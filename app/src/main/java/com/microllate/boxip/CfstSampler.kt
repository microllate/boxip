package com.microllate.boxip

import android.content.Context
import org.json.JSONObject
import java.util.Collections
import kotlin.math.exp
import kotlin.random.Random

data class CfstSampleConfig(
    val maxSamples: Int = 300,
    val learnedRatio: Double = 0.70
)

data class CfstLearningObservation(
    val ip: String,
    val downloadSpeedMbps: Double,
    val latencyMs: Long?,
    val success: Boolean
)

class CfstSampler(
    context: Context,
    private val networkKey: String,
    private val config: CfstSampleConfig = CfstSampleConfig()
) {
    companion object {
        private const val RETAIN_FASTEST_MIN_SPEED_MBPS = 10.0
    }
    private val prefs = context.getSharedPreferences("boxip_learning", Context.MODE_PRIVATE)
    private val historyKey = "history_$networkKey"
    private val ipHistoryKey = "ip_history_$networkKey"
    private val fastestIpsKey = "fastest_ips_$networkKey"
    // Legacy single-IP keys, used only to migrate the previous implementation.
    private val legacyFastestIpKey = "fastest_ip_$networkKey"
    private val legacyFastestSpeedKey = "fastest_speed_$networkKey"

    fun sample(cidrs: List<String>): List<String> {
        val candidates = ArrayList<String>()
        for (cidr in cidrs) addCidrSamples(cidr, candidates)

        val uniqueCandidates = candidates.distinct().toMutableList()

        // Retest every retained fast IP (>10 MB/s). Add them before the size check
        // so retained IPs are guaranteed to reach TCP scan.
        getHistoricalFastestIps().forEach { fastestIp ->
            if (fastestIp.startsWith("104.") && !uniqueCandidates.contains(fastestIp)) {
                uniqueCandidates.add(fastestIp)
            }
        }

        if (uniqueCandidates.size <= config.maxSamples) return uniqueCandidates.shuffled()

        val candidateBySubnet = LinkedHashMap<String, String>()
        for (ip in uniqueCandidates) {
            candidateBySubnet.putIfAbsent(subnet24(ip), ip)
        }

        val history = loadHistory()
        val learned = candidateBySubnet.entries.mapNotNull { (subnet, ip) ->
            history[subnet]?.let { subnet to Pair(ip, it) }
        }

        val learnedCount = minOf(
            (config.maxSamples * config.learnedRatio).toInt(),
            learned.size
        )
        val selected = LinkedHashSet<String>()

        // Keep every retained fast IP even if its /24 would otherwise
        // lose the learned/random selection.
        getHistoricalFastestIps().forEach { fastestIp ->
            if (uniqueCandidates.contains(fastestIp) && selected.size < config.maxSamples) {
                selected += fastestIp
            }
        }

        if (learnedCount > 0) {
            val remaining = learned.filterNot { selected.contains(it.second.first) }.toMutableList()
            repeat(learnedCount) {
                val picked = weightedPick(remaining) ?: return@repeat
                selected += picked.second.first
                remaining.remove(picked)
            }
        }

        val remainingRandom = uniqueCandidates.filterNot(selected::contains).toMutableList()
        Collections.shuffle(remainingRandom)
        for (ip in remainingRandom) {
            if (selected.size >= config.maxSamples) break
            selected += ip
        }

        return selected.toList()
    }

    fun getHistoricalFastestIps(): List<String> {
        val retained = linkedSetOf<String>()

        // Current retained list.
        prefs.getString(fastestIpsKey, null)?.let { raw ->
            try {
                val array = JSONObject("{\"ips\":$raw}").getJSONArray("ips")
                for (index in 0 until array.length()) {
                    val ip = array.optString(index)
                    if (ip.isNotBlank() && ip.startsWith("104.")) {
                        retained += ip
                    }
                }
            } catch (_: Exception) {
                // Ignore malformed retained-IP data.
            }
        }

        // Migrate the previous single retained IP if it was above the threshold.
        val legacyIp = prefs.getString(legacyFastestIpKey, null)
        val legacySpeed = prefs.getFloat(legacyFastestSpeedKey, 0f).toDouble()
        if (!legacyIp.isNullOrBlank() &&
            legacyIp.startsWith("104.") &&
            legacySpeed > RETAIN_FASTEST_MIN_SPEED_MBPS
        ) {
            retained += legacyIp
        }

        // Once an IP has enough observations, require at least 50% successful
        // downloads and an average speed above 10 MB/s. This prevents a one-time
        // fast IP from staying in the priority pool after repeated failures.
        val ipHistory = loadIpHistory()
        return retained.filter { ip ->
            val stats = ipHistory[ip] ?: return@filter true
            if (stats.observations < 3) {
                true
            } else {
                val successRate = stats.successes.toDouble() / stats.observations
                successRate >= 0.5 &&
                    stats.avgSpeedMbps > RETAIN_FASTEST_MIN_SPEED_MBPS
            }
        }
    }

    fun record(observations: List<CfstLearningObservation>) {
        if (observations.isEmpty()) return

        val ipHistory = loadIpHistory().toMutableMap()
        val now = System.currentTimeMillis()

        for (observation in observations) {
            val old = ipHistory[observation.ip] ?: CfstIpHistory()
            val count = old.observations + 1
            val successes = old.successes + if (observation.success) 1 else 0
            val speed = if (observation.success) observation.downloadSpeedMbps else 0.0
            val avgSpeed = if (count == 1) {
                speed
            } else {
                (old.avgSpeedMbps * old.observations + speed) / count
            }

            ipHistory[observation.ip] = CfstIpHistory(
                observations = count,
                successes = successes,
                avgSpeedMbps = avgSpeed,
                lastSpeedMbps = speed,
                lastSeenMs = now
            )
        }
        saveIpHistory(ipHistory)

        val history = loadHistory().toMutableMap()
        val now = System.currentTimeMillis()

        for (observation in observations) {
            val subnet = subnet24(observation.ip)
            val old = history[subnet] ?: CfstSubnetHistory()
            val count = old.observations + 1
            val successes = old.successes + if (observation.success) 1 else 0
            val avgSpeed = if (observation.success && observation.downloadSpeedMbps > 0.0) {
                if (old.successfulObservations == 0) {
                    observation.downloadSpeedMbps
                } else {
                    (old.avgSpeedMbps * old.successfulObservations + observation.downloadSpeedMbps) /
                        (old.successfulObservations + 1)
                }
            } else {
                old.avgSpeedMbps
            }
            val avgLatency = observation.latencyMs?.let { latency ->
                if (old.latencyObservations == 0) latency.toDouble()
                else (old.avgLatencyMs * old.latencyObservations + latency) /
                    (old.latencyObservations + 1)
            } ?: old.avgLatencyMs

            history[subnet] = CfstSubnetHistory(
                observations = count,
                successes = successes,
                successfulObservations = old.successfulObservations + if (observation.success) 1 else 0,
                avgSpeedMbps = avgSpeed,
                latencyObservations = old.latencyObservations + if (observation.latencyMs != null) 1 else 0,
                avgLatencyMs = avgLatency,
                lastSeenMs = now
            )
        }

        saveHistory(history)

        // Retain every IP whose latest download speed was above 10 MB/s.
        // If this run has no qualifying result, keep all previously retained IPs.
        val qualifyingIps = observations
            .filter {
                it.success &&
                    it.downloadSpeedMbps > RETAIN_FASTEST_MIN_SPEED_MBPS
            }
            .map { it.ip }
            .filter { it.startsWith("104.") }

        if (qualifyingIps.isNotEmpty()) {
            val retained = linkedSetOf<String>()
            retained += getHistoricalFastestIps()
            retained += qualifyingIps

            val array = org.json.JSONArray()
            retained.forEach { array.put(it) }

            prefs.edit()
                .putString(fastestIpsKey, array.toString())
                .apply()
        }
    }

    private fun weightedPick(
        entries: MutableList<Pair<String, Pair<String, CfstSubnetHistory>>>
    ): Pair<String, Pair<String, CfstSubnetHistory>>? {
        if (entries.isEmpty()) return null

        var total = 0.0
        val weights = entries.map { (_, value) ->
            val history = value.second
            val successRate = history.successes.toDouble() / history.observations.coerceAtLeast(1)
            val ageDays = (System.currentTimeMillis() - history.lastSeenMs).coerceAtLeast(0L) /
                86_400_000.0
            val recency = exp(-ageDays / 14.0)
            val speedWeight = maxOf(history.avgSpeedMbps, 0.1)
            val weight = speedWeight * (0.2 + 0.8 * successRate) * (0.25 + 0.75 * recency)
            total += weight
            weight
        }

        if (total <= 0.0) return entries.random()
        var target = Random.nextDouble() * total
        for (index in entries.indices) {
            target -= weights[index]
            if (target <= 0.0) return entries[index]
        }
        return entries.last()
    }

    private fun loadIpHistory(): MutableMap<String, CfstIpHistory> {
        val result = mutableMapOf<String, CfstIpHistory>()
        val raw = prefs.getString(ipHistoryKey, null) ?: return result
        return try {
            val root = JSONObject(raw)
            for (key in root.keys()) {
                val item = root.getJSONObject(key)
                result[key] = CfstIpHistory(
                    observations = item.optInt("observations", 0),
                    successes = item.optInt("successes", 0),
                    avgSpeedMbps = item.optDouble("avgSpeedMbps", 0.0),
                    lastSpeedMbps = item.optDouble("lastSpeedMbps", 0.0),
                    lastSeenMs = item.optLong("lastSeenMs", 0L)
                )
            }
            result
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun saveIpHistory(history: Map<String, CfstIpHistory>) {
        val root = JSONObject()
        for ((ip, item) in history) {
            root.put(ip, JSONObject().apply {
                put("observations", item.observations)
                put("successes", item.successes)
                put("avgSpeedMbps", item.avgSpeedMbps)
                put("lastSpeedMbps", item.lastSpeedMbps)
                put("lastSeenMs", item.lastSeenMs)
            })
        }
        prefs.edit().putString(ipHistoryKey, root.toString()).apply()
    }

    private fun loadHistory(): MutableMap<String, CfstSubnetHistory> {
        val result = mutableMapOf<String, CfstSubnetHistory>()
        val raw = prefs.getString(historyKey, null) ?: return result
        return try {
            val root = JSONObject(raw)
            for (key in root.keys()) {
                val item = root.getJSONObject(key)
                result[key] = CfstSubnetHistory(
                    observations = item.optInt("observations", 0),
                    successes = item.optInt("successes", 0),
                    successfulObservations = item.optInt("successfulObservations", 0),
                    avgSpeedMbps = item.optDouble("avgSpeedMbps", 0.0),
                    latencyObservations = item.optInt("latencyObservations", 0),
                    avgLatencyMs = item.optDouble("avgLatencyMs", 0.0),
                    lastSeenMs = item.optLong("lastSeenMs", 0L)
                )
            }
            result
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun saveHistory(history: Map<String, CfstSubnetHistory>) {
        val root = JSONObject()
        for ((subnet, item) in history) {
            root.put(subnet, JSONObject().apply {
                put("observations", item.observations)
                put("successes", item.successes)
                put("successfulObservations", item.successfulObservations)
                put("avgSpeedMbps", item.avgSpeedMbps)
                put("latencyObservations", item.latencyObservations)
                put("avgLatencyMs", item.avgLatencyMs)
                put("lastSeenMs", item.lastSeenMs)
            })
        }
        prefs.edit().putString(historyKey, root.toString()).apply()
    }

    private fun addCidrSamples(cidr: String, out: MutableList<String>) {
        val parts = cidr.split("/")
        if (parts.size != 2) return

        val base = ipv4ToLong(parts[0]) ?: return
        val prefix = parts[1].toIntOrNull() ?: return
        if (prefix !in 0..32) return

        val hostBits = 32 - prefix
        val size = 1L shl hostBits
        val mask = if (prefix == 0) {
            0L
        } else {
            (0xFFFFFFFFL shl hostBits) and 0xFFFFFFFFL
        }
        val network = base and mask

        if (prefix <= 24) {
            val blockCount = 1L shl (24 - prefix)
            for (block in 0 until blockCount) {
                val subnet24 = network + block * 256L
                val lastOctet = Random.nextInt(0, 256)
                out += longToIpv4(subnet24 + lastOctet)
            }
        } else {
            val offset = Random.nextLong(size)
            out += longToIpv4(network + offset)
        }
    }

    private fun subnet24(ip: String): String {
        val parts = ip.split('.')
        return if (parts.size == 4) {
            "${parts[0]}.${parts[1]}.${parts[2]}.0/24"
        } else {
            ip
        }
    }

    private fun ipv4ToLong(ip: String): Long? {
        val parts = ip.split(".")
        if (parts.size != 4) return null

        var value = 0L
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255) return null
            value = (value shl 8) or octet.toLong()
        }
        return value
    }

    private fun longToIpv4(value: Long): String {
        return listOf(
            (value ushr 24) and 255,
            (value ushr 16) and 255,
            (value ushr 8) and 255,
            value and 255
        ).joinToString(".")
    }

    private data class CfstIpHistory(
        val observations: Int = 0,
        val successes: Int = 0,
        val avgSpeedMbps: Double = 0.0,
        val lastSpeedMbps: Double = 0.0,
        val lastSeenMs: Long = 0L
    )

    private data class CfstSubnetHistory(
        val observations: Int = 0,
        val successes: Int = 0,
        val successfulObservations: Int = 0,
        val avgSpeedMbps: Double = 0.0,
        val latencyObservations: Int = 0,
        val avgLatencyMs: Double = 0.0,
        val lastSeenMs: Long = 0L
    )
}
