package com.microllate.boxip

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class CloudflareIpRanges(
    val ipv4: List<String>,
    val ipv6: List<String>
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

            val ipv4 = jsonArrayToList(result.getJSONArray("ipv4_cidrs"))
            val ipv6 = jsonArrayToList(result.getJSONArray("ipv6_cidrs"))

            return CloudflareIpRanges(ipv4, ipv6)
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
