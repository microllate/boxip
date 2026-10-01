package com.microllate.boxip

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Publishes BoxIP's selected Cloudflare IP as a sing-box hosts file.
 *
 * BoxIP no longer implements a local UDP DNS server. sing-box owns DNS
 * request concurrency, caching, packet parsing and response delivery.
 *
 * The generated file contains:
 *   <current-ip> life.mozzarella.top
 *
 * Configure sing-box once with a hosts DNS server pointing at this file.
 */
object BoxIpDnsServer {

    private const val TAG = "BoxIpDnsServer"
    private const val HOST = "life.mozzarella.top"
    private const val FILE_NAME = "boxip.hosts"

    @Volatile
    private var currentIp = ""

    @Volatile
    private var hostsFile: File? = null

    @Synchronized
    fun start(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        hostsFile = file

        try {
            file.parentFile?.mkdirs()
            publish(file)
            Log.i(TAG, "sing-box hosts file ready: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize hosts file", e)
        }
    }

    fun setCurrentIp(ip: String) {
        if (!isValidIpv4(ip)) return

        currentIp = ip

        val file = hostsFile ?: return
        try {
            publish(file)
            Log.d(TAG, "Current IP published to hosts: $ip")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish current IP: $ip", e)
        }
    }

    fun getCurrentIp(): String = currentIp

    @Synchronized
    fun stop() {
        // The hosts file intentionally remains in place. sing-box can keep
        // reading the last known-good entry even if the UI activity is gone.
        hostsFile = null
    }

    private fun publish(file: File) {
        val ip = currentIp
        if (!isValidIpv4(ip)) {
            return
        }

        val parent = file.parentFile ?: return
        parent.mkdirs()

        // Write a complete temporary file first, then atomically replace the
        // published file. sing-box must never observe a partially written line.
        val temp = File(parent, "${file.name}.tmp")
        temp.writeText(
            "# BoxIP managed file - do not edit manually\n" +
                "$ip $HOST\n",
            Charsets.US_ASCII
        )

        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) {
                throw IllegalStateException("Unable to replace hosts file")
            }
        }
    }

    private fun ipv4ToBytes(ip: String): ByteArray? {
        val parts = ip.split('.')
        if (parts.size != 4) return null

        val result = ByteArray(4)

        for (i in 0 until 4) {
            val value = parts[i].toIntOrNull() ?: return null
            if (value !in 0..255) return null
            result[i] = value.toByte()
        }

        return result
    }

    private fun isValidIpv4(ip: String): Boolean {
        return ipv4ToBytes(ip) != null
    }
}
