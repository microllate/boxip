package com.microllate.boxip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * External trigger for switching the sing-box BoxIP endpoint.
 *
 * A third-party watchdog only needs to send ACTION_SWITCH_HISTORY_IP.
 * BoxIP reads its own history and publishes the next historical IP.
 * No Activity needs to stay alive.
 */
class HistoryIpSwitchReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SWITCH_HISTORY_IP =
            "com.microllate.boxip.action.SWITCH_HISTORY_IP"

        private const val TAG = "HistoryIpSwitchReceiver"
        private const val PREFS = "boxip_results"
        private const val KEY_HISTORY = "history"
        private const val KEY_SNAPSHOT = "snapshot"
        private const val KEY_SELECTED_IP = "selectedIp"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SWITCH_HISTORY_IP) return

        val pendingResult = goAsync()
        Thread {
            try {
                val result = switchToNextHistoryIp(context.applicationContext)
                Log.i(TAG, result)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch historical IP", e)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun switchToNextHistoryIp(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val currentIp = prefs.getString(KEY_SELECTED_IP, null).orEmpty()

        val history = parseHistory(
            prefs.getString(KEY_HISTORY, null)
        ).filter { isValidIpv4(it.ip) }
            .sortedByDescending { it.testedAt }

        if (history.isEmpty()) {
            return "No historical IP available"
        }

        val currentIndex = history.indexOfFirst { it.ip == currentIp }
        val next = if (currentIndex >= 0) {
            history[(currentIndex + 1) % history.size]
        } else {
            history.first()
        }

        if (next.ip == currentIp) {
            return "No alternate historical IP available"
        }

        // Initialize the root hosts file even when the app process was not
        // already running.
        BoxIpDnsServer.start(context)
        BoxIpDnsServer.setCurrentIp(next.ip)

        val snapshot = prefs.getString(KEY_SNAPSHOT, null)
        val editor = prefs.edit()
            .putString(KEY_SELECTED_IP, next.ip)

        if (!snapshot.isNullOrEmpty()) {
            runCatching {
                JSONObject(snapshot).apply {
                    put(KEY_SELECTED_IP, next.ip)
                    put("savedAt", System.currentTimeMillis())
                }.toString()
            }.onSuccess {
                editor.putString(KEY_SNAPSHOT, it)
            }
        }

        editor.commit()

        // If the BoxIP Activity is currently visible, update its selection
        // indicator immediately. If it is not running, persisted selectedIp
        // will be restored the next time the Activity opens.
        MainActivity.notifyExternalSelectionChanged(next.ip)

        return "Switched historical IP: " + currentIp + " -> " + next.ip
    }

    private data class HistoryEntry(
        val ip: String,
        val testedAt: Long
    )

    private fun parseHistory(raw: String?): List<HistoryEntry> {
        if (raw.isNullOrEmpty()) return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        HistoryEntry(
                            ip = item.optString("ip", ""),
                            testedAt = item.optLong("testedAt", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun isValidIpv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        return parts.all {
            val value = it.toIntOrNull()
            value != null && value in 0..255
        }
    }
}
