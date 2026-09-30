package com.microllate.boxip

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class TcpScanResult(
    val ip: String,
    val latencyMs: Long?,
    val success: Boolean
)

class TcpScanner(
    private val timeoutMs: Int = 1500,
    private val concurrency: Int = 20
) {
    fun scan(ips: List<String>): List<TcpScanResult> {
        if (ips.isEmpty()) return emptyList()

        val executor = Executors.newFixedThreadPool(concurrency.coerceAtLeast(1))

        try {
            val tasks = ips.distinct().map { ip ->
                Callable { test(ip) }
            }

            return executor.invokeAll(tasks)
                .map { it.get() }
                .sortedWith(
                    compareBy<TcpScanResult> { !it.success }
                        .thenBy { it.latencyMs ?: Long.MAX_VALUE }
                )
        } finally {
            executor.shutdown()
            executor.awaitTermination(2, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }

    private fun test(ip: String): TcpScanResult {
        val start = System.nanoTime()

        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, 443), timeoutMs)
            }

            TcpScanResult(
                ip = ip,
                latencyMs = (System.nanoTime() - start) / 1_000_000,
                success = true
            )
        } catch (_: Exception) {
            TcpScanResult(
                ip = ip,
                latencyMs = null,
                success = false
            )
        }
    }
}
