package com.microllate.boxip

import android.net.Network
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

data class CfstDownloadResult(
    val ip: String,
    val downloadSpeedMbps: Double,
    val durationMs: Long,
    val pop: String?
)

class CfstDownloader(
    private val network: Network,
    private val downloadUrl: String = "https://speed.cloudflare.com/__down?bytes=99999999",
    private val timeoutMs: Int = 10_000,
    private val connectTimeoutMs: Int = 3_000
) {
    fun download(ips: List<String>): List<CfstDownloadResult> {
        return ips.distinct().mapNotNull { ip ->
            test(ip)
        }.sortedByDescending { it.downloadSpeedMbps }
    }

    private fun test(ip: String): CfstDownloadResult? {
        val uri = URI(downloadUrl)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        if (uri.scheme.lowercase() != "https" || port != 443) return null

        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null

        return try {
            rawSocket = network.socketFactory.createSocket()
            rawSocket.connect(InetSocketAddress(ip, 443), connectTimeoutMs)
            rawSocket.soTimeout = 1_000

            val context = SSLContext.getInstance("TLS")
            context.init(null, null, null)

            sslSocket = context.socketFactory.createSocket(
                rawSocket,
                host,
                443,
                true
            ) as SSLSocket

            val sslParameters = sslSocket.sslParameters
            sslParameters.serverNames = listOf(SNIHostName(host))
            sslSocket.sslParameters = sslParameters
            sslSocket.startHandshake()

            val session = sslSocket.session
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)) {
                return null
            }

            val output = BufferedOutputStream(sslSocket.outputStream)
            val input = BufferedInputStream(sslSocket.inputStream)

            val path = buildString {
                append(uri.rawPath.ifEmpty { "/" })
                if (!uri.rawQuery.isNullOrEmpty()) {
                    append("?")
                    append(uri.rawQuery)
                }
            }

            val request = buildString {
                append("GET ")
                append(path)
                append(" HTTP/1.1\r\n")
                append("Host: ")
                append(host)
                append("\r\n")
                append("User-Agent: BoxIP-CFST/0.1\r\n")
                append("Accept-Encoding: identity\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }

            output.write(request.toByteArray(Charsets.US_ASCII))
            output.flush()

            val statusLine = readHeader(input) ?: return null
            if (!statusLine.startsWith("HTTP/1.1 200") &&
                !statusLine.startsWith("HTTP/2 200")
            ) {
                return null
            }

            val headers = readHeaders(input) ?: return null
            val pop = headers["cf-ray"]
                ?.substringAfterLast("-", "")
                ?.takeIf { it.length == 3 }

            val startNs = System.nanoTime()
            val deadlineNs = startNs + timeoutMs * 1_000_000L
            val buffer = ByteArray(16 * 1024)
            var totalBytes = 0L

            while (System.nanoTime() < deadlineNs) {
                try {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) totalBytes += read
                } catch (_: java.net.SocketTimeoutException) {
                    if (System.nanoTime() >= deadlineNs) break
                }
            }

            val elapsedMs = ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
            val speedMbps = totalBytes.toDouble() / 1_000_000.0 /
                (elapsedMs.toDouble() / 1_000.0)

            CfstDownloadResult(
                ip = ip,
                downloadSpeedMbps = speedMbps,
                durationMs = elapsedMs,
                pop = pop?.uppercase(Locale.US)
            )
        } catch (_: Exception) {
            null
        } finally {
            try {
                sslSocket?.close()
            } catch (_: Exception) {
            }
            if (sslSocket == null) {
                try {
                    rawSocket?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun readHeader(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 8 * 1024) {
            val value = input.read()
            if (value < 0) return null
            bytes.add(value.toByte())
            if (value == '\n'.code) break
        }
        return bytes.toByteArray().toString(Charsets.ISO_8859_1).trimEnd('\r', '\n')
    }

    private fun readHeaders(input: BufferedInputStream): Map<String, String>? {
        val headers = mutableMapOf<String, String>()
        val line = ArrayList<Byte>()

        while (true) {
            line.clear()
            while (true) {
                val value = input.read()
                if (value < 0) return null
                line.add(value.toByte())
                if (value == '\n'.code) break
                if (line.size >= 16 * 1024) return null
            }

            val text = line.toByteArray()
                .toString(Charsets.ISO_8859_1)
                .trimEnd('\r', '\n')

            if (text.isEmpty()) return headers

            val separator = text.indexOf(':')
            if (separator > 0) {
                val name = text.substring(0, separator).trim().lowercase(Locale.US)
                val value = text.substring(separator + 1).trim()
                headers[name] = value
            }
        }
    }
}
