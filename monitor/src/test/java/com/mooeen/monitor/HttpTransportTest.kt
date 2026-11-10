package com.mooeen.monitor

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

/** 只验证本机传输边界；不访问 cloud、账号或宿主业务。 */
class HttpTransportTest {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val transport = HttpTransport("monitor-unit-test")
    private val requestLine = AtomicReference<String>()
    private val requestToken = AtomicReference<String>()
    private var worker: Thread? = null

    @After fun close() {
        server.close()
        worker?.join(1000)
        assertTrue(requestLine.get().orEmpty().startsWith("POST / HTTP/1.1"))
        assertEquals("unit-project-token", requestToken.get())
    }

    private fun serve(code: Int, body: String = "", headers: Map<String, String> = emptyMap()): String {
        worker = Thread {
            runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 2000
                    val reader = socket.getInputStream().bufferedReader()
                    requestLine.set(reader.readLine())
                    var contentLength = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("X-Project-Token:", true)) requestToken.set(line.substringAfter(':').trim())
                        if (line.startsWith("Content-Length:", true)) contentLength = line.substringAfter(':').trim().toInt()
                    }
                    repeat(contentLength) { reader.read() }
                    val bytes = body.toByteArray()
                    val head = "HTTP/1.1 $code Test\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n" +
                        headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
                    socket.getOutputStream().use { output ->
                        output.write(head.toByteArray())
                        output.write(bytes)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        return "http://127.0.0.1:${server.localPort}/"
    }

    @Test fun successfulResponseIsReadForReceiptValidation() {
        val body = """{"ok":true,"results":[]}"""
        assertEquals(Transport.Result.Ok(body), transport.post(serve(200, body), "unit-project-token", "{}"))
    }

    @Test fun redirectsAreNotFollowedWithProjectCredentials() {
        val result = transport.post(serve(302, headers = mapOf("Location" to "http://127.0.0.1:1/")), "unit-project-token", "{}")
        assertEquals(Transport.Result.Retryable("http 302"), result)
    }

    @Test fun rateLimitCarriesRetryAfter() {
        assertEquals(Transport.Result.Retryable("429", 120), transport.post(serve(429, headers = mapOf("Retry-After" to "120")), "unit-project-token", "{}"))
    }

    @Test fun timeoutResponseIsRetainedForRetry() {
        assertEquals(Transport.Result.Retryable("http 408"), transport.post(serve(408), "unit-project-token", "{}"))
    }

    @Test fun oversizedResponseIsNotAnAcknowledgement() {
        val result = transport.post(serve(200, "x".repeat(129 * 1024)), "unit-project-token", "{}")
        assertTrue(result is Transport.Result.Retryable)
    }
}
