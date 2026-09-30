package com.microllate.boxip

data class Ipv4SampleConfig(
    val addressesPerCidr: Int = 4
)

class Ipv4Sampler(
    private val config: Ipv4SampleConfig = Ipv4SampleConfig()
) {
    fun sample(cidrs: List<String>): List<String> {
        return cidrs
            .flatMap { sampleCidr(it) }
            .distinct()
    }

    private fun sampleCidr(cidr: String): List<String> {
        val parts = cidr.split("/")
        if (parts.size != 2) return emptyList()

        val base = ipv4ToLong(parts[0]) ?: return emptyList()
        val prefix = parts[1].toIntOrNull() ?: return emptyList()
        if (prefix !in 0..32) return emptyList()

        val hostBits = 32 - prefix
        val size = 1L shl hostBits
        val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl hostBits) and 0xFFFFFFFFL
        val network = base and mask

        if (size <= 2) {
            return listOf(network).map(::longToIpv4)
        }

        val usable = size - 2
        val count = minOf(config.addressesPerCidr.toLong(), usable).toInt()
        if (count <= 0) return emptyList()

        val offsets = if (count == 1) {
            longArrayOf(1)
        } else {
            LongArray(count) { index ->
                1 + ((usable - 1) * index / (count - 1))
            }
        }

        return offsets.map { longToIpv4(network + it) }
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
