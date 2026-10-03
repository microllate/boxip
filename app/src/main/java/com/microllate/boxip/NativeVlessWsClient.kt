package com.microllate.boxip

import android.net.Network
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * Native VLESS+WS transport probe.
 *
 * This first-stage client intentionally stops after the WebSocket upgrade.
 * It does not start sing-box and does not send a VLESS request yet.
 */
class NativeVlessWsClient(
    private val network: Network,
    private val timeoutMs: Int = 8_000
) {
    data class Result(
        val success: Boolean,
        val elapsedMs: Long?,
        val statusCode: Int?,
        val error: String?
    )

    fun probe(
        ip: String,
        port: Int,
        serverName: String,
        wsHost: String,
        wsPath: String
    ): Result {
        val started = System.nanoTime()
        var socket: SSLSocket? = null

        return try {
            val tcp = Socket()
            network.bindSocket(tcp)
            tcp.connect(InetSocketAddress(ip, port), timeoutMs)
            tcp.soTimeout = timeoutMs

            val context = SSLContext.getInstance("TLS")
            context.init(null, null, SecureRandom())

            socket = (context.socketFactory.createSocket(tcp, serverName, port, true) as SSLSocket).apply {
                soTimeout = timeoutMs
                enabledProtocols = enabledProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
                sslParameters = sslParameters.apply {
                    serverNames = listOf(SNIHostName(serverName))
                }
                startHandshake()
            }

            val keyBytes = ByteArray(16)
            SecureRandom().nextBytes(keyBytes)
            val webSocketKey = android.util.Base64.encodeToString(
                keyBytes,
                android.util.Base64.NO_WRAP
            )

            val normalizedPath = if (wsPath.startsWith("/")) wsPath else "/$wsPath"
            val request = buildString {
                append("GET ").append(normalizedPath).append(" HTTP/1.1\r\n")
                append("Host: ").append(wsHost.ifBlank { serverName }).append("\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: ").append(webSocketKey).append("\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("User-Agent: BoxIP/NativeVlessProbe\r\n")
                append("\r\n")
            }

            val out = socket.getOutputStream()
            out.write(request.toByteArray(StandardCharsets.US_ASCII))
            out.flush()

            val reader = BufferedReader(
                InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1)
            )
            val statusLine = reader.readLine() ?: error("WebSocket 无响应")
            val statusCode = Regex("^HTTP/1\\.[01]\\s+(\\d{3})").find(statusLine)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: error("无效 HTTP 状态: $statusLine")

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val elapsed = (System.nanoTime() - started) / 1_000_000L
            if (statusCode != 101) {
                Result(false, null, statusCode, statusLine)
            } else {
                Result(true, elapsed, statusCode, "WebSocket 101")
            }
        } catch (e: Exception) {
            Result(
                false,
                null,
                null,
                e.javaClass.simpleName +
                    if (!e.message.isNullOrBlank()) ": ${e.message}" else ""
            )
        } finally {
            runCatching { socket?.close() }
        }
    }
}
