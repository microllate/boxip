package com.microllate.boxip

import java.util.Collections
import kotlin.math.min
import kotlin.random.Random

data class CfstSampleConfig(
    val maxSamples: Int = 300
)

class CfstSampler(
    private val config: CfstSampleConfig = CfstSampleConfig()
) {
    fun sample(cidrs: List<String>): List<String> {
        val candidates = ArrayList<String>()

        for (cidr in cidrs) {
            addCidrSamples(cidr, candidates)
        }

        Collections.shuffle(candidates)
        return candidates.distinct().take(config.maxSamples)
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
}
