package com.microllate.boxip

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class CloudflareIpRanges(
    val ipv4: List<String>
)

class CloudflareIpProvider {
    fun fetch(): CloudflareIpRanges {
        val connection = URL("https://api.cloudflare.com/client/v4/ips")
            .openConnection() as HttpURLConnection

        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000

        try {
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val result = JSONObject(response).getJSONObject("result")
            // For this region/network, only test Cloudflare IPv4 addresses in 104.x.x.x.
            val ipv4 = jsonArrayToList(result.getJSONArray("ipv4_cidrs"))
                .filter { cidr ->
                    cidr.substringBefore('.').toIntOrNull() == 104
                }

            return CloudflareIpRanges(ipv4)
        } finally {
            connection.disconnect()
        }
    }

    private fun jsonArrayToList(array: org.json.JSONArray): List<String> {
        return buildList {
            for (i in 0 until array.length()) {
                add(array.getString(i))
            }
        }
    }
}
