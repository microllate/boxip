package com.microllate.boxip

import android.app.Activity
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

        startButton.setOnClickListener {
            startButton.isEnabled = false
            statusText.text = "正在获取 Cloudflare IPv4 网段..."
            resultText.text = ""

            executor.execute {
                try {
                    val ranges = CloudflareIpProvider().fetch()
                    val candidates = Ipv4Sampler().sample(ranges.ipv4)
                    val results = TcpScanner().scan(candidates)
                    val successful = results.filter { it.success }.take(30)

                    val output = buildString {
                        append("IPv4 网段：")
                        append(ranges.ipv4.size)
                        append("\n候选 IP：")
                        append(candidates.size)
                        append("\nTCP 443 成功：")
                        append(results.count { it.success })
                        append("\n\n最快 IP：\n")

                        if (successful.isEmpty()) {
                            append("没有连接成功的 IP")
                        } else {
                            successful.forEachIndexed { index, result ->
                                append(index + 1)
                                append(". ")
                                append(result.ip)
                                append("  ")
                                append(result.latencyMs)
                                append(" ms\n")
                            }
                        }
                    }

                    runOnUiThread {
                        statusText.text = "TCP 443 测试完成"
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
