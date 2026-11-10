package com.mooeen.monitor

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

internal interface Transport {
    sealed class Result {
        data class Ok(val body: String) : Result()
        data class Unauthorized(val code: Int) : Result()
        object Rejected : Result()
        data class Retryable(val reason: String, val afterSeconds: Long = 0) : Result()
    }
    fun post(url: String, token: String, body: String): Result
}

internal class HttpTransport(private val userAgent: String) : Transport {
    override fun post(url: String, token: String, body: String): Transport.Result {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 10_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Project-Token", token)
                setRequestProperty("User-Agent", userAgent)
            }
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            when (val code = conn.responseCode) {
                in 200..299 -> {
                    val response = ByteArrayOutputStream()
                    conn.inputStream.use { input ->
                        val buffer = ByteArray(4096)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (response.size() + n > 128 * 1024) return Transport.Result.Retryable("response too large")
                            response.write(buffer, 0, n)
                        }
                    }
                    Transport.Result.Ok(response.toString("UTF-8"))
                }
                401, 403 -> Transport.Result.Unauthorized(code)
                400, 413, 415, 422 -> Transport.Result.Rejected
                429 -> Transport.Result.Retryable("429", retryAfter(conn.getHeaderField("Retry-After")))
                else -> Transport.Result.Retryable("http $code")
            }
        } catch (e: Throwable) {
            Transport.Result.Retryable(e.javaClass.simpleName)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    internal fun retryAfter(raw: String?, nowMillis: Long = System.currentTimeMillis()): Long {
        val value = raw?.trim() ?: return 0
        value.toLongOrNull()?.let { return it.coerceIn(0, (Long.MAX_VALUE - nowMillis.coerceAtLeast(0)) / 1000) }
        return runCatching {
            val date = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(value) ?: return 0
            ((date.time - nowMillis + 999) / 1000).coerceAtLeast(0)
        }.getOrDefault(0)
    }
}
