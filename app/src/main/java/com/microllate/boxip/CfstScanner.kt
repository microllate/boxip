package com.microllate.boxip

import android.net.Network
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class CfstScanResult(
    val ip: String,
    val sent: Int,
    val received: Int,
    val lossRate: Double,
    val latencyMs: Long?
)

class CfstScanner(
    private val network: Network,
    private val pingTimes: Int = 4,
    private val timeoutMs: Int = 1000,
    private val concurrency: Int = 20
) {
    fun scan(ips: List<String>): List<CfstScanResult> {
        if (ips.isEmpty()) return emptyList()

        val executor = Executors.newFixedThreadPool(concurrency.coerceAtLeast(1))

        try {
            val tasks = ips.distinct().map { ip ->
                Callable { test(ip) }
            }

            return executor.invokeAll(tasks)
                .map { it.get() }
                .filter { it.received > 0 }
                .sortedWith(
                    compareBy<CfstScanResult> { it.lossRate }
                        .thenBy { it.latencyMs ?: Long.MAX_VALUE }
                )
        } finally {
            executor.shutdown()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }

    private fun test(ip: String): CfstScanResult {
        var received = 0
        var totalLatencyNs = 0L

        repeat(pingTimes.coerceAtLeast(1)) {
            val start = System.nanoTime()

            try {
                val socket = network.socketFactory.createSocket()
                socket.use {
                    it.connect(InetSocketAddress(ip, 443), timeoutMs)
                }

                received++
                totalLatencyNs += System.nanoTime() - start
            } catch (_: Exception) {
            }
        }

        val sent = pingTimes.coerceAtLeast(1)
        val lossRate = (sent - received).toDouble() / sent.toDouble()
        val latencyMs = if (received > 0) {
            totalLatencyNs / received / 1_000_000
        } else {
            null
        }

        return CfstScanResult(
            ip = ip,
            sent = sent,
            received = received,
            lossRate = lossRate,
            latencyMs = latencyMs
        )
    }
}
