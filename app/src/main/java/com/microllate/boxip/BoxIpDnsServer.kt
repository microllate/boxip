package com.microllate.boxip

import android.util.Log

/**
 * Publishes BoxIP's selected Cloudflare IP as a sing-box hosts file.
 *
 * The file is stored in /data/adb/box/sing-box so the root-running
 * sing-box process can access the same fixed path without crossing the
 * app's private data directory.
 */
object BoxIpDnsServer {

    private const val TAG = "BoxIpDnsServer"
    private const val HOST = "life.mozzarella.top"
    private const val FILE_PATH = "/data/adb/box/sing-box/boxip.hosts"

    @Volatile
    private var currentIp = ""

    fun setCurrentIp(ip: String) {
        if (!isValidIpv4(ip)) return

        currentIp = ip

        try {
            ensureRootDirectory()
            publish()
            Log.d(TAG, "Current IP published to hosts: " + ip)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish current IP: " + ip, e)
        }
    }

    fun getCurrentIp(): String = currentIp

    private fun ensureRootDirectory() {
        runAsRoot(
            "mkdir -p /data/adb/box/sing-box && " +
                "chmod 0755 /data/adb/box/sing-box"
        )
    }

    private fun publish() {
        val ip = currentIp
        if (!isValidIpv4(ip)) return

        val content =
            "# BoxIP managed file - do not edit manually\n" +
                ip + " " + HOST + "\n"

        val command =
            "tmp=/data/adb/box/sing-box/.boxip.hosts.tmp; " +
                "cat > \$tmp && " +
                "chmod 0644 \$tmp && " +
                "mv -f \$tmp " + FILE_PATH + " && " +
                "chmod 0644 " + FILE_PATH

        runAsRoot(command, content)
    }

    private fun runAsRoot(command: String, stdin: String? = null) {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()

        stdin?.let {
            process.outputStream.bufferedWriter(Charsets.US_ASCII).use { writer ->
                writer.write(it)
            }
        }

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw IllegalStateException(
                "root command failed (" + exitCode + "): " + output.trim()
            )
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
