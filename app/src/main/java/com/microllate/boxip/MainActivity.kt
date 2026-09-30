package com.microllate.boxip

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val PREFS = "boxip_ui"
        private const val KEY_THEME = "theme"
        private const val THEME_SYSTEM = 0
        private const val THEME_LIGHT = 1
        private const val THEME_DARK = 2
    }

    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val resultText = findViewById<TextView>(R.id.resultText)
        val startButton = findViewById<Button>(R.id.startScanButton)
        val themeButton = findViewById<TextView>(R.id.themeButton)
        val rangesValue = findViewById<TextView>(R.id.rangesValue)
        val candidatesValue = findViewById<TextView>(R.id.candidatesValue)
        val tcpValue = findViewById<TextView>(R.id.tcpValue)
        val downloadValue = findViewById<TextView>(R.id.downloadValue)
        val connectivityManager = getSystemService(ConnectivityManager::class.java)

        themeButton.text = themeLabel(currentThemeMode())
        themeButton.setOnClickListener {
            val nextMode = when (currentThemeMode()) {
                THEME_SYSTEM -> THEME_LIGHT
                THEME_LIGHT -> THEME_DARK
                else -> THEME_SYSTEM
            }
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_THEME, nextMode)
                .apply()
            recreate()
        }

        startButton.setOnClickListener {
            startButton.isEnabled = false
            themeButton.isEnabled = false
            statusText.text = "正在获取 Cloudflare IPv4 网段…"
            resultText.text = "准备测速…"
            rangesValue.text = "—"
            candidatesValue.text = "—"
            tcpValue.text = "—"
            downloadValue.text = "—"

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

                    runOnUiThread {
                        rangesValue.text = ranges.ipv4.size.toString()
                        candidatesValue.text = candidates.size.toString()
                        statusText.text = "第一阶段 · TCPing 443"
                        resultText.text = "正在测试 ${candidates.size} 个候选 IP…"
                    }

                    val results = CfstScanner(
                        network = physicalNetwork,
                        pingTimes = 4,
                        timeoutMs = 1000,
                        concurrency = 20
                    ).scan(candidates)

                    val downloadCandidates = results.take(20)

                    runOnUiThread {
                        tcpValue.text = results.size.toString()
                        statusText.text = "第二阶段 · 下载测速"
                        resultText.text = "TCPing 完成，正在测试前 ${downloadCandidates.size} 个 IP…"
                    }

                    val downloadResults = CfstDownloader(
                        network = physicalNetwork,
                        timeoutMs = 10_000,
                        connectTimeoutMs = 3_000
                    ).download(downloadCandidates.map { it.ip })

                    val resultByIp = downloadResults.associateBy { it.ip }

                    val output = buildString {
                        append(String.format(
                            Locale.US,
                            "%-15s %5s %7s %12s\n",
                            "IP", "丢包", "延迟", "下载速度"
                        ))
                        append("────────────────────────────────────────\n")

                        downloadCandidates.mapNotNull { scanResult ->
                            resultByIp[scanResult.ip]?.let { downloadResult ->
                                scanResult to downloadResult
                            }
                        }.sortedByDescending { it.second.downloadSpeedMbps }
                            .forEach { (scanResult, downloadResult) ->
                                append(String.format(
                                    Locale.US,
                                    "%-15s %4.0f%% %6s ms %10.2f MB/s\n",
                                    scanResult.ip,
                                    scanResult.lossRate * 100,
                                    scanResult.latencyMs?.toString() ?: "-",
                                    downloadResult.downloadSpeedMbps
                                ))
                            }

                        if (downloadResults.isEmpty()) {
                            append("\n没有成功的下载测速结果。")
                        }
                    }

                    runOnUiThread {
                        downloadValue.text = downloadResults.size.toString()
                        statusText.text = "测速完成 · 已按下载速度排序"
                        resultText.text = output
                        startButton.isEnabled = true
                        themeButton.isEnabled = true
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        statusText.text = "测速失败"
                        resultText.text = e.message ?: e.javaClass.simpleName
                        startButton.isEnabled = true
                        themeButton.isEnabled = true
                    }
                }
            }
        }
    }

    private fun currentThemeMode(): Int {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_THEME, THEME_SYSTEM)
    }

    private fun themeLabel(mode: Int): String {
        return when (mode) {
            THEME_LIGHT -> getString(R.string.theme_light)
            THEME_DARK -> getString(R.string.theme_dark)
            else -> getString(R.string.theme_system)
        }
    }

    private fun applySavedTheme() {
        when (currentThemeMode()) {
            THEME_LIGHT -> setTheme(R.style.Theme_BoxIP_Light)
            THEME_DARK -> setTheme(R.style.Theme_BoxIP_Dark)
            else -> {
                val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                setTheme(
                    if (night == Configuration.UI_MODE_NIGHT_YES) {
                        R.style.Theme_BoxIP_Dark
                    } else {
                        R.style.Theme_BoxIP_Light
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
