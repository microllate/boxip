package com.microllate.boxip

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors

/**
 * Minimal local DNS server for the BoxIP MVP.
 *
 * It listens only on 127.0.0.1:1053 and answers:
 *   A life.mozzarella.top -> currentIp
 *
 * Other queries receive NXDOMAIN. This server does not touch Android's
 * system DNS and does not affect other applications.
 */
object BoxIpDnsServer {
    private const val HOST = "life.mozzarella.top"
    private const val ADDRESS = "127.0.0.1"
    private const val PORT = 1053
    private const val TTL_SECONDS = 5

    @Volatile
    private var currentIp = "104.16.26.155"

    @Volatile
    private var started = false

    private var socket: DatagramSocket? = null
    private val executor = Executors.newSingleThreadExecutor()

    @Synchronized
    fun start() {
        if (started) return

        socket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(ADDRESS, PORT))
        }
        started = true

        executor.execute {
            serve()
        }
    }

    fun setCurrentIp(ip: String) {
        if (isValidIpv4(ip)) {
            currentIp = ip
        }
    }

    fun getCurrentIp(): String = currentIp

    @Synchronized
    fun stop() {
        socket?.close()
        socket = null
        started = false
    }

    private fun serve() {
        val buffer = ByteArray(1500)

        while (started) {
            try {
                val localSocket = socket ?: break
                val packet = DatagramPacket(buffer, buffer.size)
                localSocket.receive(packet)

                val response = buildResponse(
                    packet.data,
                    packet.offset,
                    packet.length
                ) ?: continue

                val reply = DatagramPacket(
                    response,
                    response.size,
                    packet.address,
                    packet.port
                )
                localSocket.send(reply)
            } catch (_: Exception) {
                if (!started) break
            }
        }
    }

    private fun buildResponse(
        data: ByteArray,
        offset: Int,
        length: Int
    ): ByteArray? {
        if (length < 12) return null

        val start = offset
        val end = offset + length
        val transactionIdHigh = data[start].toInt() and 0xff
        val transactionIdLow = data[start + 1].toInt() and 0xff

        var position = start + 12
        val labels = mutableListOf<String>()

        while (position < end) {
            val labelLength = data[position].toInt() and 0xff
            position++

            if (labelLength == 0) break
            if (labelLength > 63 || position + labelLength > end) return null

            labels += String(
                data,
                position,
                labelLength,
                Charsets.US_ASCII
            )
            position += labelLength
        }

        if (position + 4 > end) return null

        val queryName = labels.joinToString(".").lowercase()
        val qtype = ((data[position].toInt() and 0xff) shl 8) or
            (data[position + 1].toInt() and 0xff)
        val qclass = ((data[position + 2].toInt() and 0xff) shl 8) or
            (data[position + 3].toInt() and 0xff)

        if (queryName != HOST || qtype != 1 || qclass != 1) {
            return byteArrayOf(
                transactionIdHigh.toByte(),
                transactionIdLow.toByte(),
                0x81.toByte(),
                0x83.toByte(), // NXDOMAIN
                0x00,
                0x01, // QDCOUNT
                0x00,
                0x00, // ANCOUNT
                0x00,
                0x00, // NSCOUNT
                0x00,
                0x00  // ARCOUNT
            ) + data.copyOfRange(start + 12, position + 4)
        }

        val question = data.copyOfRange(start + 12, position + 4)
        val ipBytes = currentIp.split('.')
            .map { it.toInt() }
            .map { it.toByte() }
            .toByteArray()

        val answer = byteArrayOf(
            0xC0.toByte(), 0x0C.toByte(), // NAME -> question name
            0x00, 0x01,                   // TYPE A
            0x00, 0x01,                   // CLASS IN
            ((TTL_SECONDS ushr 24) and 0xff).toByte(),
            ((TTL_SECONDS ushr 16) and 0xff).toByte(),
            ((TTL_SECONDS ushr 8) and 0xff).toByte(),
            (TTL_SECONDS and 0xff).toByte(),
            0x00, 0x04,                   // RDLENGTH
            ipBytes[0],
            ipBytes[1],
            ipBytes[2],
            ipBytes[3]
        )

        return byteArrayOf(
            transactionIdHigh.toByte(),
            transactionIdLow.toByte(),
            0x81.toByte(),
            0x80.toByte(), // standard response, no error
            0x00,
            0x01, // QDCOUNT
            0x00,
            0x01, // ANCOUNT
            0x00,
            0x00, // NSCOUNT
            0x00,
            0x00  // ARCOUNT
        ) + question + answer
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        return parts.size == 4 && parts.all {
            it.toIntOrNull()?.let { value -> value in 0..255 } == true
        }
    }
}
