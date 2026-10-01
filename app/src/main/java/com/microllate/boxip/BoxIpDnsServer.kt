package com.microllate.boxip

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Local DNS server used by sing-box.
 *
 * 127.0.0.1:1053
 *
 * life.mozzarella.top -> current Cloudflare IP
 *
 * This server does not modify Android system DNS.
 */
object BoxIpDnsServer {

    private const val TAG = "BoxIpDnsServer"

    private const val HOST = "life.mozzarella.top"
    private const val ADDRESS = "127.0.0.1"
    private const val PORT = 1053
    private const val TTL_SECONDS = 5

    @Volatile
    private var currentIp = "104.16.26.155"

    @Volatile
    private var started = false

    private const val REQUEST_THREADS = 4

    private var socket: DatagramSocket? = null
    private var serverExecutor: ExecutorService? = null
    private var requestExecutor: ExecutorService? = null

    @Synchronized
    fun start() {
        if (started) return

        try {
            val localAddress = InetAddress.getByName(ADDRESS)

            val newSocket = DatagramSocket(null).apply {
                reuseAddress = false
                receiveBufferSize = 64 * 1024
                sendBufferSize = 64 * 1024
                bind(InetSocketAddress(localAddress, PORT))
            }

            socket = newSocket
            started = true

            requestExecutor = Executors.newFixedThreadPool(REQUEST_THREADS)
            serverExecutor = Executors.newSingleThreadExecutor()
            serverExecutor?.execute {
                serve(newSocket)
            }

            Log.i(TAG, "DNS server started: $ADDRESS:$PORT")
        } catch (e: Exception) {
            started = false
            socket = null

            Log.e(TAG, "Failed to start DNS server", e)
        }
    }

    fun setCurrentIp(ip: String) {
        if (isValidIpv4(ip)) {
            currentIp = ip
            Log.d(TAG, "Current IP changed: $ip")
        }
    }

    fun getCurrentIp(): String = currentIp

    @Synchronized
    fun stop() {
        started = false

        try {
            socket?.close()
        } catch (_: Exception) {
        }

        socket = null

        serverExecutor?.shutdownNow()
        serverExecutor = null

        requestExecutor?.shutdownNow()
        requestExecutor = null

        Log.i(TAG, "DNS server stopped")
    }

    private fun serve(localSocket: DatagramSocket) {
        val buffer = ByteArray(1500)

        while (started) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                localSocket.receive(packet)

                // Copy every request-owned value before handing the request to
                // a worker. DatagramPacket is reused on the receive loop, so
                // workers must never read address/port/data from that object
                // after the next receive().
                val request = packet.data.copyOfRange(
                    packet.offset,
                    packet.offset + packet.length
                )
                val clientAddress = packet.address
                val clientPort = packet.port

                requestExecutor?.execute {
                    handleRequest(
                        localSocket,
                        clientAddress,
                        clientPort,
                        request
                    )
                }
            } catch (e: Exception) {
                if (!started || localSocket.isClosed) break
                Log.e(TAG, "DNS receive error", e)
            }
        }
    }

    private fun handleRequest(
        localSocket: DatagramSocket,
        clientAddress: InetAddress,
        clientPort: Int,
        request: ByteArray
    ) {
        try {
            val response = buildResponse(request) ?: return
            if (!started || localSocket.isClosed) return

            val reply = DatagramPacket(
                response,
                response.size,
                clientAddress,
                clientPort
            )

            localSocket.send(reply)
        } catch (e: Exception) {
            if (started && !localSocket.isClosed) {
                Log.e(TAG, "DNS response error", e)
            }
        }
    }

    private fun buildResponse(data: ByteArray): ByteArray? {
        if (data.size < 12) return null

        val idHigh = data[0].toInt() and 0xff
        val idLow = data[1].toInt() and 0xff

        val requestFlags =
            ((data[2].toInt() and 0xff) shl 8) or
                (data[3].toInt() and 0xff)

        val questionCount =
            ((data[4].toInt() and 0xff) shl 8) or
                (data[5].toInt() and 0xff)

        if (questionCount < 1) return null

        var position = 12
        val labels = mutableListOf<String>()

        while (position < data.size) {
            val labelLength = data[position].toInt() and 0xff
            position++

            if (labelLength == 0) break
            if ((labelLength and 0xC0) != 0) return null
            if (labelLength > 63 || position + labelLength > data.size) {
                return null
            }

            labels += String(
                data,
                position,
                labelLength,
                Charsets.US_ASCII
            )
            position += labelLength
        }

        if (position + 4 > data.size) return null

        val qtype =
            ((data[position].toInt() and 0xff) shl 8) or
                (data[position + 1].toInt() and 0xff)

        val qclass =
            ((data[position + 2].toInt() and 0xff) shl 8) or
                (data[position + 3].toInt() and 0xff)

        position += 4

        val queryName = labels.joinToString(".").lowercase()
        val question = data.copyOfRange(12, position)

        if (queryName != HOST || qclass != 1 || qtype != 1) {
            return buildNoAnswerResponse(
                idHigh,
                idLow,
                requestFlags,
                question
            )
        }

        val ipBytes = ipv4ToBytes(currentIp) ?: return null

        val answer = byteArrayOf(
            0xC0.toByte(), 0x0C.toByte(),
            0x00, 0x01,
            0x00, 0x01,
            ((TTL_SECONDS ushr 24) and 0xff).toByte(),
            ((TTL_SECONDS ushr 16) and 0xff).toByte(),
            ((TTL_SECONDS ushr 8) and 0xff).toByte(),
            (TTL_SECONDS and 0xff).toByte(),
            0x00, 0x04,
            ipBytes[0],
            ipBytes[1],
            ipBytes[2],
            ipBytes[3]
        )

        val responseFlags =
            0x8000 or
                (requestFlags and 0x7800) or
                (requestFlags and 0x0100) or
                0x0080

        return byteArrayOf(
            idHigh.toByte(),
            idLow.toByte(),
            ((responseFlags ushr 8) and 0xff).toByte(),
            (responseFlags and 0xff).toByte(),
            0x00, 0x01,
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00
        ) + question + answer
    }

    private fun buildNoAnswerResponse(
        idHigh: Int,
        idLow: Int,
        requestFlags: Int,
        question: ByteArray
    ): ByteArray {
        val responseFlags =
            0x8000 or
                (requestFlags and 0x7800) or
                (requestFlags and 0x0100) or
                0x0080

        return byteArrayOf(
            idHigh.toByte(),
            idLow.toByte(),
            ((responseFlags ushr 8) and 0xff).toByte(),
            (responseFlags and 0xff).toByte(),
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00
        ) + question
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
