package com.microllate.boxip

import kotlin.math.abs

data class CfstQualityResult(
    val result: CfstDownloadResult,
    val tcpScore: Double,
    val tlsScore: Double,
    val ttfbScore: Double,
    val downloadScore: Double,
    val stabilityScore: Double,
    val totalScore: Double
)

class CfstQualityScorer(
    private val tcpWeight: Double = 0.05,
    private val tlsWeight: Double = 0.25,
    private val ttfbWeight: Double = 0.25,
    private val downloadWeight: Double = 0.15,
    private val stabilityWeight: Double = 0.30
) {
    fun rank(results: List<CfstDownloadResult>): List<CfstQualityResult> {
        if (results.isEmpty()) return emptyList()

        val tcp = results.map { it.tcpConnectMs.toDouble() }
        val tls = results.map { it.tlsHandshakeMs.toDouble() }
        val ttfb = results.map { it.ttfbMs.toDouble() }
        val download = results.map { it.downloadSpeedMbps }
        val stability = results.map { it.stabilityPercent }

        return results.map { result ->
            val tcpScore = lowerIsBetter(result.tcpConnectMs.toDouble(), tcp)
            val tlsScore = lowerIsBetter(result.tlsHandshakeMs.toDouble(), tls)
            val ttfbScore = lowerIsBetter(result.ttfbMs.toDouble(), ttfb)
            val downloadScore = higherIsBetter(result.downloadSpeedMbps, download)
            val stabilityScore = higherIsBetter(result.stabilityPercent, stability)

            val totalScore =
                tcpScore * tcpWeight +
                tlsScore * tlsWeight +
                ttfbScore * ttfbWeight +
                downloadScore * downloadWeight +
                stabilityScore * stabilityWeight

            CfstQualityResult(
                result = result,
                tcpScore = tcpScore,
                tlsScore = tlsScore,
                ttfbScore = ttfbScore,
                downloadScore = downloadScore,
                stabilityScore = stabilityScore,
                totalScore = totalScore
            )
        }.sortedWith(
            compareByDescending<CfstQualityResult> { it.totalScore }
                .thenBy { it.result.ttfbMs }
                .thenBy { it.result.tlsHandshakeMs }
                .thenBy { it.result.tcpConnectMs }
        )
    }

    private fun lowerIsBetter(value: Double, values: List<Double>): Double {
        val min = values.minOrNull() ?: value
        val max = values.maxOrNull() ?: value
        val range = max - min
        if (abs(range) < 0.000001) return 100.0
        return ((max - value) / range * 100.0).coerceIn(0.0, 100.0)
    }

    private fun higherIsBetter(value: Double, values: List<Double>): Double {
        val min = values.minOrNull() ?: value
        val max = values.maxOrNull() ?: value
        val range = max - min
        if (abs(range) < 0.000001) return 100.0
        return ((value - min) / range * 100.0).coerceIn(0.0, 100.0)
    }
}
