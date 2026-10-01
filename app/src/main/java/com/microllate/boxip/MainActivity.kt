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
    }

    private data class RegionOption(
        val label: String,
        val pops: Set<String>,
        val candidateCount: Int
    )

    private val regionOptions = listOf(
        RegionOption("自动", emptySet(), 10),
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

                    // Automatic mode keeps the existing Top 10 flow.
                    // Region mode first performs a short PoP discovery across
                    // a larger and more diverse pool. Anycast cannot force a
                    // specific PoP; we need to observe what this network reaches.
                    val initialCandidates = if (selectedRegion.pops.isEmpty()) {
                        buildList {
                            addAll(retainedResults.distinctBy { it.ip }.take(10))
                            for (result in results) {
                                if (size >= 10) break
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
                            .thenByDescending { it.downloadResult?.stabilityPercent ?: 0.0 }
                            .thenBy { it.downloadResult?.tlsHandshakeMs ?: Long.MAX_VALUE }
                            .thenBy { it.downloadResult?.ttfbMs ?: Long.MAX_VALUE }
                            .thenBy { it.downloadResult?.tcpConnectMs ?: Long.MAX_VALUE }
                    )

                    val realNodeCandidates = regionResults.take(10)
                    val realNodeResults = if (realNodeCandidates.isEmpty()) {
                        emptyList()
                    } else {
                        runOnUiThread {
                            statusText.text = "第三阶段 · 真实节点域名验证"
                            resultText.visibility = View.VISIBLE
                            resultText.text = "仅验证 Top ${realNodeCandidates.size} 个入口，每个 IP 进行 2 次轻量 TLS / TTFB 探测…"
                        }

                        CfstDownloader(
                            network = physicalNetwork,
                            downloadUrl = "https://life.mozzarella.top/cdn-cgi/trace",
                            observationMs = 10_000,
                            probeIntervalMs = 5_000,
                            connectTimeoutMs = 3_000,
                            probeTimeoutMs = 3_000,
                            concurrency = 2
                        ).download(realNodeCandidates.map { it.ip })
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

                        renderResults(
                            resultTable,
                            displayedResults,
                            realNodeCandidates,
                            realNodeResults
                        )
                        saveLastResults(
                            ranges.ipv4.size,
                            candidates.size,
                            results.size,
                            downloadResults.size,
                            displayedResults
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

    private fun renderResults(
        resultTable: LinearLayout,
        displayedResults: List<DownloadDisplayResult>,
        realNodeCandidates: List<CfstDownloadResult> = emptyList(),
        realNodeResults: List<CfstDownloadResult> = emptyList()
    ) {
        resultTable.removeAllViews()
        resultTable.visibility = View.VISIBLE

        val columnWidths = contentColumnWidths(
            displayedResults.map { item ->
                val scanResult = item.scanResult
                val downloadResult = item.downloadResult
                listOf(
                    scanResult.ip,
                    downloadResult?.let { "${it.tcpConnectMs} ms" } ?: "失败",
                    downloadResult?.let { "${it.tlsHandshakeMs} ms" } ?: "-",
                    downloadResult?.let { "${it.ttfbMs} ms" } ?: "-",
                    downloadResult?.let { String.format(Locale.US, "%.0f%%", it.stabilityPercent) } ?: "-",
                    downloadResult?.pop ?: "-"
                )
            }
        )

        resultTable.addView(
            createResultRow("IP", "TCP", "TLS", "TTFB", "稳定性", "区域", header = true, columnWidths = columnWidths)
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

        displayedResults.forEach { item ->
            val scanResult = item.scanResult
            val downloadResult = item.downloadResult
            resultTable.addView(
                createResultRow(
                    scanResult.ip,
                    downloadResult?.let { "${it.tcpConnectMs} ms" } ?: "失败",
                    downloadResult?.let { "${it.tlsHandshakeMs} ms" } ?: "-",
                    downloadResult?.let { "${it.ttfbMs} ms" } ?: "-",
                    downloadResult?.let { String.format(Locale.US, "%.0f%%", it.stabilityPercent) } ?: "-",
                    downloadResult?.pop ?: "-",
                    header = false,
                    columnWidths = columnWidths
                )
            )
        }

        if (realNodeCandidates.isNotEmpty()) {
            val section = TextView(this).apply {
                text = "真实节点域名验证 · life.mozzarella.top"
                setTextColor(getThemeColor(R.attr.boxTextPrimary))
                textSize = 13f
                setPadding(dp(4), dp(18), dp(4), dp(8))
            }
            resultTable.addView(section)

            val realNodeByIp = realNodeResults.associateBy { it.ip }
            // Rank the third-stage table by the real-domain verification results,
            // rather than by the public speed.cloudflare.com ranking used to pick candidates.
            val sortedRealNodeCandidates = realNodeCandidates.sortedWith(
                compareBy<CfstDownloadResult> { realNodeByIp[it.ip] == null }
                    .thenByDescending { realNodeByIp[it.ip]?.stabilityPercent ?: 0.0 }
                    .thenBy { realNodeByIp[it.ip]?.tlsHandshakeMs ?: Long.MAX_VALUE }
                    .thenBy { realNodeByIp[it.ip]?.ttfbMs ?: Long.MAX_VALUE }
                    .thenBy { realNodeByIp[it.ip]?.tcpConnectMs ?: Long.MAX_VALUE }
            )
            val realNodeRows = sortedRealNodeCandidates.map { result ->
                val verified = realNodeByIp[result.ip]
                listOf(
                    result.ip,
                    verified?.let { "${it.tcpConnectMs} ms" } ?: "失败",
                    verified?.let { "${it.tlsHandshakeMs} ms" } ?: "-",
                    verified?.let { "${it.ttfbMs} ms" } ?: "-",
                    verified?.let { String.format(Locale.US, "%.0f%%", it.stabilityPercent) } ?: "-",
                    verified?.pop ?: "-"
                )
            }
            val realNodeWidths = contentColumnWidths(realNodeRows)
            resultTable.addView(
                createResultRow(
                    "IP", "TCP", "TLS", "TTFB", "稳定性", "区域",
                    header = true,
                    columnWidths = realNodeWidths
                )
            )
            sortedRealNodeCandidates.forEach { result ->
                val verified = realNodeByIp[result.ip]
                resultTable.addView(
                    createResultRow(
                        result.ip,
                        verified?.let { "${it.tcpConnectMs} ms" } ?: "失败",
                        verified?.let { "${it.tlsHandshakeMs} ms" } ?: "-",
                        verified?.let { "${it.ttfbMs} ms" } ?: "-",
                        verified?.let { String.format(Locale.US, "%.0f%%", it.stabilityPercent) } ?: "-",
                        verified?.pop ?: "-",
                        header = false,
                        columnWidths = realNodeWidths
                    )
                )
            }
        }
    }

    private fun saveLastResults(
        ranges: Int,
        candidates: Int,
        tcp: Int,
        downloads: Int,
        results: List<DownloadDisplayResult>
    ) {
        val array = JSONArray()
        results.forEach { item ->
            val scanResult = item.scanResult
            val downloadResult = item.downloadResult
            array.put(JSONObject().apply {
                put("ip", scanResult.ip)
                put("loss", scanResult.lossRate)
                put("latency", scanResult.latencyMs ?: -1L)
                put("success", downloadResult != null)
                put("tcpMs", downloadResult?.tcpConnectMs ?: -1L)
                put("tlsMs", downloadResult?.tlsHandshakeMs ?: -1L)
                put("ttfbMs", downloadResult?.ttfbMs ?: -1L)
                put("stability", downloadResult?.stabilityPercent ?: 0.0)
                put("speed", downloadResult?.downloadSpeedMbps ?: 0.0)
                put("pop", downloadResult?.pop ?: "")
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
                    success = item.has("tcpMs") && item.optBoolean("success", false),
                    tcpMs = item.optLong("tcpMs", -1L),
                    tlsMs = item.optLong("tlsMs", -1L),
                    ttfbMs = item.optLong("ttfbMs", -1L),
                    stability = item.optDouble("stability", 0.0),
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
            val columnWidths = contentColumnWidths(
                restored.map { item ->
                    listOf(
                        item.ip,
                        if (item.success) "${item.tcpMs} ms" else "失败",
                        if (item.success) "${item.tlsMs} ms" else "-",
                        if (item.success) "${item.ttfbMs} ms" else "-",
                        if (item.success) String.format(Locale.US, "%.0f%%", item.stability) else "-",
                        if (item.success) item.pop else "-"
                    )
                }
            )

            resultTable.addView(
                createResultRow("IP", "TCP", "TLS", "TTFB", "稳定性", "区域", header = true, columnWidths = columnWidths)
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
                        if (item.success) "${item.tcpMs} ms" else "失败",
                        if (item.success) "${item.tlsMs} ms" else "-",
                        if (item.success) "${item.ttfbMs} ms" else "-",
                        if (item.success) String.format(Locale.US, "%.0f%%", item.stability) else "-",
                        if (item.success) item.pop else "-",
                        header = false,
                        columnWidths = columnWidths
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
        columnWidths: IntArray
    ): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val values = listOf(ip, loss, latency, speed, stability, pop)
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
                    columnWidths[index],
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        return row
    }

    /**
     * Give each column a weight based on the widest text currently shown.
     * This keeps the columns aligned across rows while avoiding fixed 20% columns.
     */
    /**
     * Calculate one shared pixel width per column from the widest cell content.
     * Any unused screen width is then distributed evenly so short columns
     * do not become disproportionately narrow.
     */
    private fun contentColumnWidths(rows: List<List<String>>): IntArray {
        val widths = IntArray(6)

        rows.forEach { row ->
            row.forEachIndexed { index, value ->
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = sp(if (index == 0 && value != "IP") 12f else 13f)
                }
                val measured = paint.measureText(value).toInt()
                widths[index] = maxOf(widths[index], measured + dp(16))
            }
        }

        val available = resources.displayMetrics.widthPixels - dp(68)
        val total = widths.sum()

        if (total < available) {
            val extra = available - total
            val each = extra / widths.size
            var remainder = extra % widths.size
            for (index in widths.indices) {
                widths[index] += each
                if (remainder > 0) {
                    widths[index]++
                    remainder--
                }
            }
        } else if (total > available && total > 0) {
            val scale = available.toFloat() / total.toFloat()
            for (index in widths.indices) {
                widths[index] = maxOf(dp(36), (widths[index] * scale).toInt())
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
