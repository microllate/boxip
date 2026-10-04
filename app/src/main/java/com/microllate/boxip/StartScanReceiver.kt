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

        showScanNotification(context)

        // If MainActivity is already alive (including while in the background),
        // run the existing scan flow directly so the UI is not brought forward.
        if (MainActivity.startScanExternally()) {
            return
        }

        // Fall back to creating MainActivity only when the process/activity is
        // not alive yet. This preserves the existing trigger behavior after
        // the app process has been reclaimed by Android.
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

    private fun showScanNotification(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "boxip_background_scan"

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "BoxIP 后台测速",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "BoxIP 自动触发后台测速"
            }
            manager.createNotificationChannel(channel)
        }

        if (
            android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled().not()
        ) {
            return
        }

        val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
            .setSmallIcon(com.microllate.boxip.R.drawable.boxip_icon)
            .setContentTitle("BoxIP")
            .setContentText("正在后台自动测速…")
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

        androidx.core.app.NotificationManagerCompat.from(context)
            .notify(1001, notification)
    }
}
