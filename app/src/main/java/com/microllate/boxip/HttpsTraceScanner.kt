package com.microllate.boxip

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

data class HttpsTraceResult(
    val ip: String,
    val latencyMs: Long?,
    val success: Boolean,
    val statusCode: Int?,
    val colo: String?,
    val error: String?
)

class HttpsTraceScanner(
    private val timeoutMs: Int = 5000,
    private val concurrency: Int = 12
) {
    fun scan(ips: List<String>, host: String): List<HttpsTraceResult> {
        if (ips.isEmpty()) return emptyList()

        val executor = Executors.newFixedThreadPool(concurrency.coerceAtLeast(1))

        try {
            val tasks = ips.distinct().map { ip ->
                Callable { test(ip, host) }
            }

            return executor.invokeAll(tasks)
                .map { it.get() }
                .sortedWith(
                    compareBy<HttpsTraceResult> { !it.success }
                        .thenBy { it.latencyMs ?: Long.MAX_VALUE }
                )
        } finally {
            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }

    private fun test(ip: String, host: String): HttpsTraceResult {
        val start = System.nanoTime()

        return try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, null, null)

            val socket = sslContext.socketFactory.createSocket() as SSLSocket
            socket.use {
                it.soTimeout = timeoutMs
                it.connect(InetSocketAddress(ip, 443), timeoutMs)

                val parameters = it.sslParameters
                parameters.serverNames = listOf(SNIHostName(host))
                parameters.endpointIdentificationAlgorithm = "HTTPS"
                it.sslParameters = parameters

                it.startHandshake()

                val request = buildString {
                    append("GET /cdn-cgi/trace HTTP/1.1\r\n")
                    append("Host: ").append(host).append("\r\n")
                    append("Connection: close\r\n")
                    append("User-Agent: BoxIP/0.1\r\n")
                    append("\r\n")
                }

                val writer = it.outputStream.bufferedWriter()
                writer.write(request)
                writer.flush()

                val reader = BufferedReader(InputStreamReader(it.inputStream, Charsets.UTF_8))
                val response = reader.readText()

                val headerEnd = response.indexOf("\r\n\r\n")
                val headers = if (headerEnd >= 0) response.substring(0, headerEnd) else response
                val body = if (headerEnd >= 0) response.substring(headerEnd + 4) else ""

                val statusLine = headers.lineSequence().firstOrNull().orEmpty()
                val statusCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull()

                val colo = body.lineSequence()
                    .firstOrNull { it.startsWith("colo=") }
                    ?.substringAfter("=", "")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

                HttpsTraceResult(
                    ip = ip,
                    latencyMs = (System.nanoTime() - start) / 1_000_000,
                    success = statusCode != null && statusCode in 200..399 && colo != null,
                    statusCode = statusCode,
                    colo = colo,
                    error = null
                )
            }
        } catch (e: SocketTimeoutException) {
            HttpsTraceResult(ip, null, false, null, null, "timeout")
        } catch (e: Exception) {
            HttpsTraceResult(
                ip = ip,
                latencyMs = null,
                success = false,
                statusCode = null,
                colo = null,
                error = e.javaClass.simpleName + ": " + (e.message ?: "")
            )
        }
    }
}
