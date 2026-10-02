package com.microllate.boxip

import android.net.Network
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
 * Real VLESS + WebSocket probe.
 *
 * The transport is implemented by NativeVlessWsClient. No sing-box process,
 * local SOCKS listener, or temporary configuration is required.
 */
class VlessWsScanner(
    @Suppress("UNUSED_PARAMETER") private val network: Network,
    private val timeoutMs: Int = 8_000,
    private val concurrency: Int = 4
) {
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
        @Suppress("UNUSED_PARAMETER") interfaceName: String? = null,
        onResult: ((VlessWsResult) -> Unit)? = null
    ): List<VlessWsResult> {
        if (ips.isEmpty()) return emptyList()

        val profile = loadProfile(host)
            ?: return ips.distinct().map {
                VlessWsResult(it, null, false, "配置", "未找到正在使用的 VLESS 配置: " + host)
            }

        val executor = Executors.newFixedThreadPool(concurrency.coerceAtLeast(1))
        try {
            val tasks = ips.distinct().map { ip ->
                Callable {
                    val result = test(ip, host, path, profile)
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
            val process = ProcessBuilder("su", "-c", "cat '/data/adb/box/sing-box/config.json'")
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
        profile: Profile
    ): VlessWsResult {
        return try {
            val wsPath = requestedPath.ifBlank { profile.wsPath }
            val normalizedPath = if (wsPath.startsWith("/")) wsPath else "/$wsPath"

            // First native stage: TCP + TLS/SNI + WebSocket Upgrade.
            // VLESS payload is intentionally not sent yet.
            val result = NativeVlessWsClient(timeoutMs).probe(
                ip = ip,
                port = profile.serverPort,
                serverName = profile.tlsServerName.ifBlank { host },
                wsHost = profile.wsHost.ifBlank { host },
                wsPath = normalizedPath
            )

            if (result.success) {
                VlessWsResult(
                    ip = ip,
                    latencyMs = result.elapsedMs,
                    success = true,
                    stage = "原生 TLS + WS",
                    error = "WebSocket 101"
                )
            } else {
                VlessWsResult(
                    ip = ip,
                    latencyMs = null,
                    success = false,
                    stage = "原生 TLS + WS",
                    error = result.error ?: ("HTTP " + (result.statusCode ?: "无响应"))
                )
            }
        } catch (e: Exception) {
            VlessWsResult(
                ip = ip,
                latencyMs = null,
                success = false,
                stage = "原生 TLS + WS",
                error = e.javaClass.simpleName +
                    if (!e.message.isNullOrBlank()) ": " + e.message else ""
            )
        }
    }
}
