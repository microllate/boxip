package com.microllate.boxip

import android.net.Network
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Locale
import kotlin.math.roundToLong
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import java.util.concurrent.Executors

data class CfstDownloadResult(
    val ip: String,
    val tcpConnectMs: Long,
    val tlsHandshakeMs: Long,
    val ttfbMs: Long,
    val stabilityPercent: Double,
    val downloadSpeedMbps: Double,
    val durationMs: Long,
    val pop: String?
)

class CfstDownloader(
    private val network: Network,
    private val downloadUrl: String = "https://speed.cloudflare.com/cdn-cgi/trace",
    private val observationMs: Int = 30_000,
    private val probeIntervalMs: Int = 5_000,
    private val connectTimeoutMs: Int = 3_000,
    private val probeTimeoutMs: Int = 3_000,
    private val concurrency: Int = 3
) {
    private data class ProbeResult(
        val tcpConnectMs: Long,
        val tlsHandshakeMs: Long,
        val ttfbMs: Long,
        val pop: String?
    )

    fun download(ips: List<String>): List<CfstDownloadResult> {
        val distinctIps = ips.distinct()
        if (distinctIps.isEmpty()) return emptyList()

        val pool = Executors.newFixedThreadPool(concurrency.coerceIn(1, 8))
        return try {
            val futures = distinctIps.map { ip ->
                pool.submit<CfstDownloadResult?> {
                    runCatching { test(ip) }.getOrNull()
                }
            }

            futures.mapNotNull { future ->
                runCatching { future.get() }.getOrNull()
            }.sortedWith(
                compareByDescending<CfstDownloadResult> { it.stabilityPercent }
                    .thenBy { it.ttfbMs }
                    .thenBy { it.tlsHandshakeMs }
                    .thenBy { it.tcpConnectMs }
            )
        } finally {
            pool.shutdownNow()
        }
    }

    private fun test(ip: String): CfstDownloadResult? {
        val uri = URI(downloadUrl)
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        if (uri.scheme.lowercase() != "https" || port != 443) return null

        val observationStartNs = System.nanoTime()
        val deadlineNs = observationStartNs + observationMs * 1_000_000L
        val intervalNs = probeIntervalMs.coerceAtLeast(1) * 1_000_000L

        var nextProbeNs = observationStartNs
        var attempts = 0
        var successes = 0
        val successfulProbes = mutableListOf<ProbeResult>()
        var lastPop: String? = null

        while (System.nanoTime() < deadlineNs) {
            val waitNs = nextProbeNs - System.nanoTime()
            if (waitNs > 0L) {
                try {
                    Thread.sleep(waitNs / 1_000_000L, (waitNs % 1_000_000L).toInt())
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }

            if (System.nanoTime() >= deadlineNs && attempts > 0) break

            attempts++
            val probe = probe(ip, host, uri)
            if (probe != null) {
                successes++
                successfulProbes += probe
                lastPop = probe.pop ?: lastPop
            }

            nextProbeNs += intervalNs
        }

        if (successfulProbes.isEmpty()) return null

        val avgTcpConnectMs = successfulProbes.map { it.tcpConnectMs }.average().roundToLong()
        val avgTlsHandshakeMs = successfulProbes.map { it.tlsHandshakeMs }.average().roundToLong()
        val avgTtfbMs = successfulProbes.map { it.ttfbMs }.average().roundToLong()

        val elapsedMs = ((System.nanoTime() - observationStartNs) / 1_000_000L)
            .coerceAtLeast(1L)

        val stabilityPercent = if (attempts == 0) {
            0.0
        } else {
            successes.toDouble() / attempts.toDouble() * 100.0
        }

        return CfstDownloadResult(
            ip = ip,
            tcpConnectMs = avgTcpConnectMs,
            tlsHandshakeMs = avgTlsHandshakeMs,
            ttfbMs = avgTtfbMs,
            stabilityPercent = stabilityPercent,
            // Kept for compatibility with the existing learning model.
            // The new test intentionally does not use bulk download speed
            // as the entry-quality metric.
            downloadSpeedMbps = 0.0,
            durationMs = elapsedMs,
            pop = (lastPop ?: successfulProbe.pop)?.uppercase(Locale.US)
        )
    }

    private fun probe(
        ip: String,
        host: String,
        uri: URI
    ): ProbeResult? {
        var rawSocket: Socket? = null
        var sslSocket: SSLSocket? = null

        return try {
            val tcpStartNs = System.nanoTime()
            rawSocket = network.socketFactory.createSocket()
            rawSocket.connect(InetSocketAddress(ip, 443), connectTimeoutMs)
            val tcpConnectMs = elapsedMs(tcpStartNs)

            rawSocket.soTimeout = probeTimeoutMs

            val context = SSLContext.getInstance("TLS")
            context.init(null, null, null)

            val tlsStartNs = System.nanoTime()
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
            val tlsHandshakeMs = elapsedMs(tlsStartNs)

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
                append("User-Agent: BoxIP-CFST/0.3\r\n")
                append("Accept-Encoding: identity\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }

            output.write(request.toByteArray(Charsets.US_ASCII))
            output.flush()

            val ttfbStartNs = System.nanoTime()
            val statusLine = readHeader(input) ?: return null
            val ttfbMs = elapsedMs(ttfbStartNs)

            if (!statusLine.startsWith("HTTP/1.1 200") &&
                !statusLine.startsWith("HTTP/2 200")
            ) {
                return null
            }

            val headers = readHeaders(input) ?: return null
            val pop = headers["cf-ray"]
                ?.substringAfterLast("-", "")
                ?.takeIf { it.length == 3 }

            ProbeResult(
                tcpConnectMs = tcpConnectMs,
                tlsHandshakeMs = tlsHandshakeMs,
                ttfbMs = ttfbMs,
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

    private fun elapsedMs(startNs: Long): Long {
        return ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(0L)
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
