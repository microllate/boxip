package com.microllate.boxip

import android.net.Network
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.UUID
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
 * It uses the rooted device's existing sing-box binary as the actual
 * VLESS + TLS + WebSocket client, then sends a real HTTPS request through
 * a temporary loopback SOCKS proxy.
 */
class VlessWsScanner(
    private val network: Network,
    private val timeoutMs: Int = 8_000,
    private val concurrency: Int = 2
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

        UUID.fromString(uuid)

        val executor = Executors.newFixedThreadPool(concurrency.coerceIn(1, 4))
        return try {
            ips.distinct().mapIndexed { index, ip ->
                executor.submit(Callable {
                    test(ip, host, path, uuid, interfaceName, BASE_PORT + index)
                })
            }.mapNotNull { future ->
                runCatching { future.get() }.getOrNull()
            }.sortedWith(
                compareBy<VlessWsResult> { !it.success }
                    .thenBy { it.latencyMs ?: Long.MAX_VALUE }
            )
        } finally {
            executor.shutdown()
            executor.awaitTermination(2, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }

    private fun test(
        ip: String,
        host: String,
        rawPath: String,
        uuid: String,
        interfaceName: String?,
        port: Int
    ): VlessWsResult {
        val startNs = System.nanoTime()
        val workDir = File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp")
        val configFile = File(workDir, "boxip-probe-" + port + ".json")
        val logFile = File(workDir, "boxip-probe-" + port + ".log")
        var process: Process? = null

        fun result(success: Boolean, stage: String, error: String? = null): VlessWsResult {
            return VlessWsResult(
                ip = ip,
                latencyMs = if (success) {
                    (System.nanoTime() - startNs) / 1_000_000L
                } else null,
                success = success,
                stage = stage,
                error = error
            )
        }

        try {
            val pathParts = splitPath(rawPath)
            configFile.writeText(
                buildConfig(
                    ip = ip,
                    host = host,
                    path = pathParts.first,
                    earlyData = pathParts.second,
                    uuid = uuid,
                    interfaceName = interfaceName,
                    port = port
                )
            )

            val command = "exec " + SING_BOX + " run -c " +
                shellQuote(configFile.absolutePath)

            process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .redirectOutput(logFile)
                .start()

            if (!waitForProxy(port, process, timeoutMs)) {
                val detail = readLog(logFile)
                return result(
                    false,
                    "sing-box",
                    if (detail.isEmpty()) "sing-box 未能启动 SOCKS 入站" else detail
                )
            }

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
                return VlessWsResult(
                    ip = ip,
                    latencyMs = elapsed,
                    success = true,
                    stage = "真实代理流量",
                    error = "HTTP " + code
                )
            }

            return result(false, "真实代理流量", "测试请求返回 HTTP " + code)
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
            return result(false, "真实代理流量", message)
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

    private fun buildConfig(
        ip: String,
        host: String,
        path: String,
        earlyData: Int,
        uuid: String,
        interfaceName: String?,
        port: Int
    ): String {
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
            .put("tag", "probe")
            .put("server", ip)
            .put("server_port", 443)
            .put("uuid", uuid)
            .put("tls", tls)
            .put("transport", transport)
            .put("packet_encoding", "xudp")

        if (!interfaceName.isNullOrBlank()) {
            vless.put("bind_interface", interfaceName)
        }

        val inbound = JSONObject()
            .put("type", "mixed")
            .put("tag", "probe-in")
            .put("listen", "127.0.0.1")
            .put("listen_port", port)

        return JSONObject()
            .put("log", JSONObject().put("level", "error"))
            .put("inbounds", JSONArray().put(inbound))
            .put("outbounds", JSONArray().put(vless))
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
                } else null
            }
            .firstOrNull()
            ?: 2560

        return path to ed.coerceIn(0, 16384)
    }

    private fun waitForProxy(port: Int, process: Process, timeoutMs: Int): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) return false
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 150)
                }
                return true
            } catch (_: Exception) {
                Thread.sleep(50)
            }
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
