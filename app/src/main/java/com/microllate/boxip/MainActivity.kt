package com.microllate.boxip

import android.app.Activity
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()

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
                    val candidates = CfstSampler().sample(ranges.ipv4)

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
                        statusText.text = "CFST：TCPing 443，4 次/每个 IP..."
                    }

                    val results = CfstScanner(
                        network = physicalNetwork,
                        pingTimes = 4,
                        timeoutMs = 1000,
                        concurrency = 20
                    ).scan(candidates)

                    val downloadCandidates = results.take(20)

                    statusText.post {
                        statusText.text = "CFST：正在下载测速前 20 个 IP..."
                    }

                    val downloadResults = CfstDownloader(
                        network = physicalNetwork,
                        timeoutMs = 10_000,
                        connectTimeoutMs = 3_000
                    ).download(downloadCandidates.map { it.ip })

                    val resultByIp = downloadResults.associateBy { it.ip }

                    val output = buildString {
                        append("Cloudflare IPv4 网段：")
                        append(ranges.ipv4.size)
                        append("\n候选 IP：")
                        append(candidates.size)
                        append("\nTCPing 可用：")
                        append(results.size)
                        append("\n下载测速候选：")
                        append(downloadCandidates.size)
                        append("\n下载测速成功：")
                        append(downloadResults.size)
                        append("\n\nIP                丢包     延迟      下载速度\n")

                        downloadCandidates.mapNotNull { scanResult ->
                            resultByIp[scanResult.ip]?.let { downloadResult ->
                                scanResult to downloadResult
                            }
                        }.sortedByDescending { it.second.downloadSpeedMbps }
                            .forEach { (scanResult, downloadResult) ->
                                append(String.format(
                                    "%-16s  %.0f%%      %-7s  %.2f MB/s\n",
                                    scanResult.ip,
                                    scanResult.lossRate * 100,
                                    scanResult.latencyMs?.toString() ?: "-",
                                    downloadResult.downloadSpeedMbps
                                ))
                            }
                    }

                    runOnUiThread {
                        statusText.text = "CFST 下载测速完成"
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
