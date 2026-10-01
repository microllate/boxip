package com.microllate.boxip

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import org.json.JSONArray
import org.json.JSONObject
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.view.Gravity
import android.graphics.Paint
import android.widget.Button
import android.widget.LinearLayout
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
        val resultTable = findViewById<LinearLayout>(R.id.resultTable)
        val startButton = findViewById<Button>(R.id.startScanButton)
        val themeButton = findViewById<TextView>(R.id.themeButton)
        val rangesValue = findViewById<TextView>(R.id.rangesValue)
        val candidatesValue = findViewById<TextView>(R.id.candidatesValue)
        val tcpValue = findViewById<TextView>(R.id.tcpValue)
        val downloadValue = findViewById<TextView>(R.id.downloadValue)
        val connectivityManager = getSystemService(ConnectivityManager::class.java)

        themeButton.text = themeLabel(currentThemeMode())
        restoreLastResults(
            statusText,
            resultText,
            resultTable,
            rangesValue,
            candidatesValue,
            tcpValue,
            downloadValue
        )
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
            resultText.visibility = View.VISIBLE
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

                    val downloadCandidates = results.take(10)

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
                        resultText.visibility = if (successfulResults.isEmpty()) View.VISIBLE else View.GONE
                        resultText.text = "没有成功的下载测速结果。"

                        renderResults(resultTable, successfulResults)
                        saveLastResults(
                            ranges.ipv4.size,
                            candidates.size,
                            results.size,
                            downloadResults.size,
                            successfulResults
                        )

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

    private fun renderResults(
        resultTable: LinearLayout,
        successfulResults: List<Pair<CfstScanResult, CfstDownloadResult>>
    ) {
        resultTable.removeAllViews()
        resultTable.visibility = View.VISIBLE

        resultTable.addView(
            createResultRow("IP", "丢包", "延迟", "速度", "区域", header = true)
        )

        val divider = View(this)
        divider.setBackgroundColor(getThemeColor(R.attr.boxDivider))
        resultTable.addView(
            divider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            )
        )

        successfulResults.forEach { (scanResult, downloadResult) ->
            resultTable.addView(
                createResultRow(
                    scanResult.ip,
                    String.format(Locale.US, "%.0f%%", scanResult.lossRate * 100),
                    (scanResult.latencyMs?.toString() ?: "-") + " ms",
                    String.format(Locale.US, "%.2f MB/s", downloadResult.downloadSpeedMbps),
                    downloadResult.pop ?: "-",
                    header = false
                )
            )
        }
    }

    private fun saveLastResults(
        ranges: Int,
        candidates: Int,
        tcp: Int,
        downloads: Int,
        results: List<Pair<CfstScanResult, CfstDownloadResult>>
    ) {
        val array = JSONArray()
        results.forEach { (scanResult, downloadResult) ->
            array.put(JSONObject().apply {
                put("ip", scanResult.ip)
                put("loss", scanResult.lossRate)
                put("latency", scanResult.latencyMs ?: -1L)
                put("speed", downloadResult.downloadSpeedMbps)
                put("pop", downloadResult.pop ?: "")
            })
        }

        getSharedPreferences("boxip_results", Context.MODE_PRIVATE)
            .edit()
            .putInt("ranges", ranges)
            .putInt("candidates", candidates)
            .putInt("tcp", tcp)
            .putInt("downloads", downloads)
            .putString("results", array.toString())
            .putLong("savedAt", System.currentTimeMillis())
            .apply()
    }

    private fun restoreLastResults(
        statusText: TextView,
        resultText: TextView,
        resultTable: LinearLayout,
        rangesValue: TextView,
        candidatesValue: TextView,
        tcpValue: TextView,
        downloadValue: TextView
    ) {
        val prefs = getSharedPreferences("boxip_results", Context.MODE_PRIVATE)
        val raw = prefs.getString("results", null) ?: return

        try {
            val array = JSONArray(raw)
            val restored = mutableListOf<RestoredResult>()

            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                restored += RestoredResult(
                    ip = item.getString("ip"),
                    loss = item.optDouble("loss", 0.0),
                    latencyMs = item.optLong("latency", -1L),
                    speed = item.optDouble("speed", 0.0),
                    pop = item.optString("pop", "").ifEmpty { "-" }
                )
            }

            rangesValue.text = prefs.getInt("ranges", 0).toString()
            candidatesValue.text = prefs.getInt("candidates", 0).toString()
            tcpValue.text = prefs.getInt("tcp", 0).toString()
            downloadValue.text = prefs.getInt("downloads", restored.size).toString()

            statusText.text = "已恢复上次测速结果"
            resultText.visibility = if (restored.isEmpty()) View.VISIBLE else View.GONE
            resultText.text = "没有成功的下载测速结果。"

            resultTable.removeAllViews()
            resultTable.visibility = View.VISIBLE
            resultTable.addView(
                createResultRow("IP", "丢包", "延迟", "速度", "区域", header = true)
            )

            val divider = View(this)
            divider.setBackgroundColor(getThemeColor(R.attr.boxDivider))
            resultTable.addView(
                divider,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1)
                )
            )

            restored.forEach { item ->
                resultTable.addView(
                    createResultRow(
                        item.ip,
                        String.format(Locale.US, "%.0f%%", item.loss),
                        if (item.latencyMs >= 0) item.latencyMs.toString() + " ms" else "-",
                        String.format(Locale.US, "%.2f MB/s", item.speed),
                        item.pop,
                        header = false
                    )
                )
            }
        } catch (_: Exception) {
            // Ignore invalid old result data and keep the initial empty state.
        }
    }

    private data class RestoredResult(
        val ip: String,
        val loss: Double,
        val latencyMs: Long,
        val speed: Double,
        val pop: String
    )

    private fun createResultRow(
        ip: String,
        loss: String,
        latency: String,
        speed: String,
        pop: String,
        header: Boolean
    ): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val values = listOf(ip, loss, latency, speed, pop)
        val columnWeights = contentColumnWeights()

        values.forEachIndexed { index, value ->
            val cell = TextView(this)
            cell.text = value
            cell.setTextColor(
                getThemeColor(
                    if (header) R.attr.boxTextPrimary else R.attr.boxTextSecondary
                )
            )
            cell.textSize = if (header) 13f else 12f
            cell.gravity = Gravity.CENTER
            cell.setPadding(dp(4), dp(8), dp(4), dp(8))
            cell.includeFontPadding = false
            cell.maxLines = 1

            row.addView(
                cell,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    columnWeights[index]
                )
            )
        }

        return row
    }

    /**
     * Give each column a weight based on the widest text currently shown.
     * This keeps the columns aligned across rows while avoiding fixed 20% columns.
     */
    private fun contentColumnWeights(): FloatArray {
        val rows = mutableListOf(
            listOf("IP", "丢包", "延迟", "速度", "区域")
        )

        val prefs = getSharedPreferences("boxip_results", Context.MODE_PRIVATE)
        val raw = prefs.getString("results", null)
        if (!raw.isNullOrEmpty()) {
            try {
                val array = JSONArray(raw)
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    rows += listOf(
                        item.optString("ip", ""),
                        String.format(
                            Locale.US,
                            "%.0f%%",
                            item.optDouble("loss", 0.0) * 100
                        ),
                        if (item.optLong("latency", -1L) >= 0) {
                            item.optLong("latency", -1L).toString() + " ms"
                        } else {
                            "-"
                        },
                        String.format(
                            Locale.US,
                            "%.2f MB/s",
                            item.optDouble("speed", 0.0)
                        ),
                        item.optString("pop", "").ifEmpty { "-" }
                    )
                }
            } catch (_: Exception) {
            }
        }

        val widths = FloatArray(5)
        rows.forEach { row ->
            row.forEachIndexed { index, value ->
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = sp(if (index == 0 && value != "IP") 12f else 13f)
                }
                val width = paint.measureText(value) + dp(8)
                widths[index] = maxOf(widths[index], width)
            }
        }

        return widths
    }

    private fun sp(value: Float): Float {
        return value * resources.displayMetrics.scaledDensity
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
