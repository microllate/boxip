package com.microllate.boxip

import android.app.Activity
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()

    companion object {
        private const val TEST_HOST = "life.mozzarella.top"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val resultText = findViewById<TextView>(R.id.resultText)
        val startButton = findViewById<Button>(R.id.startScanButton)
        val connectivityManager = getSystemService(ConnectivityManager::class.java)

        startButton.setOnClickListener {
            startButton.isEnabled = false
            statusText.text = "正在获取 Cloudflare IPv4 网段..."
            resultText.text = ""

            executor.execute {
                try {
                    val ranges = CloudflareIpProvider().fetch()
                    val candidates = Ipv4Sampler().sample(ranges.ipv4)

                    statusText.post {
                        statusText.text = "第一轮：TCP 443 可达性测试..."
                    }

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

                    val tcpResults = TcpScanner(network = physicalNetwork).scan(candidates)
                    val tcpSuccessful = tcpResults.filter { it.success }

                    statusText.post {
                        statusText.text = "第二轮：HTTPS + SNI + /cdn-cgi/trace..."
                    }

                    val httpsResults = HttpsTraceScanner(network = physicalNetwork).scan(
                        ips = tcpSuccessful.map { it.ip },
                        host = TEST_HOST
                    )
                    val httpsSuccessful = httpsResults.filter { it.success }.take(30)

                    val output = buildString {
                        append("测试域名：")
                        append(TEST_HOST)
                        append("\n")
                        append("IPv4 网段：")
                        append(ranges.ipv4.size)
                        append("\n候选 IP：")
                        append(candidates.size)
                        append("\nTCP 443 成功：")
                        append(tcpSuccessful.size)
                        append("\nHTTPS + SNI 成功：")
                        append(httpsResults.count { it.success })
                        append("\n")

                        append("\nHTTPS 实测结果（手机真实网络）：\n")

                        if (httpsSuccessful.isEmpty()) {
                            append("没有获取到有效 Cloudflare trace")
                        } else {
                            httpsSuccessful.forEachIndexed { index, result ->
                                append(index + 1)
                                append(". ")
                                append(result.ip)
                                append("  ")
                                append(result.latencyMs)
                                append(" ms")
                                append("  colo=")
                                append(result.colo)
                                append("  HTTP ")
                                append(result.statusCode)
                                append("\n")
                            }
                        }
                    }

                    runOnUiThread {
                        statusText.text = "HTTPS + SNI 测试完成"
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
