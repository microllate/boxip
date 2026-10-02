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
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Spinner
import android.widget.ArrayAdapter
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    companion object {
        private const val PREFS = "boxip_ui"
        private const val KEY_THEME = "theme"
        private const val THEME_SYSTEM = 0
        private const val THEME_LIGHT = 1
        private const val THEME_DARK = 2
        private const val KEY_REGION = "region"
        private const val KEY_RESULTS_SNAPSHOT = "snapshot"
        private const val KEY_HISTORY = "history"
    private val retestingHistoryIps = mutableSetOf<String>()
    }

    private data class RegionOption(
        val label: String,
        val pops: Set<String>,
        val candidateCount: Int
    )

    private val regionOptions = listOf(
        RegionOption("自动", emptySet(), 30),
        RegionOption("香港 HKG", setOf("HKG"), 100),
        RegionOption("日本 JP", setOf("NRT", "KIX", "FUK", "OKA"), 100),
        RegionOption("新加坡 SIN", setOf("SIN"), 100),
        RegionOption(
            "美国 US",
            setOf(
                "ATL", "AUS", "BNA", "BOS", "BUF", "CLT", "CLE", "CMH", "CVG",
                "DAL", "DFW", "DEN", "DTW", "EWR", "IAD", "IAH", "IND", "JAX",
                "LAS", "LAX", "MCI", "MCO", "MEM", "MIA", "MSP", "MSY", "OAK",
                "OMA", "ORD", "PDX", "PHL", "PHX", "PIT", "RDU", "RIC", "SAN",
                "SAT", "SEA", "SFO", "SJC", "SLC", "SMF", "STL", "TPA"
            ),
            100
        )
    )

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var scanStopRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applySavedTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Start BoxIP's loopback-only DNS server for the MVP.
        // It only listens on 127.0.0.1:1053 and does not affect system DNS.
        BoxIpDnsServer.start(this)

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
        val regionSpinner = findViewById<Spinner>(R.id.regionSpinner)
        val rangesValue = findViewById<TextView>(R.id.rangesValue)
        val candidatesValue = findViewById<TextView>(R.id.candidatesValue)
        val tcpValue = findViewById<TextView>(R.id.tcpValue)
        val downloadValue = findViewById<TextView>(R.id.downloadValue)
        val stageProgress = findViewById<ProgressBar>(R.id.stageProgress)
        val stopScanButton = findViewById<Button>(R.id.stopScanButton)
        val connectivityManager = getSystemService(ConnectivityManager::class.java)

        themeButton.text = themeLabel(currentThemeMode())

        val regionAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            regionOptions.map { it.label }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        regionSpinner.adapter = regionAdapter
        regionSpinner.setSelection(
            getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_REGION, 0)
                .coerceIn(0, regionOptions.lastIndex)
        )
        regionSpinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_REGION, position)
                    .apply()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        })

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

        stopScanButton.setOnClickListener {
            scanStopRequested = true
            it.isEnabled = false
            statusText.text = "正在停止测速…"
        }

        startButton.setOnClickListener {
            scanStopRequested = false
            startButton.isEnabled = false
            themeButton.isEnabled = false
            regionSpinner.isEnabled = false
            statusText.text = "正在获取 Cloudflare IPv4 网段…"
            resultText.visibility = View.VISIBLE
            resultText.text = "准备测速…"
            resultTable.visibility = View.GONE
            resultTable.removeAllViews()
            rangesValue.text = "—"
            candidatesValue.text = "—"
            tcpValue.text = "—"
            downloadValue.text = "—"
            stageProgress.visibility = View.GONE
            stageProgress.progress = 0
            stopScanButton.visibility = View.VISIBLE
            stopScanButton.isEnabled = true

            executor.execute {
                try {
                    val scanStartMs = System.currentTimeMillis()
                    val scanLog = StringBuilder()

                    fun appendScanLog(message: String) {
                        val elapsed = (System.currentTimeMillis() - scanStartMs) / 1000.0
                        runOnUiThread {
                            scanLog.append(String.format(Locale.US, "[%6.1fs] %s\n", elapsed, message))
                            resultText.visibility = View.VISIBLE
                            resultText.text = scanLog.toString()
                        }
                    }

                    appendScanLog("开始测速")
                    val selectedRegion = regionOptions[
                getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY_REGION, 0)
                    .coerceIn(0, regionOptions.lastIndex)
            ]

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

                    val results = mutableListOf<CfstScanResult>()
                    val candidates = mutableListOf<String>()
                    var vlessResults: List<VlessWsResult> = emptyList()
                    var downloadCandidates: List<CfstScanResult> = emptyList()
                    var downloadResults: List<CfstDownloadResult> = emptyList()
                    var regionResults: List<CfstDownloadResult> = emptyList()
                    var displayedResults: List<DownloadDisplayResult> = emptyList()
                    var realNodeCandidates: List<CfstDownloadResult> = emptyList()
                    var realNodeResults: List<CfstDownloadResult> = emptyList()
                    var selectedIp: String? = null
                    var finalAvailableCount = 0

                    val physicalInterface =
                        connectivityManager.getLinkProperties(physicalNetwork)?.interfaceName

                    if (selectedRegion.pops.isEmpty()) {
                        val testedIps = linkedSetOf<String>()
                        var cycleIndex = 0

                        while (true) {
                            cycleIndex++

                            val batch = sampler.sampleRandomBatch(ranges.ipv4, 300)
                                .filterNot(testedIps::contains)

                            if (batch.isEmpty()) {
                                throw IllegalStateException(
                                    "Cloudflare IP 地址池已耗尽，无法继续寻找可用入口"
                                )
                            }

                            testedIps += batch
                            candidates += batch

                            runOnUiThread {
                                rangesValue.text = ranges.ipv4.size.toString()
                                candidatesValue.text = testedIps.size.toString()
                                statusText.text = "第一阶段 · TCP ≤ 200 ms 筛选"
                                resultText.text = "随机测试 300 个 IP，寻找 TCP ≤ 200 ms 的入口…"
                            }

                            appendScanLog(
                                "第 ${cycleIndex} 轮 · 第一阶段开始 · 随机 300 IP · TCP ≤ 200 ms"
                            )

                            val batchResults = CfstScanner(
                                network = physicalNetwork,
                                pingTimes = 4,
                                timeoutMs = 1000,
                                concurrency = 20
                            ).scan(batch)

                            results += batchResults

                            val tcpCandidates = batchResults
                                .filter {
                                    it.received > 0 &&
                                        (it.latencyMs ?: Long.MAX_VALUE) <= 200L
                                }
                                .distinctBy { it.ip }

                            appendScanLog(
                                "第一阶段完成 · 本批 TCP ≤ 200 ms：${tcpCandidates.size} 个"
                            )

                            if (tcpCandidates.isEmpty()) {
                                appendScanLog("第一阶段结果为空 · 重新随机 300 个 IP")
                                continue
                            }

                            runOnUiThread {
                                statusText.text = "第二阶段 · 真实 VLESS + WS 验证"
                            }
                            appendScanLog(
                                "第二阶段开始 · 原生 TLS → WS · ${tcpCandidates.size} 个入口"
                            )

                            val currentVlessResults = VlessWsScanner(
                                network = physicalNetwork,
                                timeoutMs = 8_000,
                                concurrency = 4
                            ).scan(
                                ips = tcpCandidates.map { it.ip },
                                host = "life.mozzarella.top",
                                path = "",
                                interfaceName = physicalInterface
                            ) { result ->
                                appendScanLog(
                                    "VLESS ${result.ip} · " +
                                        if (result.success) {
                                            "成功 · ${result.latencyMs ?: "-"} ms · ${result.stage}"
                                        } else {
                                            "失败 · ${result.stage} · ${result.error ?: "未知错误"}"
                                        }
                                )
                            }

                            val currentVlessByIp = currentVlessResults.associateBy { it.ip }
                            val verifiedCandidates = tcpCandidates.filter {
                                currentVlessByIp[it.ip]?.success == true
                            }

                            appendScanLog(
                                "第二阶段完成 · VLESS + WS 成功 ${verifiedCandidates.size} / ${tcpCandidates.size}"
                            )

                            if (verifiedCandidates.isEmpty()) {
                                appendScanLog("第二阶段结果为空 · 回到第一阶段重新随机 300 个 IP")
                                continue
                            }

                            runOnUiThread {
                                tcpValue.text = verifiedCandidates.size.toString()
                                statusText.text = "第三阶段 · Cloudflare 入口质量"
                                stageProgress.visibility = View.VISIBLE
                                stageProgress.progress = 0
                            }

                            appendScanLog(
                                "第三阶段开始 · TCP / TLS / TTFB / 30 秒稳定性 · ${verifiedCandidates.size} 个 IP"
                            )

                            val currentDownloadResults = CfstDownloader(
                                network = physicalNetwork,
                                observationMs = 30_000,
                                probeIntervalMs = 5_000,
                                connectTimeoutMs = 3_000,
                                probeTimeoutMs = 3_000,
                                concurrency = 3
                            ).download(verifiedCandidates.map { it.ip }) { completed, total ->
                                val percent =
                                    (completed * 100 / total.coerceAtLeast(1)).coerceIn(0, 100)
                                runOnUiThread {
                                    stageProgress.progress = percent
                                }
                            }

                            val currentQualityResults = CfstQualityScorer()
                                .rank(currentDownloadResults.filter { it.minTcpConnectMs <= 200L })

                            appendScanLog(
                                "第三阶段完成 · 最终质量结果 ${currentQualityResults.size} 个"
                            )

                            if (currentQualityResults.isEmpty()) {
                                appendScanLog("第三阶段结果为空 · 回到第一阶段重新开始")
                                runOnUiThread { stageProgress.progress = 0 }
                                continue
                            }

                            vlessResults = currentVlessResults
                            finalAvailableCount = currentQualityResults.size
                            downloadCandidates = verifiedCandidates
                            downloadResults = currentDownloadResults
                            regionResults = currentDownloadResults

                            val qualityByIp = currentQualityResults.associateBy { it.result.ip }

                            realNodeCandidates = currentQualityResults
                                .take(10)
                                .map { it.result }

                            realNodeResults = realNodeCandidates.mapNotNull { candidate ->
                                regionResults.firstOrNull { it.ip == candidate.ip }
                            }

                            selectedIp = currentQualityResults
                                .firstOrNull()
                                ?.result
                                ?.ip
                                ?: getPersistedSelectedIp()

                            displayedResults = downloadCandidates.map { scanResult ->
                                DownloadDisplayResult(
                                    scanResult,
                                    regionResults.firstOrNull { it.ip == scanResult.ip }
                                )
                            }.sortedWith(
                                compareBy<DownloadDisplayResult> { it.downloadResult == null }
                                    .thenByDescending {
                                        it.downloadResult?.let { qualityByIp[it.ip]?.totalScore } ?: -1.0
                                    }
                                    .thenBy { it.downloadResult?.ttfbMs ?: Long.MAX_VALUE }
                                    .thenBy { it.downloadResult?.tlsHandshakeMs ?: Long.MAX_VALUE }
                                    .thenBy { it.downloadResult?.tcpConnectMs ?: Long.MAX_VALUE }
                            )

                            sampler.record(
                                downloadCandidates.map { scanResult ->
                                    val downloadResult =
                                        currentDownloadResults.firstOrNull { it.ip == scanResult.ip }
                                    CfstLearningObservation(
                                        ip = scanResult.ip,
                                        downloadSpeedMbps = downloadResult?.downloadSpeedMbps ?: 0.0,
                                        latencyMs = scanResult.latencyMs,
                                        success = downloadResult != null
                                    )
                                }
                            )

                            runOnUiThread { stageProgress.progress = 100 }
                            break
                        }
                    } else {
                        // Region mode keeps its existing discovery behavior.
                        val sampled = sampler.sample(ranges.ipv4)
                        candidates += sampled

                        runOnUiThread {
                            rangesValue.text = ranges.ipv4.size.toString()
                            candidatesValue.text = candidates.size.toString()
                            statusText.text = "第一阶段 · 自适应 TCPing 443"
                            resultText.text = "历史优选 + 随机探索，正在测试 ${candidates.size} 个候选 IP…"
                        }

                        appendScanLog("第一阶段开始 · TCPing 443 · ${candidates.size} 个候选 IP")

                        results += CfstScanner(
                            network = physicalNetwork,
                            pingTimes = 4,
                            timeoutMs = 1000,
                            concurrency = 20
                        ).scan(sampled)

                        appendScanLog(
                            "第一阶段完成 · TCP 可用 ${results.count { it.received > 0 }} / ${results.size}"
                        )

                    }

                    if (scanStopRequested) {
                        runOnUiThread {
                            statusText.text = "测速已手动停止"
                            resultText.text = "本次测速已停止"
                            startButton.isEnabled = true
                            themeButton.isEnabled = true
                            regionSpinner.isEnabled = true
                            stopScanButton.visibility = View.GONE
                        }
                        return@execute
                    }

                    if (selectedIp != null) {
                        BoxIpDnsServer.setCurrentIp(selectedIp)
                    }

                    runOnUiThread {
                        downloadValue.text = if (selectedRegion.pops.isEmpty()) {
                            finalAvailableCount.toString()
                        } else {
                            regionResults.size.toString()
                        }
                        statusText.text = "测速完成 · 请确认查看结果"

                        saveLastResults(
                            ranges.ipv4.size,
                            candidates.size,
                            results.size,
                            if (selectedRegion.pops.isEmpty()) {
                                finalAvailableCount
                            } else {
                                downloadResults.size
                            },
                            displayedResults,
                            realNodeCandidates,
                            realNodeResults,
                            selectedIp,
                            vlessResults
                        )

                        resultTable.removeAllViews()
                        resultTable.visibility = View.VISIBLE

                        val confirmButton = Button(this@MainActivity).apply {
                            text = "确定，查看测速结果"
                            textSize = 14f
                            minHeight = 0
                            minimumHeight = 0
                            setBackgroundResource(R.drawable.bg_primary_button)
                            setTextColor(getThemeColor(R.attr.boxOnAccent))
                            setOnClickListener {
                                resultText.visibility = View.GONE
                                resultTable.removeAllViews()
                                resultTable.visibility = View.VISIBLE
                                statusText.text = if (selectedRegion.pops.isEmpty()) {
                                    "测速完成 · 按入口连接质量排序"
                                } else {
                                    "测速完成 · ${selectedRegion.label}"
                                }
                                renderRealNodeSection(
                                    resultTable,
                                    realNodeCandidates,
                                    realNodeResults,
                                    selectedIp,
                                    vlessResults
                                )
                                renderHistorySection(resultTable, selectedIp, clearFirst = false)

                                startButton.isEnabled = true
                                themeButton.isEnabled = true
                                regionSpinner.isEnabled = true
                                stopScanButton.visibility = View.GONE
                            }
                        }

                        resultTable.addView(
                            confirmButton,
                            LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                dp(48)
                            ).apply {
                                setMargins(dp(4), dp(8), dp(4), dp(8))
                            }
                        )
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        statusText.text = "测速失败"
                        resultText.text = e.message ?: e.javaClass.simpleName
                        startButton.isEnabled = true
                        themeButton.isEnabled = true
                        regionSpinner.isEnabled = true
                        stopScanButton.visibility = View.GONE
                    }
                }
            }
        }
    }

    private data class DownloadDisplayResult(
        val scanResult: CfstScanResult,
        val downloadResult: CfstDownloadResult?
    )

    private fun renderHistorySection(
        resultTable: LinearLayout,
        selectedIp: String?,
        clearFirst: Boolean = true
    ) {
        val scrollView = (resultTable.parent?.parent as? android.widget.ScrollView)
        val savedScrollY = scrollView?.scrollY ?: 0
        if (clearFirst) resultTable.removeAllViews()
        resultTable.visibility = View.VISIBLE

        val prefs = getSharedPreferences("boxip_results", Context.MODE_PRIVATE)
        val history = parseHistory(prefs.getString(KEY_HISTORY, null))
            .sortedWith(
                compareBy<HistoryResult> { it.ip != selectedIp }
                    .thenBy { it.vlessLatencyMs.takeIf { value -> value >= 0L } ?: Long.MAX_VALUE }
                    .thenByDescending { it.testedAt }
            )

        resultTable.addView(TextView(this).apply {
            text = "历史可用节点"
            setTextColor(getThemeColor(R.attr.boxTextPrimary))
            textSize = 13f
            setPadding(dp(4), dp(18), dp(4), dp(8))
        })

        if (history.isEmpty()) {
            resultTable.addView(TextView(this).apply {
                text = "暂无通过真实 VLESS + WS 验证的节点"
                setTextColor(getThemeColor(R.attr.boxTextSecondary))
                textSize = 12f
                setPadding(dp(4), dp(8), dp(4), dp(12))
            })
            return
        }

        val metricLabels = listOf("VLESS", "TCP", "TLS", "TTFB", "速度", "区域")

        history.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                tag = "history_row:${item.ip}"
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }

            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val selector = TextView(this).apply {
                tag = "history_selector:${item.ip}"
                contentDescription = if (item.ip == selectedIp) "当前使用 ${item.ip}" else "选择 ${item.ip}"
                background = createSelectorDrawable(item.ip == selectedIp)
                isClickable = true
                isFocusable = true
                setOnClickListener { selectIp(item.ip, resultTable) }
            }
            top.addView(selector, LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                marginStart = dp(2)
                marginEnd = dp(6)
            })

            val ipText = TextView(this).apply {
                text = item.ip
                setTextColor(getThemeColor(R.attr.boxTextSecondary))
                textSize = 12f
                includeFontPadding = false
                maxLines = 1
                setOnClickListener { selectIp(item.ip, resultTable) }
            }
            top.addView(ipText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            val retestSpinner = RetestSpinnerView(this).apply {
                tag = "retest_spinner:${item.ip}"
                visibility = if (retestingHistoryIps.contains(item.ip)) View.VISIBLE else View.GONE
            }
            top.addView(retestSpinner, LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                marginStart = dp(6)
                marginEnd = dp(8)
            })

            val testButton = Button(this).apply {
                tag = "retest_button:${item.ip}"
                text = "重测"
                textSize = 11f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(0, 0, 0, 0)
                setBackgroundResource(if (retestingHistoryIps.contains(item.ip)) R.drawable.bg_surface_alt else R.drawable.bg_primary_button)
                setTextColor(getThemeColor(if (retestingHistoryIps.contains(item.ip)) R.attr.boxTextPrimary else R.attr.boxOnAccent))
                isEnabled = !retestingHistoryIps.contains(item.ip)
                setOnClickListener { retestHistoryIp(item.ip, resultTable) }
            }
            top.addView(testButton, LinearLayout.LayoutParams(dp(72), dp(28)).apply {
                marginEnd = dp(8)
            })

            val deleteButton = Button(this).apply {
                text = "删除"
                textSize = 11f
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(0, 0, 0, 0)
                setBackgroundResource(R.drawable.bg_primary_button)
                setTextColor(getThemeColor(R.attr.boxOnAccent))
                setOnClickListener { deleteHistoryIp(item.ip, resultTable) }
            }
            top.addView(deleteButton, LinearLayout.LayoutParams(dp(72), dp(28)))

            row.addView(top)

            val metricsHeader = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            metricLabels.forEach { label ->
                metricsHeader.addView(TextView(this).apply {
                    text = label
                    setTextColor(getThemeColor(R.attr.boxTextPrimary))
                    textSize = 10.5f
                    includeFontPadding = false
                    maxLines = 1
                }, LinearLayout.LayoutParams(0, dp(22), 1f))
            }
            row.addView(
                metricsHeader,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(22)
                ).apply {
                    topMargin = dp(6)
                }
            )

            val metricsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                tag = "history_metrics:${item.ip}"
            }

            val metricValues = listOf(
                if (item.vlessLatencyMs >= 0) "${item.vlessLatencyMs} ms" else "-",
                if (item.tcpMs >= 0) "${item.tcpMs}" else "-",
                if (item.tlsMs >= 0) "${item.tlsMs}" else "-",
                if (item.ttfbMs >= 0) "${item.ttfbMs}" else "-",
                if (item.speed > 0.0) String.format(Locale.US, "%.1f MB/s", item.speed) else "-",
                item.pop ?: "-"
            )
            metricValues.forEach { value ->
                metricsRow.addView(TextView(this).apply {
                    text = value
                    setTextColor(getThemeColor(R.attr.boxTextSecondary))
                    textSize = 10.5f
                    includeFontPadding = false
                    maxLines = 1
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                }, LinearLayout.LayoutParams(0, dp(28), 1f))
            }
            row.addView(metricsRow)

            val divider = View(this).apply {
                setBackgroundColor(getThemeColor(R.attr.boxDivider))
            }
            row.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))
            resultTable.addView(row)
        }

        scrollView?.post { scrollView.scrollTo(0, savedScrollY) }
    }

    private fun renderRealNodeSection(
        resultTable: LinearLayout,
        realNodeCandidates: List<CfstDownloadResult>,
        realNodeResults: List<CfstDownloadResult>,
        selectedIp: String?,
        vlessResults: List<VlessWsResult>
    ) {
        val section = TextView(this).apply {
            text = "真实节点域名验证 · life.mozzarella.top"
            setTextColor(getThemeColor(R.attr.boxTextPrimary))
            textSize = 13f
            setPadding(dp(4), dp(18), dp(4), dp(8))
        }
        resultTable.addView(section)

        val realNodeByIp = realNodeResults.associateBy { it.ip }
        val vlessByIp = vlessResults.associateBy { it.ip }

        // Real VLESS+WS is the final gate. Successful entries come first and
        // are ordered by the measured end-to-end latency. Failed entries follow
        // and retain the second-stage quality ordering as a tie-breaker.
        val sortedRealNodeCandidates = realNodeCandidates.sortedWith(
            compareBy<CfstDownloadResult> { vlessByIp[it.ip]?.success != true }
                .thenBy {
                    if (vlessByIp[it.ip]?.success == true) {
                        vlessByIp[it.ip]?.latencyMs ?: Long.MAX_VALUE
                    } else {
                        Long.MAX_VALUE
                    }
                }
                .thenByDescending { realNodeByIp[it.ip]?.stabilityPercent ?: 0.0 }
                .thenBy { realNodeByIp[it.ip]?.tlsHandshakeMs ?: Long.MAX_VALUE }
                .thenBy { realNodeByIp[it.ip]?.ttfbMs ?: Long.MAX_VALUE }
                .thenBy { realNodeByIp[it.ip]?.tcpConnectMs ?: Long.MAX_VALUE }
        )

        val realNodeRows = sortedRealNodeCandidates.map { result ->
            val verified = realNodeByIp[result.ip]
            listOf(
                result.ip,
                verified?.let { "${it.tcpConnectMs}" } ?: "失败",
                verified?.let { "${it.tlsHandshakeMs}" } ?: "-",
                verified?.let { "${it.ttfbMs}" } ?: "-",
                verified?.let { String.format(Locale.US, "%.1f MB/s", it.downloadSpeedMbps) } ?: "-",
                verified?.pop ?: "-"
            )
        }
        val realNodeWidths = contentColumnWidths(realNodeRows)
        resultTable.addView(
            createResultRow(
                "IP", "TCP", "TLS", "TTFB", "速度", "区域",
                header = true,
                columnWidths = realNodeWidths,
                showSelector = true
            )
        )

        sortedRealNodeCandidates.forEach { result ->
            val verified = realNodeByIp[result.ip]
            resultTable.addView(
                createResultRow(
                    result.ip,
                    verified?.let { "${it.tcpConnectMs}" } ?: "失败",
                    verified?.let { "${it.tlsHandshakeMs}" } ?: "-",
                    verified?.let { "${it.ttfbMs}" } ?: "-",
                    verified?.let { String.format(Locale.US, "%.1f MB/s", it.downloadSpeedMbps) } ?: "-",
                    verified?.pop ?: "-",
                    header = false,
                    columnWidths = realNodeWidths,
                    selectedIp = selectedIp,
                    showSelector = true,
                    onSelect = if (vlessByIp[result.ip]?.success == true) {
                        { ip -> selectIp(ip, resultTable) }
                    } else {
                        null
                    },
                    failedSelector = vlessByIp[result.ip]?.success != true
                )
            )
        }

    }
    private fun saveLastResults(
        ranges: Int,
        candidates: Int,
        tcp: Int,
        downloads: Int,
        results: List<DownloadDisplayResult>,
        realNodeCandidates: List<CfstDownloadResult>,
        realNodeResults: List<CfstDownloadResult>,
        selectedIp: String?,
        vlessResults: List<VlessWsResult>
    ) {
        val prefs = getSharedPreferences("boxip_results", Context.MODE_PRIVATE)
        val existing = parseHistory(prefs.getString(KEY_HISTORY, null)).toMutableList()
        val metricsByIp = realNodeResults.associateBy { it.ip }

        // History must come from the final third-stage quality results,
        // not from every IP that passed the second-stage VLESS + WS gate.
        // Only keep entries that also passed the real VLESS + WS verification.
        val vlessByIp = vlessResults.associateBy { it.ip }
        realNodeCandidates.forEach { finalResult ->
            val verified = vlessByIp[finalResult.ip]
            if (verified?.success != true) return@forEach

            val metrics = metricsByIp[finalResult.ip]
            val item = HistoryResult(
                ip = finalResult.ip,
                tcpMs = metrics?.tcpConnectMs ?: finalResult.tcpConnectMs,
                tlsMs = metrics?.tlsHandshakeMs ?: finalResult.tlsHandshakeMs,
                ttfbMs = metrics?.ttfbMs ?: finalResult.ttfbMs,
                speed = metrics?.downloadSpeedMbps ?: finalResult.downloadSpeedMbps,
                pop = metrics?.pop ?: finalResult.pop,
                vlessLatencyMs = verified.latencyMs ?: -1L,
                testedAt = System.currentTimeMillis()
            )
            existing.removeAll { it.ip == item.ip }
            existing.add(item)
        }

        val historyJson = serializeHistory(existing)
        val realNodeCandidatesJson = serializeDownloadResults(realNodeCandidates)
        val realNodeResultsJson = serializeDownloadResults(realNodeResults)
        val vlessJson = JSONArray().apply {
            vlessResults.forEach { result ->
                put(JSONObject().apply {
                    put("ip", result.ip)
                    put("latencyMs", result.latencyMs ?: -1L)
                    put("success", result.success)
                    put("stage", result.stage)
                    put("error", result.error ?: "")
                })
            }
        }
        val snapshot = JSONObject().apply {
            put("version", 4)
            put("ranges", ranges)
            put("candidates", candidates)
            put("tcp", tcp)
            put("downloads", downloads)
            put("history", JSONArray(historyJson))
            put("realNodeCandidates", JSONArray(realNodeCandidatesJson))
            put("realNodeResults", JSONArray(realNodeResultsJson))
            put("vlessResults", vlessJson)
            put("selectedIp", selectedIp ?: "")
            put("savedAt", System.currentTimeMillis())
        }

        prefs.edit()
            .putString(KEY_RESULTS_SNAPSHOT, snapshot.toString())
            .putString(KEY_HISTORY, historyJson)
            .putString("realNodeCandidates", realNodeCandidatesJson)
            .putString("realNodeResults", realNodeResultsJson)
            .putString("vlessResults", vlessJson.toString())
            .putString("selectedIp", selectedIp ?: "")
            .putLong("savedAt", System.currentTimeMillis())
            .commit()
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
        val snapshotRaw = prefs.getString(KEY_RESULTS_SNAPSHOT, null)
        val snapshot = snapshotRaw?.let { runCatching { JSONObject(it) }.getOrNull() }

        rangesValue.text = (snapshot?.optInt("ranges", prefs.getInt("ranges", 0))
            ?: prefs.getInt("ranges", 0)).toString()
        candidatesValue.text = (snapshot?.optInt("candidates", prefs.getInt("candidates", 0))
            ?: prefs.getInt("candidates", 0)).toString()
        tcpValue.text = (snapshot?.optInt("tcp", prefs.getInt("tcp", 0))
            ?: prefs.getInt("tcp", 0)).toString()
        downloadValue.text = (snapshot?.optInt("downloads", prefs.getInt("downloads", 0))
            ?: prefs.getInt("downloads", 0)).toString()

        val selectedIp = snapshot?.optString("selectedIp", "")
            ?.takeIf { it.isNotEmpty() }
            ?: prefs.getString("selectedIp", null)?.takeIf { it.isNotEmpty() }

        if (selectedIp != null) {
            BoxIpDnsServer.setCurrentIp(selectedIp)
        }

        val history = parseHistory(prefs.getString(KEY_HISTORY, null))
        val candidatesRaw = snapshot?.optJSONArray("realNodeCandidates")?.toString()
            ?: prefs.getString("realNodeCandidates", null)
        val resultsRaw = snapshot?.optJSONArray("realNodeResults")?.toString()
            ?: prefs.getString("realNodeResults", null)
        val candidates = parseRestoredDownloadResults(candidatesRaw)
        val realNodeResults = parseRestoredDownloadResults(resultsRaw)
        val vlessRaw = snapshot?.optJSONArray("vlessResults")?.toString()
            ?: prefs.getString("vlessResults", null)
        val vlessResults = parseRestoredVlessResults(vlessRaw)

        if (history.isNotEmpty() || candidates.isNotEmpty() || vlessResults.isNotEmpty()) {
            statusText.text = if (history.isNotEmpty()) {
                "已恢复历史可用节点"
            } else {
                "已恢复最近一次测速结果"
            }
            resultText.visibility = View.GONE
        } else {
            statusText.text = "暂无历史可用节点"
            resultText.visibility = View.VISIBLE
            resultText.text = "暂无通过真实 VLESS + WS 验证的节点。"
        }

        if (candidates.isNotEmpty()) {
            renderRestoredRealNodeResults(
                resultTable,
                candidates,
                realNodeResults,
                selectedIp,
                vlessResults
            )
            renderHistorySection(resultTable, selectedIp, clearFirst = false)
        } else {
            renderHistorySection(resultTable, selectedIp, clearFirst = false)
        }
    }

    private fun serializeDownloadResults(results: List<CfstDownloadResult>): String {
        val array = JSONArray()
        results.forEach { result ->
            array.put(JSONObject().apply {
                put("ip", result.ip)
                put("tcpMs", result.tcpConnectMs)
                put("tlsMs", result.tlsHandshakeMs)
                put("ttfbMs", result.ttfbMs)
                put("stability", result.stabilityPercent)
                put("speed", result.downloadSpeedMbps)
                put("durationMs", result.durationMs)
                put("pop", result.pop ?: "")
            })
        }
        return array.toString()
    }

    private fun parseRestoredDownloadResults(raw: String?): List<RestoredDownloadResult> {
        if (raw.isNullOrEmpty()) return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        RestoredDownloadResult(
                            ip = item.getString("ip"),
                            tcpMs = item.optLong("tcpMs", -1L),
                            tlsMs = item.optLong("tlsMs", -1L),
                            ttfbMs = item.optLong("ttfbMs", -1L),
                            stability = item.optDouble("stability", 0.0),
                            speed = item.optDouble("speed", 0.0),
                            durationMs = item.optLong("durationMs", -1L),
                            pop = item.optString("pop", "").ifEmpty { null }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun renderRestoredRealNodeResults(
        resultTable: LinearLayout,
        candidates: List<RestoredDownloadResult>,
        results: List<RestoredDownloadResult>,
        selectedIp: String? = null,
        vlessResults: List<RestoredVlessResult> = emptyList()
    ) {
        val section = TextView(this).apply {
            text = "真实节点域名验证 · life.mozzarella.top"
            setTextColor(getThemeColor(R.attr.boxTextPrimary))
            textSize = 13f
            setPadding(dp(4), dp(18), dp(4), dp(8))
        }
        resultTable.addView(section)

        val resultByIp = results.associateBy { it.ip }
        val vlessByIp = vlessResults.associateBy { it.ip }

        val sortedCandidates = candidates.sortedWith(
            compareBy<RestoredDownloadResult> { vlessByIp[it.ip]?.success != true }
                .thenBy {
                    if (vlessByIp[it.ip]?.success == true) {
                        vlessByIp[it.ip]?.latencyMs ?: Long.MAX_VALUE
                    } else {
                        Long.MAX_VALUE
                    }
                }
                .thenByDescending { resultByIp[it.ip]?.stability ?: 0.0 }
                .thenBy { resultByIp[it.ip]?.tlsMs ?: Long.MAX_VALUE }
                .thenBy { resultByIp[it.ip]?.ttfbMs ?: Long.MAX_VALUE }
                .thenBy { resultByIp[it.ip]?.tcpMs ?: Long.MAX_VALUE }
        )

        val rows = sortedCandidates.map { candidate ->
            val verified = resultByIp[candidate.ip]
            listOf(
                candidate.ip,
                verified?.let { "${it.tcpMs} ms" } ?: "失败",
                verified?.let { "${it.tlsMs} ms" } ?: "-",
                verified?.let { "${it.ttfbMs}" } ?: "-",
                verified?.let { String.format(Locale.US, "%.1f MB/s", it.speed) } ?: "-",
                verified?.pop ?: "-"
            )
        }

        val widths = contentColumnWidths(rows)
        resultTable.addView(
            createResultRow(
                "IP", "TCP", "TLS", "TTFB", "速度", "区域",
                header = true,
                columnWidths = widths,
                showSelector = true
            )
        )

        sortedCandidates.forEach { candidate ->
            val verified = resultByIp[candidate.ip]
            resultTable.addView(
                createResultRow(
                    candidate.ip,
                    verified?.let { "${it.tcpMs} ms" } ?: "失败",
                    verified?.let { "${it.tlsMs} ms" } ?: "-",
                    verified?.let { "${it.ttfbMs}" } ?: "-",
                    verified?.let { String.format(Locale.US, "%.1f MB/s", it.speed) } ?: "-",
                    verified?.pop ?: "-",
                    header = false,
                    columnWidths = widths,
                    selectedIp = selectedIp,
                    showSelector = true,
                    onSelect = if (vlessByIp[candidate.ip]?.success == true) {
                        { ip -> selectIp(ip, resultTable) }
                    } else {
                        null
                    },
                    failedSelector = vlessByIp[candidate.ip]?.success != true
                )
            )
        }

    }

    private fun parseRestoredVlessResults(raw: String?): List<RestoredVlessResult> {
        if (raw.isNullOrEmpty()) return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        RestoredVlessResult(
                            ip = item.optString("ip", ""),
                            latencyMs = item.optLong("latencyMs", -1L).takeIf { it >= 0L },
                            success = item.optBoolean("success", false),
                            stage = item.optString("stage", ""),
                            error = item.optString("error", "").ifEmpty { null }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private data class HistoryResult(
        val ip: String,
        val tcpMs: Long,
        val tlsMs: Long,
        val ttfbMs: Long,
        val speed: Double,
        val pop: String?,
        val vlessLatencyMs: Long,
        val testedAt: Long
    )

    private fun parseHistory(raw: String?): List<HistoryResult> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        HistoryResult(
                            ip = item.optString("ip", ""),
                            tcpMs = item.optLong("tcpMs", -1L),
                            tlsMs = item.optLong("tlsMs", -1L),
                            ttfbMs = item.optLong("ttfbMs", -1L),
                            speed = item.optDouble("speed", 0.0),
                            pop = item.optString("pop", "").ifEmpty { null },
                            vlessLatencyMs = item.optLong("vlessLatencyMs", -1L),
                            testedAt = item.optLong("testedAt", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun serializeHistory(items: List<HistoryResult>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("ip", item.ip)
                put("tcpMs", item.tcpMs)
                put("tlsMs", item.tlsMs)
                put("ttfbMs", item.ttfbMs)
                put("speed", item.speed)
                put("pop", item.pop ?: "")
                put("vlessLatencyMs", item.vlessLatencyMs)
                put("testedAt", item.testedAt)
            })
        }
        return array.toString()
    }

    private fun getPersistedSelectedIp(): String? {
        return getSharedPreferences("boxip_results", Context.MODE_PRIVATE)
            .getString("selectedIp", null)
            ?.takeIf { it.isNotEmpty() }
    }

    private fun updateHistoryItem(item: HistoryResult) {
        val prefs = getSharedPreferences("boxip_results", Context.MODE_PRIVATE)
        val history = parseHistory(prefs.getString(KEY_HISTORY, null)).toMutableList()
        val index = history.indexOfFirst { it.ip == item.ip }

        if (index >= 0) {
            val old = history[index]
            history[index] = HistoryResult(
                ip = item.ip,
                tcpMs = item.tcpMs.takeIf { it >= 0L } ?: old.tcpMs,
                tlsMs = item.tlsMs.takeIf { it >= 0L } ?: old.tlsMs,
                ttfbMs = item.ttfbMs.takeIf { it >= 0L } ?: old.ttfbMs,
                speed = item.speed.takeIf { it > 0.0 } ?: old.speed,
                pop = item.pop ?: old.pop,
                vlessLatencyMs = item.vlessLatencyMs.takeIf { it >= 0L } ?: old.vlessLatencyMs,
                testedAt = item.testedAt
            )
        } else {
            history.add(item)
        }

        prefs.edit().putString(KEY_HISTORY, serializeHistory(history)).commit()
    }

    private fun setHistoryRetestState(
        resultTable: LinearLayout,
        ip: String,
        testing: Boolean
    ) {
        val row = resultTable.findViewWithTag<View>("history_row:$ip") as? LinearLayout
            ?: return

        val spinner = row.findViewWithTag<View>("retest_spinner:$ip")
        val button = row.findViewWithTag<Button>("retest_button:$ip")

        spinner?.visibility = if (testing) View.VISIBLE else View.GONE
        button?.isEnabled = !testing
        button?.setBackgroundResource(
            if (testing) R.drawable.bg_surface_alt else R.drawable.bg_primary_button
        )
        button?.setTextColor(
            getThemeColor(
                if (testing) R.attr.boxTextPrimary else R.attr.boxOnAccent
            )
        )
    }

    private fun updateHistoryVlessMetric(
        resultTable: LinearLayout,
        ip: String,
        latencyMs: Long
    ) {
        val row = resultTable.findViewWithTag<View>("history_row:$ip") as? LinearLayout
            ?: return
        val metricsRow = row.findViewWithTag<LinearLayout>("history_metrics:" + ip) ?: return
        val vlessText = metricsRow.getChildAt(0) as? TextView ?: return
        vlessText.text = latencyMs.toString() + " ms"
    }

    private fun deleteHistoryIp(ip: String, resultTable: LinearLayout) {
        val prefs = getSharedPreferences("boxip_results", Context.MODE_PRIVATE)
        val history = parseHistory(prefs.getString(KEY_HISTORY, null))
            .filterNot { it.ip == ip }

        val selectedIp = getPersistedSelectedIp()
        val editor = prefs.edit().putString(KEY_HISTORY, serializeHistory(history))

        if (selectedIp == ip && resultTable.findViewWithTag<View>(ip) == null) {
            // If this IP is not part of the current scan results either, clear the active endpoint.
            // Otherwise deleting its history entry must not disturb the current scan selection.
            editor.remove("selectedIp")
            val snapshotRaw = prefs.getString(KEY_RESULTS_SNAPSHOT, null)
            if (!snapshotRaw.isNullOrEmpty()) {
                runCatching {
                    JSONObject(snapshotRaw).apply {
                        put("selectedIp", "")
                        put("savedAt", System.currentTimeMillis())
                    }.toString()
                }.onSuccess { editor.putString(KEY_RESULTS_SNAPSHOT, it) }
            }
            BoxIpDnsServer.setCurrentIp("")
        }

        editor.commit()

        // Remove only this history row. Keep the current scan results and their layout intact.
        resultTable.findViewWithTag<View>("history_row:$ip")?.let { row ->
            resultTable.removeView(row)
        }

        val remainingHistoryRows = (0 until resultTable.childCount)
            .map { resultTable.getChildAt(it) }
            .count { (it.tag as? String)?.startsWith("history_row:") == true }

        if (remainingHistoryRows == 0 && history.isNotEmpty()) {
            // No-op: this can only happen if the row was not currently rendered.
        } else if (remainingHistoryRows == 0) {
            resultTable.addView(TextView(this).apply {
                tag = "history_empty"
                text = "暂无通过真实 VLESS + WS 验证的节点"
                setTextColor(getThemeColor(R.attr.boxTextSecondary))
                textSize = 12f
                setPadding(dp(4), dp(8), dp(4), dp(12))
            })
        }
    }

    private fun retestHistoryIp(ip: String, resultTable: LinearLayout) {
        if (!retestingHistoryIps.add(ip)) return

        // Do not rebuild the result page. Only toggle the local spinner/button state.
        setHistoryRetestState(resultTable, ip, true)

        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val physicalNetwork = connectivityManager.allNetworks.firstOrNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities != null &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        if (physicalNetwork == null) {
            retestingHistoryIps.remove(ip)
            setHistoryRetestState(resultTable, ip, false)
            return
        }

        val physicalInterface = connectivityManager
            .getLinkProperties(physicalNetwork)
            ?.interfaceName

        executor.execute {
            val result = runCatching {
                var result: VlessWsResult? = null
                for (attemptIndex in 0 until 3) {
                    val attempt = VlessWsScanner(
                        network = physicalNetwork,
                        timeoutMs = 8_000,
                        concurrency = 1
                    ).scan(
                        ips = listOf(ip),
                        host = "life.mozzarella.top",
                        path = "",
                        interfaceName = physicalInterface
                    ).firstOrNull()
                    result = attempt
                    if (attempt?.success == true) break
                }
                result
            }.getOrNull()

            runOnUiThread {
                retestingHistoryIps.remove(ip)

                if (result?.success == true) {
                    val old = parseHistory(
                        getSharedPreferences("boxip_results", Context.MODE_PRIVATE)
                            .getString(KEY_HISTORY, null)
                    ).firstOrNull { it.ip == ip }

                    if (old != null) {
                        updateHistoryItem(
                            old.copy(
                                vlessLatencyMs = result.latencyMs ?: -1L,
                                testedAt = System.currentTimeMillis()
                            )
                        )
                        result.latencyMs?.let {
                            updateHistoryVlessMetric(resultTable, ip, it)
                        }
                        statusTextForHistory("单独验证成功：$ip · VLESS " + (result.latencyMs ?: "-") + " ms")
                    }
                } else {
                    statusTextForHistory("单独验证失败：$ip")
                }

                // Restore only this row's controls. The rest of the page is untouched.
                setHistoryRetestState(resultTable, ip, false)
            }
        }
    }

    private fun statusTextForHistory(message: String) {
        findViewById<TextView>(R.id.statusText).text = message
    }

    private data class RestoredDownloadResult(
        val ip: String,
        val tcpMs: Long,
        val tlsMs: Long,
        val ttfbMs: Long,
        val stability: Double,
        val speed: Double,
        val durationMs: Long,
        val pop: String?
    )

    private data class RestoredVlessResult(
        val ip: String,
        val latencyMs: Long?,
        val success: Boolean,
        val stage: String,
        val error: String?
    )

    private data class RestoredResult(
        val ip: String,
        val loss: Double,
        val latencyMs: Long,
        val success: Boolean,
        val tcpMs: Long,
        val tlsMs: Long,
        val ttfbMs: Long,
        val stability: Double,
        val speed: Double,
        val pop: String
    )

    private fun createResultRow(
        ip: String,
        loss: String,
        latency: String,
        speed: String,
        stability: String,
        pop: String,
        header: Boolean,
        columnWidths: IntArray,
        selectedIp: String? = null,
        showSelector: Boolean = false,
        onSelect: ((String) -> Unit)? = null,
        failedSelector: Boolean = false
    ): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        if (!header && showSelector && onSelect != null) {
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { onSelect(ip) }
        }

        val values = listOf(ip, loss, latency, speed, stability, pop)
        values.forEachIndexed { index, value ->
            if (index == 0 && !header && showSelector) {
                val ipCell = LinearLayout(this)
                ipCell.orientation = LinearLayout.HORIZONTAL
                ipCell.gravity = Gravity.CENTER_VERTICAL

                val selector = TextView(this).apply {
                    contentDescription = if (failedSelector) {
                        "VLESS + WS 验证失败 $ip"
                    } else if (ip == selectedIp) {
                        "当前使用 $ip"
                    } else {
                        "可用入口 $ip"
                    }
                    if (failedSelector) {
                        text = "×"
                        setTextColor(getThemeColor(R.attr.boxTextMuted))
                        textSize = 14f
                        gravity = Gravity.CENTER
                        isClickable = false
                        isFocusable = false
                    } else {
                        background = createSelectorDrawable(ip == selectedIp)
                        tag = ip
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { onSelect?.invoke(ip) }
                    }
                }

                ipCell.setOnClickListener { onSelect?.invoke(ip) }

                ipCell.addView(
                    selector,
                    LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                        marginStart = dp(2)
                        marginEnd = dp(6)
                    }
                )

                val ipText = TextView(this).apply {
                    text = value
                    setTextColor(getThemeColor(R.attr.boxTextSecondary))
                    textSize = 12f
                    gravity = Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                    maxLines = 1
                    isClickable = true
                    setOnClickListener { onSelect?.invoke(ip) }
                }

                ipCell.addView(
                    ipText,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )

                row.addView(
                    ipCell,
                    LinearLayout.LayoutParams(
                        columnWidths[index],
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
                return@forEachIndexed
            }

            val cell = TextView(this)
            cell.text = value
            cell.setTextColor(
                getThemeColor(
                    if (header) R.attr.boxTextPrimary else R.attr.boxTextSecondary
                )
            )
            cell.textSize = if (header) 13f else 12f
            // Left-align every column. With IP sized to its content and
            // the remaining columns sharing the available width equally,
            // each column starts at a predictable position without large
            // right-alignment gaps.
            cell.gravity = Gravity.CENTER_VERTICAL
            cell.setPadding(
                dp(4),
                dp(8),
                dp(2),
                dp(8)
            )
            cell.includeFontPadding = false
            cell.maxLines = 1

            row.addView(
                cell,
                LinearLayout.LayoutParams(
                    columnWidths[index],
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        return row
    }

    private fun createSelectorDrawable(selected: Boolean): Drawable {
        return IpSelectorDrawable(
            selected = selected,
            accentColor = Color.rgb(112, 181, 242),
            idleColor = Color.rgb(105, 115, 130)
        )
    }

    private class RetestSpinnerView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            color = Color.rgb(96, 165, 250)
        }
        private val rect = RectF()
        private var startTime = 0L

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            startTime = android.os.SystemClock.uptimeMillis()
            postInvalidateOnAnimation()
        }

        override fun onDetachedFromWindow() {
            removeCallbacks(null)
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = paint.strokeWidth / 2f + 1f
            rect.set(inset, inset, width - inset, height - inset)

            val elapsed = android.os.SystemClock.uptimeMillis() - startTime
            val rotation = (elapsed % 900L) * 360f / 900f

            canvas.drawArc(
                rect,
                rotation,
                270f,
                false,
                paint
            )
            postInvalidateOnAnimation()
        }
    }

    private class IpSelectorDrawable(
        private val selected: Boolean,
        private val accentColor: Int,
        private val idleColor: Int
    ) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isDither = true
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        override fun draw(canvas: Canvas) {
            val cx = bounds.exactCenterX()
            val cy = bounds.exactCenterY()
            val radius = minOf(bounds.width(), bounds.height()) * 0.30f

            if (selected) {
                // A restrained two-ring treatment: thin outer ring + compact core.
                // It reads more like a premium status control than a filled circle.
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = radius * 0.16f
                paint.color = accentColor
                paint.alpha = 235
                canvas.drawCircle(cx, cy, radius, paint)

                paint.style = Paint.Style.FILL
                paint.alpha = 255
                canvas.drawCircle(cx, cy, radius * 0.42f, paint)

                // Small highlight to give the selected state a subtle depth.
                paint.color = Color.WHITE
                paint.alpha = 70
                canvas.drawCircle(
                    cx - radius * 0.18f,
                    cy - radius * 0.18f,
                    radius * 0.11f,
                    paint
                )
            } else {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = radius * 0.12f
                paint.color = idleColor
                paint.alpha = 190
                canvas.drawCircle(cx, cy, radius, paint)
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    private fun selectIp(ip: String, resultTable: LinearLayout) {
        // History entries and current scan entries use the exact same selection path.
        // Changing either one immediately changes the active BoxIP endpoint.
        BoxIpDnsServer.setCurrentIp(ip)
        persistSelectedIp(ip)
        updateSelectionIndicators(resultTable, ip)

        resultTable.findViewWithTag<View>("history_row:$ip")?.let { row ->
            row.post { row.requestRectangleOnScreen(android.graphics.Rect(0, 0, row.width, row.height), false) }
        }
    }

    private fun updateSelectionIndicators(resultTable: LinearLayout, selectedIp: String) {
        for (index in 0 until resultTable.childCount) {
            val row = resultTable.getChildAt(index) as? LinearLayout ?: continue
            val ipCell = row.getChildAt(0) as? LinearLayout
            if (ipCell != null) {
                val selector = ipCell.getChildAt(0) as? TextView
                val ip = selector?.tag as? String
                if (selector != null && ip != null) {
                    selector.background = createSelectorDrawable(ip == selectedIp)
                    selector.contentDescription = if (ip == selectedIp) "当前使用 $ip" else "可用入口 $ip"
                }
            }

            val topRow = row.getChildAt(0) as? LinearLayout
            if (topRow != null) {
                for (childIndex in 0 until topRow.childCount) {
                    val child = topRow.getChildAt(childIndex)
                    val tag = child.tag as? String
                    if (child is TextView && tag?.startsWith("history_selector:") == true) {
                        val ip = tag.removePrefix("history_selector:")
                        child.background = createSelectorDrawable(ip == selectedIp)
                        child.contentDescription = if (ip == selectedIp) "当前使用 $ip" else "选择 $ip"
                    }
                }
            }
        }
    }

    private fun persistSelectedIp(ip: String) {
        val prefs = getSharedPreferences("boxip_results", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        val snapshotRaw = prefs.getString(KEY_RESULTS_SNAPSHOT, null)

        if (!snapshotRaw.isNullOrEmpty()) {
            runCatching {
                JSONObject(snapshotRaw).apply {
                    put("selectedIp", ip)
                    put("savedAt", System.currentTimeMillis())
                }.also {
                    editor.putString(KEY_RESULTS_SNAPSHOT, it.toString())
                }
            }
        }

        editor.putString("selectedIp", ip).commit()
    }

    /**
     * Give the IP column exactly the width needed by the longest IPv4 value,
     * including the selector dot. Split all remaining screen space evenly
     * across TCP / TLS / TTFB / stability / region.
     */
    private fun contentColumnWidths(rows: List<List<String>>): IntArray {
        val widths = IntArray(6)

        // Only the IP column is content-sized. The other five columns are
        // intentionally equal-width so the table stays visually balanced.
        val ipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sp(12f)
        }
        var maxIpWidth = ipPaint.measureText("111.111.111.111").toInt()

        rows.forEach { row ->
            if (row.isNotEmpty()) {
                maxIpWidth = maxOf(
                    maxIpWidth,
                    ipPaint.measureText(row[0]).toInt()
                )
            }
        }

        widths[0] = maxIpWidth + dp(14 + 2 + 6 + 4)

        // Result table uses 8dp horizontal ScrollView padding plus the root
        // side margins. Give every non-IP column the same share.
        val available = resources.displayMetrics.widthPixels - dp(56)
        val remaining = maxOf(0, available - widths[0])
        val each = remaining / 5
        var remainder = remaining % 5

        for (index in 1 until widths.size) {
            widths[index] = each
            if (remainder > 0) {
                widths[index]++
                remainder--
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