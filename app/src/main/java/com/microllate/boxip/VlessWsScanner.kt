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
            val proxy = Proxy(
                Proxy.Type.SOCKS,
                InetSocketAddress("127.0.0.1", port)
            )
            val testUrl = URL("https://" + host + TEST_PATH)
            val connection = testUrl.openConnection(proxy) as HttpsURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false

            val requestStart = System.nanoTime()
            val code = connection.responseCode
            val elapsed = (System.nanoTime() - requestStart) / 1_000_000L
            connection.disconnect()

            if (code in 200..399) {
                VlessWsResult(
                    ip = ip,
                    latencyMs = elapsed,
                    success = true,
                    stage = "真实代理流量",
                    error = "HTTP " + code
                )
            } else {
                VlessWsResult(
                    ip = ip,
                    latencyMs = null,
                    success = false,
                    stage = "真实代理流量",
                    error = "HTTP " + code
                )
            }
        } catch (e: Exception) {
            VlessWsResult(
                ip = ip,
                latencyMs = null,
                success = false,
                stage = "真实代理流量",
                error = e.javaClass.simpleName +
                    if (!e.message.isNullOrBlank()) ": " + e.message else ""
            )
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
