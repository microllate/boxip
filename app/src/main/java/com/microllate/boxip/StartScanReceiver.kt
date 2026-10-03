package com.microllate.boxip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Internal trigger used when the complete historical IP pool has failed
 * for three rounds. It launches the existing MainActivity scan flow.
 */
class StartScanReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_START_SCAN =
            "com.microllate.boxip.action.START_SCAN"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_START_SCAN) return

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_START_SCAN
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        context.startActivity(launchIntent)
    }
}
