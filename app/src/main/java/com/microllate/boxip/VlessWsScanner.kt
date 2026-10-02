package com.microllate.boxip

import android.net.Network
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.net.HttpURLConnection
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
 * Each candidate gets its own temporary sing-box process, local SOCKS listener,
 * and VLESS outbound, so candidate failures do not contaminate one another.
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
        private const val MAIN_CONFIG = "/data/adb/box/sing-box/config.json"
        private const val TEST_URL_PATH = "/cdn-cgi/trace"
        private const val BASE_PORT = 18480
    }

    private data class Profile(
        val uuid: String,
        val serverPort: Int,
        val tlsServerName: String,
        val wsPath: String,
        val wsHost: String,
        val earlyData: Int?
    )

    fun scan(
        ips: List<String>,
        host: String,
        path: String,
        interfaceName: String? = null,
        onResult: ((VlessWsResult) -> Unit)? = null
    ): List<VlessWsResult> {
        if (ips.isEmpty()) return emptyList()

        val profile = loadProfile(host)
            ?: return ips.distinct().map {
                VlessWsResult(it, null, false, "配置", "未找到正在使用的 VLESS 配置: " + host)
            }

        val executor = Executors.newFixedThreadPool(concurrency.coerceAtLeast(1))
        try {
            val tasks = ips.distinct().mapIndexed { index, ip ->
                Callable {
                    val result = test(ip, host, path, profile, interfaceName, BASE_PORT + index)
                    runCatching { onResult?.invoke(result) }
                    result
                }
            }
            return executor.invokeAll(tasks).map { it.get() }
                .sortedWith(
                    compareBy<VlessWsResult> { !it.success }
                        .thenBy { it.latencyMs ?: Long.MAX_VALUE }
                )
        } finally {
            executor.shutdown()
            executor.awaitTermination(30, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }

    private fun loadProfile(host: String): Profile? {
        return try {
            val process = ProcessBuilder("su", "-c", "cat '" + MAIN_CONFIG + "'")
                .redirectErrorStream(true)
                .start()
            val json = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor(3, TimeUnit.SECONDS)
            if (json.isBlank()) return null
            findVless(JSONObject(json), host)
        } catch (_: Exception) {
            null
        }
    }

    private fun findVless(value: Any?, host: String): Profile? {
        when (value) {
            is JSONObject -> {
                if (value.optString("type") == "vless" && value.optString("server") == host) {
                    val tls = value.optJSONObject("tls") ?: JSONObject()
                    val transport = value.optJSONObject("transport") ?: JSONObject()
                    val headers = transport.optJSONObject("headers") ?: JSONObject()
                    val wsPath = transport.optString("path", "/")
                    val wsHost = headers.optString("Host", host)
                    val earlyData = parseEarlyData(wsPath)
                        ?: transport.optInt("max_early_data", 0).takeIf { it > 0 }

                    return Profile(
                        uuid = value.optString("uuid"),
                        serverPort = value.optInt("server_port", 443),
                        tlsServerName = tls.optString("server_name", host),
                        wsPath = wsPath.substringBefore('?'),
                        wsHost = wsHost,
                        earlyData = earlyData
                    )
                }

                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    findVless(value.opt(key), host)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    findVless(value.opt(i), host)?.let { return it }
                }
            }
        }
        return null
    }

    private fun parseEarlyData(path: String): Int? {
        val match = Regex("[?&]ed=(\\d+)").find(path) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun test(
        ip: String,
        host: String,
        requestedPath: String,
        profile: Profile,
        interfaceName: String?,
        port: Int
    ): VlessWsResult {
        val temp = File.createTempFile("boxip-vless-", ".json")
        val log = File.createTempFile("boxip-vless-", ".log")
        var process: Process? = null

        try {
            val (wsPath, earlyData) = splitPath(requestedPath.ifBlank { profile.wsPath }, profile.earlyData)
            temp.writeText(buildConfig(ip, host, profile, wsPath, earlyData, interfaceName, port))

            val command = "exec '" + SING_BOX + "' run -c '" + temp.absolutePath +
                "' > '" + log.absolutePath + "' 2>&1"
            process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()

            if (!waitForPort(port, timeoutMs)) {
                return VlessWsResult(
                    ip, null, false, "sing-box",
                    log.readText().takeLast(1200).ifBlank { "本地 SOCKS 未启动" }
                )
            }

            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            val start = System.nanoTime()
            val connection = (URL("https://" + host + TEST_URL_PATH)
                .openConnection(proxy) as HttpURLConnection)

            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("Host", host)
            connection.setRequestProperty("User-Agent", "BoxIP/real-vless-probe")

            val code = connection.responseCode
            connection.inputStream.use { it.readBytes() }
            connection.disconnect()

            if (code !in 200..399) {
                return VlessWsResult(ip, null, false, "真实代理", "HTTP " + code)
            }

            val elapsed = (System.nanoTime() - start) / 1_000_000L
            return VlessWsResult(ip, elapsed, true, "真实 VLESS + WS", "真实请求 HTTP " + code)
        } catch (e: Exception) {
            val logText = runCatching { log.readText().takeLast(800) }.getOrDefault("")
            val detail = e.javaClass.simpleName +
                if (!e.message.isNullOrBlank()) ": " + e.message else "" +
                if (logText.isNotBlank()) " | " + logText else ""
            return VlessWsResult(ip, null, false, "真实 VLESS + WS", detail)
        } finally {
            runCatching { process?.destroy() }
            runCatching { process?.waitFor(500, TimeUnit.MILLISECONDS) }
            runCatching { process?.destroyForcibly() }
            runCatching { temp.delete() }
            runCatching { log.delete() }
        }
    }

    private fun splitPath(path: String, fallbackEarlyData: Int?): Pair<String, Int?> {
        val normalized = if (path.startsWith("/")) path else "/" + path
        val early = parseEarlyData(normalized) ?: fallbackEarlyData
        return normalized.substringBefore('?') to early
    }

    private fun buildConfig(
        ip: String,
        host: String,
        profile: Profile,
        wsPath: String,
        earlyData: Int?,
        interfaceName: String?,
        listenPort: Int
    ): String {
        val root = JSONObject()
        root.put("log", JSONObject().apply { put("level", "error") })
        root.put("inbounds", JSONArray().put(JSONObject().apply {
            put("type", "mixed")
            put("tag", "boxip-in")
            put("listen", "127.0.0.1")
            put("listen_port", listenPort)
        }))

        root.put("outbounds", JSONArray().put(JSONObject().apply {
            put("type", "vless")
            put("tag", "boxip-vless")
            put("server", ip)
            put("server_port", profile.serverPort)
            put("uuid", profile.uuid)

            if (!interfaceName.isNullOrBlank()) put("bind_interface", interfaceName)

            put("tls", JSONObject().apply {
                put("enabled", true)
                put("server_name", profile.tlsServerName.ifBlank { host })
            })

            put("transport", JSONObject().apply {
                put("type", "ws")
                put("path", wsPath)
                put("headers", JSONObject().apply { put("Host", profile.wsHost.ifBlank { host }) })
                if (earlyData != null && earlyData > 0) {
                    put("max_early_data", earlyData)
                    put("early_data_header_name", "Sec-WebSocket-Protocol")
                }
            })
            put("packet_encoding", "xudp")
        }))

        root.put("route", JSONObject().apply { put("final", "boxip-vless") })
        return root.toString()
    }

    private fun waitForPort(port: Int, timeoutMs: Int): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            try {
                java.net.Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 150) }
                return true
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
        return false
    }
}
