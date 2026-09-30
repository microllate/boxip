package com.microllate.boxip

import android.net.Network
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

data class VlessWsResult(
    val ip: String,
    val latencyMs: Long?,
    val success: Boolean,
    val stage: String,
    val error: String?
)

class VlessWsScanner(
    private val network: Network,
    private val timeoutMs: Int = 5000,
    private val concurrency: Int = 8
) {
    fun scan(
        ips: List<String>,
        host: String,
        path: String,
        uuid: String
    ): List<VlessWsResult> {
        if (ips.isEmpty()) return emptyList()

        val executor = Executors.newFixedThreadPool(concurrency.coerceAtLeast(1))
        try {
            val tasks = ips.distinct().map { ip ->
                Callable { test(ip, host, path, uuid) }
            }

            return executor.invokeAll(tasks)
                .map { it.get() }
                .sortedWith(
                    compareBy<VlessWsResult> { !it.success }
                        .thenBy { it.latencyMs ?: Long.MAX_VALUE }
                )
        } finally {
            executor.shutdown()
            executor.awaitTermination(20, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }

    private fun test(
        ip: String,
        host: String,
        path: String,
        uuidText: String
    ): VlessWsResult {
        val start = System.nanoTime()
        var rawSocket: Socket? = null

        fun result(success: Boolean, stage: String, error: String? = null): VlessWsResult {
            return VlessWsResult(
                ip = ip,
                latencyMs = if (success) (System.nanoTime() - start) / 1_000_000 else null,
                success = success,
                stage = stage,
                error = error
            )
        }

        try {
            val uuid = try {
                UUID.fromString(uuidText)
            } catch (_: Exception) {
                return result(false, "UUID", "UUID 格式无效")
            }

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, null, null)

            rawSocket = network.socketFactory.createSocket()
            rawSocket.soTimeout = timeoutMs
            rawSocket.connect(InetSocketAddress(ip, 443), timeoutMs)

            val sslSocket = sslContext.socketFactory.createSocket(
                rawSocket,
                host,
                443,
                true
            ) as SSLSocket

            sslSocket.use {
                rawSocket = null
                it.soTimeout = timeoutMs

                val parameters = it.sslParameters
                parameters.serverNames = listOf(SNIHostName(host))
                parameters.endpointIdentificationAlgorithm = "HTTPS"
                runCatching {
                    parameters.applicationProtocols = arrayOf("http/1.1")
                }
                it.sslParameters = parameters

                it.startHandshake()

                val input = BufferedInputStream(it.inputStream)
                val output = it.outputStream

                val wsKeyBytes = ByteArray(16)
                SecureRandom().nextBytes(wsKeyBytes)
                val wsKey = Base64.getEncoder().encodeToString(wsKeyBytes)

                val normalizedPath = if (path.startsWith("/")) path else "/$path"
                val wsRequest = buildString {
                    append("GET ").append(normalizedPath).append(" HTTP/1.1\r\n")
                    append("Host: ").append(host).append("\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("User-Agent: BoxIP/0.1\r\n")
                    append("\r\n")
                }

                output.write(wsRequest.toByteArray(Charsets.US_ASCII))
                output.flush()

                val wsStatus = readHttpStatus(input)
                if (wsStatus != 101) {
                    return result(false, "WebSocket", "HTTP $wsStatus")
                }

                val vlessRequest = buildVlessRequest(uuid, 443, host)
                output.write(buildClientWsFrame(vlessRequest))
                output.flush()

                val frame = readWsFrame(input)
                    ?: return result(false, "VLESS", "WebSocket 连接提前关闭")

                if (frame.opcode == 0x8) {
                    return result(false, "VLESS", "服务器关闭 WebSocket")
                }

                if (frame.opcode != 0x2 || frame.payload.size < 2) {
                    return result(false, "VLESS", "未收到 VLESS 二进制响应")
                }

                val version = frame.payload[0].toInt() and 0xFF
                if (version != 1) {
                    return result(false, "VLESS", "VLESS 响应版本=$version")
                }

                return result(true, "VLESS")
            }
        } catch (e: Exception) {
            return result(false, "连接", e.javaClass.simpleName + ": " + (e.message ?: ""))
        } finally {
            rawSocket?.close()
        }
    }

    private fun readHttpStatus(input: BufferedInputStream): Int {
        val buffer = ByteArrayOutputStream()
        while (buffer.size() < 16 * 1024) {
            val value = input.read()
            if (value < 0) break
            buffer.write(value)
            val bytes = buffer.toByteArray()
            val size = bytes.size
            if (size >= 4 &&
                bytes[size - 4] == '\r'.code.toByte() &&
                bytes[size - 3] == '\n'.code.toByte() &&
                bytes[size - 2] == '\r'.code.toByte() &&
                bytes[size - 1] == '\n'.code.toByte()
            ) {
                val firstLine = bytes.toString(Charsets.US_ASCII)
                    .lineSequence()
                    .firstOrNull()
                    ?: return -1
                return firstLine.split(" ").getOrNull(1)?.toIntOrNull() ?: -1
            }
        }
        return -1
    }

    private fun buildVlessRequest(uuid: UUID, port: Int, address: String): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(1)
        output.write(uuidToBytes(uuid))
        output.write(0)
        output.write(1)
        output.write((port ushr 8) and 0xFF)
        output.write(port and 0xFF)

        val addressBytes = address.toByteArray(Charsets.UTF_8)
        output.write(2)
        output.write(addressBytes.size)
        output.write(addressBytes)

        return output.toByteArray()
    }

    private fun uuidToBytes(uuid: UUID): ByteArray {
        val buffer = ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    private fun buildClientWsFrame(payload: ByteArray): ByteArray {
        val maskKey = ByteArray(4)
        SecureRandom().nextBytes(maskKey)

        val output = ByteArrayOutputStream()
        output.write(0x82)

        when {
            payload.size < 126 -> {
                output.write(0x80 or payload.size)
            }
            payload.size <= 0xFFFF -> {
                output.write(0x80 or 126)
                output.write((payload.size ushr 8) and 0xFF)
                output.write(payload.size and 0xFF)
            }
            else -> {
                output.write(0x80 or 127)
                for (shift in 56 downTo 0 step 8) {
                    output.write((payload.size.toLong() ushr shift).toInt() and 0xFF)
                }
            }
        }

        output.write(maskKey)

        val masked = ByteArray(payload.size)
        for (i in payload.indices) {
            masked[i] = (payload[i].toInt() xor (maskKey[i % 4].toInt() and 0xFF)).toByte()
        }
        output.write(masked)

        return output.toByteArray()
    }

    private data class WsFrame(val opcode: Int, val payload: ByteArray)

    private fun readWsFrame(input: BufferedInputStream): WsFrame? {
        val first = input.read()
        val second = input.read()
        if (first < 0 || second < 0) return null

        val opcode = first and 0x0F
        val masked = (second and 0x80) != 0
        var length = second and 0x7F

        if (length == 126) {
            length = (input.read() shl 8) or input.read()
        } else if (length == 127) {
            var longLength = 0L
            repeat(8) {
                longLength = (longLength shl 8) or (input.read().toLong() and 0xFF)
            }
            if (longLength > Int.MAX_VALUE) return null
            length = longLength.toInt()
        }

        val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
        val payload = ByteArray(length)
        readFully(input, payload)

        if (mask != null) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor (mask[i % 4].toInt() and 0xFF)).toByte()
            }
        }

        return WsFrame(opcode, payload)
    }

    private fun readFully(input: BufferedInputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw IllegalStateException("unexpected EOF")
            offset += count
        }
    }
}
