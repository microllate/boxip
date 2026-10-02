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

        startButton.setOnClickListener {
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

            executor.execute {
                try {
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

                    val historicalFastestIps = sampler.getHistoricalFastestIps()
                    val retainedResults = historicalFastestIps.mapNotNull { ip ->
                        results.firstOrNull { it.ip == ip }
                    }

                    // Automatic mode keeps a broader Top 30 pool for the second-stage quality analysis.
                    // Region mode first performs a short PoP discovery across
                    // a larger and more diverse pool. Anycast cannot force a
                    // specific PoP; we need to observe what this network reaches.
                    val initialCandidates = if (selectedRegion.pops.isEmpty()) {
                        buildList {
                            addAll(retainedResults.distinctBy { it.ip }.take(30))
                            for (result in results) {
                                if (size >= 30) break
                                if (none { it.ip == result.ip }) add(result)
                            }
                        }
                    } else {
                        results.shuffled().take(selectedRegion.candidateCount)
                    }

                    val downloadCandidates: List<CfstScanResult>
                    val discoveryResults: List<CfstDownloadResult>

                    if (selectedRegion.pops.isEmpty()) {
                        downloadCandidates = initialCandidates
                        runOnUiThread {
                            tcpValue.text = results.size.toString()
                            statusText.text = "第二阶段 · Cloudflare 入口质量"
                            resultText.text = "TCPing 完成，正在测试 ${downloadCandidates.size} 个 IP 的 TCP / TLS / TTFB / 30 秒稳定性…"
                        }

                        discoveryResults = CfstDownloader(
                            network = physicalNetwork,
                            observationMs = 30_000,
                            probeIntervalMs = 5_000,
                            connectTimeoutMs = 3_000,
                            concurrency = 3
                        ).download(downloadCandidates.map { it.ip })
                    } else {
                        runOnUiThread {
                            tcpValue.text = results.size.toString()
                            statusText.text = "第二阶段 · ${selectedRegion.label} PoP 探索"
                            resultText.text = "先用 ${initialCandidates.size} 个候选进行 5 秒轻量探测，寻找实际可达的 ${selectedRegion.label}…"
                        }

                        discoveryResults = CfstDownloader(
                            network = physicalNetwork,
                            observationMs = 5_000,
                            probeIntervalMs = 5_000,
                            connectTimeoutMs = 3_000,
                            probeTimeoutMs = 3_000,
                            concurrency = 8
                        ).download(initialCandidates.map { it.ip })

                        val matchingIps = discoveryResults
                            .filter { it.pop in selectedRegion.pops }
                            .sortedWith(
                                compareByDescending<CfstDownloadResult> { it.stabilityPercent }
                                    .thenBy { it.tlsHandshakeMs }
                                    .thenBy { it.ttfbMs }
                            )
                            .take(10)
                            .map { it.ip }
                            .toSet()

                        downloadCandidates = results.filter { it.ip in matchingIps }
                            .sortedBy { matchingIps.indexOf(it.ip) }

                        runOnUiThread {
                            statusText.text = "第二阶段 · ${selectedRegion.label} 入口质量"
                            resultText.text = if (downloadCandidates.isEmpty()) {
                                "本次探索未观察到 ${selectedRegion.label}。Cloudflare Anycast 无法强制指定 PoP，可换网络或稍后重试。"
                            } else {
                                "发现 ${downloadCandidates.size} 个 ${selectedRegion.label} 入口，正在进行 30 秒完整质量测试…"
                            }
                        }
                    }

                    val downloadResults = if (selectedRegion.pops.isEmpty()) {
                        discoveryResults
                    } else if (downloadCandidates.isEmpty()) {
                        emptyList()
                    } else {
                        CfstDownloader(
                            network = physicalNetwork,
                            observationMs = 30_000,
                            probeIntervalMs = 5_000,
                            connectTimeoutMs = 3_000,
                            probeTimeoutMs = 3_000,
                            concurrency = 3
                        ).download(downloadCandidates.map { it.ip })
                    }

                    val allResultByIp = downloadResults.associateBy { it.ip }
                    val regionResults = if (selectedRegion.pops.isEmpty()) {
                        downloadResults
                    } else {
                        downloadResults.filter { it.pop in selectedRegion.pops }
                    }
                    val resultByIp = regionResults.associateBy { it.ip }

                    // Stage 2 ranking is deliberately separated from CfstDownloader:
                    // the downloader only measures raw metrics, while this scorer
                    // turns TCP/TLS/TTFB/download/stability into one quality score.
                    val qualityResults = CfstQualityScorer().rank(regionResults)
                    val qualityByIp = qualityResults.associateBy { it.result.ip }

                    sampler.record(
                        downloadCandidates.map { scanResult ->
                            val downloadResult = allResultByIp[scanResult.ip]
                            CfstLearningObservation(
                                ip = scanResult.ip,
                                downloadSpeedMbps = downloadResult?.downloadSpeedMbps ?: 0.0,
                                latencyMs = scanResult.latencyMs,
                                success = downloadResult != null
                            )
                        }
                    )

                    val displayedResults = if (selectedRegion.pops.isEmpty()) {
                        downloadCandidates.map { scanResult ->
                            DownloadDisplayResult(scanResult, resultByIp[scanResult.ip])
                        }
                    } else {
                        downloadCandidates.mapNotNull { scanResult ->
                            resultByIp[scanResult.ip]?.let { downloadResult ->
                                DownloadDisplayResult(scanResult, downloadResult)
                            }
                        }
                    }.sortedWith(
                        compareBy<DownloadDisplayResult> { it.downloadResult == null }
                            .thenByDescending { item -> item.downloadResult?.let { qualityByIp[it.ip]?.totalScore } ?: -1.0 }
                            .thenBy { it.downloadResult?.ttfbMs ?: Long.MAX_VALUE }
                            .thenBy { it.downloadResult?.tlsHandshakeMs ?: Long.MAX_VALUE }
                            .thenBy { it.downloadResult?.tcpConnectMs ?: Long.MAX_VALUE }
                    )

                    val realNodeCandidates = qualityResults
                        .take(10)
                        .map { it.result }

                    // Keep the existing first/second-stage selection completely intact.
                    // Only the final Top 10 are passed to the real VLESS + WS verifier.
                    val physicalInterface =
                        connectivityManager.getLinkProperties(physicalNetwork)?.interfaceName

                    val vlessResults = if (realNodeCandidates.isEmpty()) {
                        emptyList()
                    } else {
                        runOnUiThread {
                            statusText.text = "第三阶段 · 真实 VLESS + WS 验证"
                            resultText.visibility = View.VISIBLE
                            resultText.text =
                                "仅验证 Top ${realNodeCandidates.size} 个入口：真实 sing-box → TLS → WS → VLESS…"
                        }

                        val metricsByIp = regionResults.associateBy { it.ip }
                        VlessWsScanner(
                            network = physicalNetwork,
                            timeoutMs = 8_000,
                            concurrency = 4
                        ).scan(
                            ips = realNodeCandidates.map { it.ip },
                            host = "life.mozzarella.top",
                            path = "/micro?ed=2560",
                            interfaceName = physicalInterface
                        ) { verified ->
                            if (verified.success) {
                                saveSuccessfulVlessToHistory(verified, metricsByIp)
                                runOnUiThread {
                                    renderHistorySection(resultTable, getPersistedSelectedIp(), clearFirst = false)
                                }
                            }
                        }
                    }

                    // Keep the existing result table data for the first/second stages.
                    // The final selected IP is decided only by a successful real
                    // VLESS + WS response.
                    val realNodeResults = realNodeCandidates.mapNotNull { candidate ->
                        regionResults.firstOrNull { it.ip == candidate.ip }
                    }

                    val selectedIp = vlessResults
                        .asSequence()
                        .filter { it.success }
                        .sortedBy { it.latencyMs ?: Long.MAX_VALUE }
                        .firstOrNull()
                        ?.ip
                        ?: getPersistedSelectedIp()
                    if (selectedIp != null) {
                        BoxIpDnsServer.setCurrentIp(selectedIp)
                    }

                    runOnUiThread {
                        downloadValue.text = regionResults.size.toString()
                        statusText.text = if (selectedRegion.pops.isEmpty()) {
                            "测速完成 · 按入口连接质量排序"
                        } else {
                            "测速完成 · ${selectedRegion.label}"
                        }
                        resultText.visibility = if (displayedResults.isEmpty()) View.VISIBLE else View.GONE
                        resultText.text = if (displayedResults.isEmpty()) {
                            if (selectedRegion.pops.isEmpty()) {
                                "没有成功的入口质量测试结果。"
                            } else {
                                "当前网络下未测到 ${selectedRegion.label}，可切换为自动再测试。"
                            }
                        } else {
                            ""
                        }

                        renderRealNodeSection(
                            resultTable,
                            realNodeCandidates,
                            realNodeResults,
                            selectedIp,
                            vlessResults
                        )
                        renderHistorySection(resultTable, selectedIp, clearFirst = false)
                        saveLastResults(
                            ranges.ipv4.size,
                            candidates.size,
                            results.size,
                            downloadResults.size,
                            displayedResults,
                            realNodeCandidates,
                            realNodeResults,
                            selectedIp,
                            vlessResults
                        )

                        startButton.isEnabled = true
                        themeButton.isEnabled = true
                        regionSpinner.isEnabled = true
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        statusText.text = "测速失败"
                        resultText.text = e.message ?: e.javaClass.simpleName
                        startButton.isEnabled = true
                        themeButton.isEnabled = true
                        regionSpinner.isEnabled = true
                    }
                }
            }
        }
    }

    private data class DownloadDisplayResult(
        val scanResult: CfstScanResult,
        val downloadResult: CfstDownloadResult?
    )

    private fun renderVlessAndHistorySection(
        resultTable: LinearLayout,
        selectedIp: String?,
        vlessResults: List<VlessWsResult>
    ) {
        resultTable.removeAllViews()
        resultTable.visibility = View.VISIBLE
        renderVlessSection(resultTable, vlessResults)
        renderHistorySection(resultTable, selectedIp, clearFirst = false)
    }

    private fun renderVlessSection(
        resultTable: LinearLayout,
        vlessResults: List<VlessWsResult>
    ) {
        if (vlessResults.isEmpty()) return

        val domainSection = TextView(this).apply {
            text = "真实节点域名验证 · life.mozzarella.top"
            setTextColor(getThemeColor(R.attr.boxTextPrimary))
            textSize = 13f
            setPadding(dp(4), dp(18), dp(4), dp(8))
        }
        resultTable.addView(domainSection)

        val vlessSection = TextView(this).apply {
            text = "真实 VLESS + WS 验证"
            setTextColor(getThemeColor(R.attr.boxTextPrimary))
            textSize = 13f
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        resultTable.addView(vlessSection)

        vlessResults
            .sortedWith(compareBy<VlessWsResult> { !it.success }.thenBy { it.latencyMs ?: Long.MAX_VALUE })
            .forEach { result ->
                val row = TextView(this).apply {
                    text = if (result.success) {
                        "✓ ${result.ip}   成功   " +
                            (result.latencyMs?.let { "${it} ms" } ?: "-") +
                            "   ${result.stage}"
                    } else {
                        "✕ ${result.ip}   ${result.error ?: "失败"}"
                    }
                    setTextColor(getThemeColor(R.attr.boxTextSecondary))
                    textSize = 12f
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    maxLines = 4
                }
                resultTable.addView(row)
            }
    }

    private fun renderHistorySection(
        resultTable: LinearLayout,
        selectedIp: String?,
        clearFirst: Boolean = true
    ) {
        // Re-rendering the history must not make the user leave or jump within
        // the current result page. Preserve the ScrollView position exactly.
        val scrollView = (resultTable.parent?.parent as? android.widget.ScrollView)
        val savedScrollY = scrollView?.scrollY ?: 0

        if (clearFirst) {
            resultTable.removeAllViews()
        }
        resultTable.visibility = View.VISIBLE

        val prefs = getSharedPreferences("boxip_results", Context.MODE_PRIVATE)
        val history = parseHistory(prefs.getString(KEY_HISTORY, null))
            .sortedWith(
                compareBy<HistoryResult> { it.ip != selectedIp }
                    .thenBy { it.vlessLatencyMs.takeIf { value -> value >= 0L } ?: Long.MAX_VALUE }
                    .thenByDescending { it.testedAt }
            )

        val section = TextView(this).apply {
            text = "历史可用节点"
            setTextColor(getThemeColor(R.attr.boxTextPrimary))
            textSize = 13f
            setPadding(dp(4), dp(18), dp(4), dp(8))
        }
        resultTable.addView(section)

        if (history.isEmpty()) {
            resultTable.addView(TextView(this).apply {
                text = "暂无通过真实 VLESS + WS 验证的节点"
                setTextColor(getThemeColor(R.attr.boxTextSecondary))
                textSize = 12f
                setPadding(dp(4), dp(8), dp(4), dp(12))
            })
            return
        }

        history.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }

            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val ipText = TextView(this).apply {
                text = item.ip
                setTextColor(getThemeColor(R.attr.boxTextSecondary))
                textSize = 12f
                setPadding(0, 0, dp(6), 0)
                setOnClickListener { selectIp(item.ip, resultTable) }
            }
            top.addView(ipText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            if (retestingHistoryIps.contains(item.ip)) {
                top.addView(ProgressBar(this).apply {
                    isIndeterminate = true
                    setPadding(0, 0, dp(6), 0)
                }, LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    marginEnd = dp(6)
                })
            }

            val testButton = Button(this).apply {
                text = "重测"
                textSize = 11f
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(10), 0, dp(10), 0)
                isEnabled = !retestingHistoryIps.contains(item.ip)
                setOnClickListener { retestHistoryIp(item.ip, resultTable) }
            }
            top.addView(testButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)))

            val deleteButton = Button(this).apply {
                text = "删除"
                textSize = 11f
                minHeight = 0
                minimumHeight = 0
                setPadding(dp(10), 0, dp(10), 0)
                setOnClickListener { deleteHistoryIp(item.ip, resultTable) }
            }
            top.addView(deleteButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)))

            row.addView(top)

            val metricsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            fun addHistoryMetric(text: String) {
                metricsRow.addView(TextView(this).apply {
                    this.text = text
                    setTextColor(getThemeColor(R.attr.boxTextSecondary))
                    textSize = 11f
                    maxLines = 1
                    gravity = Gravity.START
                }, LinearLayout.LayoutParams(0, dp(24), 1f))
            }

            val vless = item.vlessLatencyMs.takeIf { it >= 0L }?.let { "${it} ms" } ?: "-"
            addHistoryMetric("VLESS $vless")
            addHistoryMetric("TCP ${item.tcpMs.takeIf { it >= 0L } ?: "-"}")
            addHistoryMetric("TLS ${item.tlsMs.takeIf { it >= 0L } ?: "-"}")
            addHistoryMetric("TTFB ${item.ttfbMs.takeIf { it >= 0L } ?: "-"}")
            addHistoryMetric(String.format(Locale.US, "%.1f MB/s", item.speed))
            addHistoryMetric(item.pop ?: "区域未知")
            row.addView(metricsRow)
            val divider = View(this).apply {
                setBackgroundColor(getThemeColor(R.attr.boxDivider))
            }
            row.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)))

            resultTable.addView(row)
        }

        // Restore the exact position after rebuilding the history rows.
        scrollView?.post {
            scrollView.scrollTo(0, savedScrollY)
        }
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
                    }
                )
            )
        }

        if (vlessResults.isNotEmpty()) {
            val vlessSection = TextView(this).apply {
                text = "真实 VLESS + WS 验证"
                setTextColor(getThemeColor(R.attr.boxTextPrimary))
                textSize = 13f
                setPadding(dp(4), dp(18), dp(4), dp(8))
            }
            resultTable.addView(vlessSection)

            val sortedVlessResults = vlessResults.sortedWith(
                compareBy<VlessWsResult> { !it.success }
                    .thenBy { it.latencyMs ?: Long.MAX_VALUE }
            )

            sortedVlessResults.forEach { result ->
                val row = TextView(this).apply {
                    text = if (result.success) {
                        "✓ " + result.ip + "   成功   " +
                            (result.latencyMs?.let { "${it} ms" } ?: "-")
                    } else {
                        "✕ " + result.ip + "   " + (result.error ?: "失败")
                    }
                    setTextColor(getThemeColor(R.attr.boxTextSecondary))
                    textSize = 12f
                    setPadding(dp(4), dp(6), dp(4), dp(6))
                    maxLines = 2
                }
                resultTable.addView(row)
            }
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

        vlessResults.filter { it.success }.forEach { verified ->
            val metrics = metricsByIp[verified.ip]
            val item = HistoryResult(
                ip = verified.ip,
                tcpMs = metrics?.tcpConnectMs ?: -1L,
                tlsMs = metrics?.tlsHandshakeMs ?: -1L,
                ttfbMs = metrics?.ttfbMs ?: -1L,
                speed = metrics?.downloadSpeedMbps ?: 0.0,
                pop = metrics?.pop,
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
            renderVlessSection(
                resultTable,
                vlessResults.map {
                    VlessWsResult(
                        ip = it.ip,
                        latencyMs = it.latencyMs,
                        success = it.success,
                        stage = it.stage,
                        error = it.error
                    )
                }
            )
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
                    }
                )
            )
        }

        if (vlessResults.isNotEmpty()) {
            val vlessSection = TextView(this).apply {
                text = "真实 VLESS + WS 验证"
                setTextColor(getThemeColor(R.attr.boxTextPrimary))
                textSize = 13f
                setPadding(dp(4), dp(18), dp(4), dp(8))
            }
            resultTable.addView(vlessSection)

            vlessResults
                .sortedWith(compareBy<RestoredVlessResult> { !it.success }.thenBy { it.latencyMs ?: Long.MAX_VALUE })
                .forEach { result ->
                    val row = TextView(this).apply {
                        text = if (result.success) {
                            "✓ " + result.ip + "   成功   " +
                                (result.latencyMs?.let { "${it} ms" } ?: "-")
                        } else {
                            "✕ " + result.ip + "   " + (result.error ?: "失败")
                        }
                        setTextColor(getThemeColor(R.attr.boxTextSecondary))
                        textSize = 12f
                        setPadding(dp(4), dp(6), dp(4), dp(6))
                        maxLines = 2
                    }
                    resultTable.addView(row)
                }
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

    private fun saveSuccessfulVlessToHistory(
        verified: VlessWsResult,
        metricsByIp: Map<String, CfstDownloadResult>
    ) {
        val metrics = metricsByIp[verified.ip]
        val item = HistoryResult(
            ip = verified.ip,
            tcpMs = metrics?.tcpConnectMs ?: -1L,
            tlsMs = metrics?.tlsHandshakeMs ?: -1L,
            ttfbMs = metrics?.ttfbMs ?: -1L,
            speed = metrics?.downloadSpeedMbps ?: 0.0,
            pop = metrics?.pop,
            vlessLatencyMs = verified.latencyMs ?: -1L,
            testedAt = System.currentTimeMillis()
        )
        updateHistoryItem(item)
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

    private fun deleteHistoryIp(ip: String, resultTable: LinearLayout) {
        val prefs = getSharedPreferences("boxip_results", Context.MODE_PRIVATE)
        val history = parseHistory(prefs.getString(KEY_HISTORY, null)).filterNot { it.ip == ip }
        val editor = prefs.edit().putString(KEY_HISTORY, serializeHistory(history))

        if (getPersistedSelectedIp() == ip) {
            editor.remove("selectedIp")
            BoxIpDnsServer.setCurrentIp("")
        }

        editor.commit()
        renderHistorySection(resultTable, getPersistedSelectedIp())
    }

    private fun retestHistoryIp(ip: String, resultTable: LinearLayout) {
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
            statusTextForHistory("重测失败：没有可用的物理网络")
            return
        }

        val physicalInterface = connectivityManager.getLinkProperties(physicalNetwork)?.interfaceName
        if (!retestingHistoryIps.add(ip)) return
        renderHistorySection(resultTable, getPersistedSelectedIp())

        executor.execute {
            val result = runCatching {
                VlessWsScanner(
                    network = physicalNetwork,
                    timeoutMs = 8_000,
                    concurrency = 1
                ).scan(
                    ips = listOf(ip),
                    host = "life.mozzarella.top",
                    path = "/micro?ed=2560",
                    interfaceName = physicalInterface
                ).firstOrNull()
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
                    }
                }
                renderHistorySection(resultTable, getPersistedSelectedIp())
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
        onSelect: ((String) -> Unit)? = null
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
                    contentDescription = if (ip == selectedIp) {
                        "当前使用 $ip"
                    } else {
                        "可用入口 $ip"
                    }
                    background = createSelectorDrawable(ip == selectedIp)
                    tag = ip
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onSelect?.invoke(ip) }
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
        BoxIpDnsServer.setCurrentIp(ip)
        persistSelectedIp(ip)
        updateSelectionIndicators(resultTable, ip)
    }

    private fun updateSelectionIndicators(resultTable: LinearLayout, selectedIp: String) {
        for (index in 0 until resultTable.childCount) {
            val row = resultTable.getChildAt(index) as? LinearLayout ?: continue
            val ipCell = row.getChildAt(0) as? LinearLayout ?: continue
            val selector = ipCell.getChildAt(0) as? TextView ?: continue
            val ip = selector.tag as? String ?: continue

            selector.background = createSelectorDrawable(ip == selectedIp)
            selector.contentDescription = if (ip == selectedIp) {
                "当前使用 $ip"
            } else {
                "可用入口 $ip"
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