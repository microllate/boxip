package com.microllate.boxip

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.view.Gravity
import android.widget.Button
import android.widget.TableLayout
import android.widget.TableRow
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

        val rootLayout = findViewById<View>(R.id.rootLayout)
        rootLayout.setOnApplyWindowInsetsListener { view, insets ->
            // Keep the app content below the status bar and above the navigation bar.
            // The 12dp base spacing preserves the intended visual margin.
            view.setPadding(
                view.paddingLeft,
                dp(12) + insets.systemWindowInsetTop,
                view.paddingRight,
                dp(12) + insets.systemWindowInsetBottom
            )
            insets
        }
        rootLayout.requestApplyInsets()

        val statusText = findViewById<TextView>(R.id.statusText)
        val resultText = findViewById<TextView>(R.id.resultText)
        val resultTable = findViewById<TableLayout>(R.id.resultTable)
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
            resultTable.visibility = View.GONE
            resultTable.removeAllViews()
            rangesValue.text = "—"
            candidatesValue.text = "—"
            tcpValue.text = "—"
            downloadValue.text = "—"

            executor.execute {
                try {
                    val ranges = CloudflareIpProvider().fetch()

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

                    val physicalCapabilities =
                        connectivityManager.getNetworkCapabilities(physicalNetwork)
                            ?: throw IllegalStateException("无法读取物理网络状态")

                    val networkKey = when {
                        physicalCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        physicalCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                        else -> "other"
                    }

                    val sampler = CfstSampler(
                        context = this@MainActivity,
                        networkKey = networkKey
                    )
                    val candidates = sampler.sample(ranges.ipv4)

                    runOnUiThread {
                        rangesValue.text = ranges.ipv4.size.toString()
                        candidatesValue.text = candidates.size.toString()
                        statusText.text = "第一阶段 · 自适应 TCPing 443"
                        resultText.text = "历史优选 + 随机探索，正在测试 ${candidates.size} 个候选 IP…"
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

                    sampler.record(
                        downloadCandidates.map { scanResult ->
                            val downloadResult = resultByIp[scanResult.ip]
                            CfstLearningObservation(
                                ip = scanResult.ip,
                                downloadSpeedMbps = downloadResult?.downloadSpeedMbps ?: 0.0,
                                latencyMs = scanResult.latencyMs,
                                success = downloadResult != null
                            )
                        }
                    )

                    val successfulResults = downloadCandidates.mapNotNull { scanResult ->
                        resultByIp[scanResult.ip]?.let { downloadResult ->
                            scanResult to downloadResult
                        }
                    }.sortedByDescending { it.second.downloadSpeedMbps }

                    runOnUiThread {
                        downloadValue.text = downloadResults.size.toString()
                        statusText.text = "测速完成 · 已按下载速度排序"
                        resultText.text = if (successfulResults.isEmpty()) {
                            "没有成功的下载测速结果。"
                        } else {
                            "结果已按下载速度排序"
                        }

                        resultTable.removeAllViews()
                        resultTable.visibility = View.VISIBLE

                        val header = TableRow(this@MainActivity)
                        addResultCell(header, "IP", true)
                        addResultCell(header, "丢包", true)
                        addResultCell(header, "延迟", true)
                        addResultCell(header, "速度", true)
                        resultTable.addView(header)

                        successfulResults.forEach { (scanResult, downloadResult) ->
                            val row = TableRow(this@MainActivity)
                            addResultCell(row, scanResult.ip, false)
                            addResultCell(
                                row,
                                String.format(Locale.US, "%.0f%%", scanResult.lossRate * 100),
                                false
                            )
                            addResultCell(
                                row,
                                "${scanResult.latencyMs?.toString() ?: "-"} ms",
                                false
                            )
                            addResultCell(
                                row,
                                String.format(Locale.US, "%.2f MB/s", downloadResult.downloadSpeedMbps),
                                false
                            )
                            resultTable.addView(row)
                        }

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

    private fun addResultCell(row: TableRow, text: String, header: Boolean) {
        val cell = TextView(this)
        cell.text = text
        cell.setTextColor(
            getThemeColor(if (header) R.attr.boxTextPrimary else R.attr.boxTextSecondary)
        )
        cell.textSize = if (header) 13f else 12f
        cell.gravity = Gravity.CENTER_VERTICAL
        cell.setPadding(dp(6), dp(8), dp(6), dp(8))
        cell.includeFontPadding = false
        cell.maxLines = 1
        row.addView(cell, TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun getThemeColor(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return if (typedValue.resourceId != 0) {
            getColor(typedValue.resourceId)
        } else {
            typedValue.data
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
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
