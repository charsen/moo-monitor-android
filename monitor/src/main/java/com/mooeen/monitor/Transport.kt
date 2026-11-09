package com.mooeen.monitor

import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/** 传输层抽象：单测注入假实现，生产走 [HttpTransport]。 */
internal interface Transport {
    sealed class Result {
        object Ok : Result()
        /** 401 / 403：token 无效或撤权，停止发送并自我禁用。 */
        data class Unauthorized(val code: Int) : Result()
        /** 4xx（除 401/403/429）：格式问题，重试也不会成功，丢批。 */
        object Rejected : Result()
        /** 429 / 5xx / IO：可退避重试。 */
        data class Retryable(val reason: String) : Result()
    }

    fun post(url: String, token: String, body: String): Result
}

internal class HttpTransport(private val userAgent: String) : Transport {

    override fun post(url: String, token: String, body: String): Transport.Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                // token 走头，不进 URL / 访问日志。
                setRequestProperty("X-Project-Token", token)
                setRequestProperty("User-Agent", userAgent)
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { out: OutputStream -> out.write(bytes) }

            when (val code = conn.responseCode) {
                in 200..299 -> Transport.Result.Ok
                401, 403 -> Transport.Result.Unauthorized(code)
                429 -> Transport.Result.Retryable("429")
                in 500..599 -> Transport.Result.Retryable("http $code")
                else -> Transport.Result.Rejected
            }
        } catch (e: Throwable) {
            Transport.Result.Retryable(e.javaClass.simpleName)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}
