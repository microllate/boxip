package com.microllate.boxip

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.app.Activity
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val resultText = findViewById<TextView>(R.id.resultText)
        val startButton = findViewById<Button>(R.id.startScanButton)
        val hostInput = findViewById<EditText>(R.id.hostInput)
        val pathInput = findViewById<EditText>(R.id.pathInput)
        val uuidInput = findViewById<EditText>(R.id.uuidInput)
        val connectivityManager = getSystemService(ConnectivityManager::class.java)

        startButton.setOnClickListener {
            val host = hostInput.text.toString().trim()
            val path = pathInput.text.toString().trim()
            val uuid = uuidInput.text.toString().trim()

            if (host.isEmpty() || path.isEmpty() || uuid.isEmpty()) {
                statusText.text = "请填写 SNI、WS Path 和 VLESS UUID"
                return@setOnClickListener
            }

            startButton.isEnabled = false
            statusText.text = "正在获取 Cloudflare IPv4 网段..."
            resultText.text = ""

            executor.execute {
                try {
                    val ranges = CloudflareIpProvider().fetch()
                    val candidates = Ipv4Sampler().sample(ranges.ipv4)

                    val physicalNetwork = connectivityManager.allNetworks
                        .firstOrNull { network ->
                            val capabilities = connectivityManager.getNetworkCapabilities(network)
                            capabilities != null &&
                                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) &&
                                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        }

                    if (physicalNetwork == null) {
                        throw IllegalStateException("没有找到可用的 Wi-Fi/移动数据物理网络")
                    }

                    statusText.post {
                        statusText.text = "第一阶段：手机真实网络 → Cloudflare TCP 443..."
                    }

                    val tcpResults = TcpScanner(network = physicalNetwork).scan(candidates)
                    val tcpSuccessful = tcpResults.filter { it.success }

                    val physicalInterface = connectivityManager.getLinkProperties(physicalNetwork)?.interfaceName

                    statusText.post {
                        statusText.text = "第二阶段：手机 → Cloudflare → 服务器（真实 sing-box 流量）..."
                    }

                    val vlessResults = VlessWsScanner(network = physicalNetwork).scan(
                        ips = tcpSuccessful.map { it.ip },
                        host = host,
                        path = path,
                        uuid = uuid,
                        interfaceName = physicalInterface
                    )

                    val successful = vlessResults.filter { it.success }.take(30)

                    val output = buildString {
                        append("SNI / Host：")
                        append(host)
                        append("\nWS Path：")
                        append(path)
                        append("\nIPv4 网段：")
                        append(ranges.ipv4.size)
                        append("\n候选 IP：")
                        append(candidates.size)
                        append("\nTCP 443 成功：")
                        append(tcpSuccessful.size)
                        append("\nVLESS + WS 成功：")
                        append(vlessResults.count { it.success })
                        append("\n\n第二阶段结果（手机真实网络 → Cloudflare → 服务器）：\n")

                        if (successful.isEmpty()) {
                            append("没有建立成功的 VLESS + WS 连接\n\n失败阶段：\n")
                            vlessResults.take(10).forEach { result ->
                                append(result.ip)
                                append("  ")
                                append(result.stage)
                                append("  ")
                                append(result.error)
                                append("\n")
                            }
                        } else {
                            successful.forEachIndexed { index, result ->
                                append(index + 1)
                                append(". ")
                                append(result.ip)
                                append("  ")
                                append(result.latencyMs)
                                append(" ms")
                                append("  ")
                                append(result.stage)
                                append("\n")
                            }
                        }
                    }

                    runOnUiThread {
                        statusText.text = "VLESS + WS 测试完成"
                        resultText.text = output
                        startButton.isEnabled = true
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        statusText.text = "测速失败"
                        resultText.text = e.message ?: e.javaClass.simpleName
                        startButton.isEnabled = true
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
