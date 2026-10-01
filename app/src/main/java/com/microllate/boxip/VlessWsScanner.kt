package com.microllate.boxip

import android.net.Network
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray
import org.json.JSONObject

data class VlessWsResult(
    val ip: String,
    val latencyMs: Long?,
    val success: Boolean,
    val stage: String,
    val error: String?
)

/**
 * Experimental real-node probe.
 *
 * One temporary sing-box process is shared by all candidate IPs.
 * Each candidate gets its own local SOCKS listener and VLESS outbound.
 * The measured time starts only when the real HTTPS request begins, so
 * sing-box process startup is not included in the IP latency.
 */
class VlessWsScanner(
    private val network: Network,
    private val timeoutMs: Int = 8_000,
    private val concurrency: Int = 4
) {
    companion object {
        private const val SING_BOX = "/data/adb/box/bin/sing-box"
        private const val TEST_PATH = "/cdn-cgi/trace"
        private const val BASE_PORT = 18480
    }

    fun scan(
        ips: List<String>,
        host: String,
        path: String,
        uuid: String,
        interfaceName: String? = null
    ): List<VlessWsResult> {
        if (ips.isEmpty()) return emptyList()

        val distinctIps = ips.distinct()
        val pathParts = splitPath(path)
        val workDir = File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp")
        val stamp = System.nanoTime()
        val configFile = File(workDir, "boxip-probe-" + stamp + ".json")
        val logFile = File(workDir, "boxip-probe-" + stamp + ".log")
        var process: Process? = null

        try {
            val ports = distinctIps.indices.associateWith { BASE_PORT + it }
            configFile.writeText(
                buildConfig(
                    ips = distinctIps,
                    host = host,
                    path = pathParts.first,
                    earlyData = pathParts.second,
                    uuid = uuid,
                    interfaceName = interfaceName,
                    ports = ports
                )
            )

            val command = "exec " + SING_BOX + " run -c " + shellQuote(configFile.absolutePath)
            process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .redirectOutput(logFile)
                .start()

            if (!waitForProxies(ports.values, process, timeoutMs)) {
                val detail = readLog(logFile)
                return distinctIps.map {
                    VlessWsResult(
                        ip = it,
                        latencyMs = null,
                        success = false,
                        stage = "sing-box",
                        error = if (detail.isEmpty()) "临时 sing-box 未能启动全部测试端口" else detail
                    )
                }
            }

            val executor = Executors.newFixedThreadPool(concurrency.coerceIn(1, 8))
            return try {
                distinctIps.mapIndexed { index, ip ->
                    executor.submit(Callable {
                        testRequest(
                            ip = ip,
                            host = host,
                            port = ports.getValue(index)
                        )
                    })
                }.mapNotNull { future ->
                    runCatching { future.get() }.getOrNull()
                }.sortedWith(
                    compareBy<VlessWsResult> { !it.success }
                        .thenBy { it.latencyMs ?: Long.MAX_VALUE }
                )
            } finally {
                executor.shutdown()
                executor.awaitTermination(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                executor.shutdownNow()
            }
        } catch (e: Exception) {
            val detail = readLog(logFile)
            val message = buildString {
                append(e.javaClass.simpleName)
                if (!e.message.isNullOrBlank()) {
                    append(": ")
                    append(e.message)
                }
                if (detail.isNotBlank()) {
                    append("\n")
                    append(detail.takeLast(1200))
                }
            }
            return distinctIps.map {
                VlessWsResult(
                    ip = it,
                    latencyMs = null,
                    success = false,
                    stage = "真实代理流量",
                    error = message
                )
            }
        } finally {
            process?.let {
                runCatching { it.destroy() }
                runCatching {
                    if (!it.waitFor(500, TimeUnit.MILLISECONDS)) {
                        it.destroyForcibly()
                    }
                }
            }
            configFile.delete()
            logFile.delete()
        }
    }

    private fun testRequest(
        ip: String,
        host: String,
        port: Int
    ): VlessWsResult {
        return try {
            /*
             * Keep one SOCKS -> VLESS -> TLS -> WS connection alive.
             * The first HTTP request is only a warm-up. The following three
             * requests reuse the already-established proxy tunnel, so their
             * median is a much better measure of steady-state performance.
             */
            val proxy = Proxy(
                Proxy.Type.SOCKS,
                InetSocketAddress("127.0.0.1", port)
            )
            val tunnel = Socket(proxy)
            tunnel.connect(InetSocketAddress.createUnresolved(host, 443), timeoutMs)
            tunnel.soTimeout = timeoutMs

            val sslFactory = javax.net.ssl.SSLContext.getDefault().socketFactory
            val ssl = sslFactory.createSocket(tunnel, host, 443, true) as javax.net.ssl.SSLSocket
            ssl.soTimeout = timeoutMs
            val params = ssl.sslParameters
            params.serverNames = listOf(javax.net.ssl.SNIHostName(host))
            ssl.sslParameters = params
            ssl.startHandshake()

            val input = ssl.inputStream.buffered()
            val output = ssl.outputStream.buffered()

            // Warm-up: establishes the TLS/WS/VLESS-backed HTTP session.
            readHttpResponse(
                input = input,
                output = output,
                host = host,
                measure = false
            )

            val samples = mutableListOf<Long>()
            repeat(3) {
                val elapsed = readHttpResponse(
                    input = input,
                    output = output,
                    host = host,
                    measure = true
                )
                samples += elapsed
            }

            ssl.close()

            val sorted = samples.sorted()
            val median = sorted[sorted.size / 2]

            VlessWsResult(
                ip = ip,
                latencyMs = median,
                success = true,
                stage = "稳定真实代理流量",
                error = "3次复用连接: " + samples.joinToString("/") + " ms"
            )
        } catch (e: Exception) {
            VlessWsResult(
                ip = ip,
                latencyMs = null,
                success = false,
                stage = "稳定真实代理流量",
                error = e.javaClass.simpleName +
                    if (!e.message.isNullOrBlank()) ": " + e.message else ""
            )
        }
    }

    private fun readHttpResponse(
        input: java.io.BufferedInputStream,
        output: java.io.BufferedOutputStream,
        host: String,
        measure: Boolean
    ): Long {
        val start = if (measure) System.nanoTime() else 0L

        output.write(
            ("GET " + TEST_PATH + " HTTP/1.1\\r\\n" +
                "Host: " + host + "\\r\\n" +
                "Connection: keep-alive\\r\\n" +
                "Accept: */*\\r\\n" +
                "User-Agent: BoxIP-Probe/1.0\\r\\n" +
                "\\r\\n").toByteArray(Charsets.US_ASCII)
        )
        output.flush()

        val statusLine = readLine(input)
        val statusParts = statusLine.split(" ", limit = 3)
        if (statusParts.size < 2) {
            throw java.io.IOException("invalid HTTP status")
        }
        val code = statusParts[1].toIntOrNull()
            ?: throw java.io.IOException("invalid HTTP status code")

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim().lowercase()] =
                    line.substring(colon + 1).trim()
            }
        }

        val transferEncoding = headers["transfer-encoding"]?.lowercase()
        val contentLength = headers["content-length"]?.toLongOrNull()

        when {
            transferEncoding?.contains("chunked") == true -> readChunkedBody(input)
            contentLength != null -> readExactly(input, contentLength)
            else -> {
                // A keep-alive response should normally provide a length.
                // If it doesn't, don't wait for a connection close.
                throw java.io.IOException("HTTP response has no reusable body length")
            }
        }

        if (code !in 200..399) {
            throw java.io.IOException("HTTP $code")
        }

        return if (measure) {
            (System.nanoTime() - start) / 1_000_000L
        } else {
            0L
        }
    }

    private fun readLine(input: java.io.BufferedInputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw java.io.EOFException("connection closed")
            if (b == 10) break
            if (b != 13) bytes.write(b)
        }
        return bytes.toString(Charsets.US_ASCII.name())
    }

    private fun readExactly(
        input: java.io.BufferedInputStream,
        length: Long
    ) {
        var remaining = length
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw java.io.EOFException("response body truncated")
            remaining -= read
        }
    }

    private fun readChunkedBody(input: java.io.BufferedInputStream) {
        while (true) {
            val sizeLine = readLine(input)
            val size = sizeLine.substringBefore(';').trim().toLong(16)
            if (size == 0L) {
                while (true) {
                    if (readLine(input).isEmpty()) break
                }
                return
            }
            readExactly(input, size)
            val cr = input.read()
            val lf = input.read()
            if (cr != 13 || lf != 10) {
                throw java.io.IOException("invalid chunk terminator")
            }
        }
    }

    private fun buildConfig(
        ips: List<String>,
        host: String,
        path: String,
        earlyData: Int,
        uuid: String,
        interfaceName: String?,
        ports: Map<Int, Int>
    ): String {
        val inbounds = JSONArray()
        val outbounds = JSONArray()
        val rules = JSONArray()

        ips.forEachIndexed { index, ip ->
            val inboundTag = "probe-in-" + index
            val outboundTag = "probe-out-" + index

            val inbound = JSONObject()
                .put("type", "mixed")
                .put("tag", inboundTag)
                .put("listen", "127.0.0.1")
                .put("listen_port", ports.getValue(index))

            val transport = JSONObject()
                .put("type", "ws")
                .put("path", path)
                .put("headers", JSONObject().put("Host", host))

            if (earlyData > 0) {
                transport
                    .put("max_early_data", earlyData)
                    .put("early_data_header_name", "Sec-WebSocket-Protocol")
            }

            val tls = JSONObject()
                .put("enabled", true)
                .put("server_name", host)

            val vless = JSONObject()
                .put("type", "vless")
                .put("tag", outboundTag)
                .put("server", ip)
                .put("server_port", 443)
                .put("uuid", uuid)
                .put("tls", tls)
                .put("transport", transport)
                .put("packet_encoding", "xudp")

            if (!interfaceName.isNullOrBlank()) {
                vless.put("bind_interface", interfaceName)
            }

            inbounds.put(inbound)
            outbounds.put(vless)

            rules.put(
                JSONObject()
                    .put("inbound", JSONArray().put(inboundTag))
                    .put("action", "route")
                    .put("outbound", outboundTag)
            )
        }

        return JSONObject()
            .put("log", JSONObject().put("level", "error"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put(
                "route",
                JSONObject()
                    .put("rules", rules)
                    .put("final", outbounds.getJSONObject(0).getString("tag"))
            )
            .toString()
    }

    private fun splitPath(raw: String): Pair<String, Int> {
        val normalized = if (raw.startsWith("/")) raw else "/" + raw
        val question = normalized.indexOf('?')
        if (question < 0) return normalized to 2560

        val path = normalized.substring(0, question).ifEmpty { "/" }
        val query = normalized.substring(question + 1)
        val ed = query.split('&')
            .asSequence()
            .mapNotNull {
                val parts = it.split('=', limit = 2)
                if (parts.size == 2 && parts[0] == "ed") {
                    parts[1].toIntOrNull()
                } else {
                    null
                }
            }
            .firstOrNull()
            ?: 2560

        return path to ed.coerceIn(0, 16384)
    }

    private fun waitForProxies(
        ports: Collection<Int>,
        process: Process,
        timeoutMs: Int
    ): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) return false

            var allReady = true
            for (port in ports) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress("127.0.0.1", port), 150)
                    }
                } catch (_: Exception) {
                    allReady = false
                    break
                }
            }

            if (allReady) return true
            Thread.sleep(50)
        }
        return false
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private fun readLog(file: File): String {
        return runCatching {
            if (file.exists()) file.readText().trim() else ""
        }.getOrDefault("")
    }
}
