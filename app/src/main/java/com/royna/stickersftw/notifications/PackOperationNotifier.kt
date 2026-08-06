package com.royna.stickersftw.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.royna.stickersftw.MainActivity

/** Posts and updates a single ongoing notification per pack operation that
 * the user chose to "Run in background" on -- progress while it runs,
 * a final success/failure state, and a tap action that reopens the app
 * straight onto that pack's [com.royna.stickersftw.ui.screens.ConversionScreen],
 * which already renders whichever of those three states is current. */
object PackOperationNotifier {
    private const val CHANNEL_ID = "pack_operations"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Pack operations",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    fun showProgress(context: Context, packId: String, packTitle: String, stage: String, fraction: Float) {
        post(context, packId, baseBuilder(context, packId, packTitle)
            .setContentText(stage)
            .setProgress(100, (fraction * 100).toInt().coerceIn(0, 100), false)
            .setOngoing(true)
            .setAutoCancel(false)
            .build())
    }

    fun showSuccess(context: Context, packId: String, packTitle: String, message: String) {
        post(context, packId, baseBuilder(context, packId, packTitle)
            .setContentText(message)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .build())
    }

    fun showFailure(context: Context, packId: String, packTitle: String, message: String) {
        post(context, packId, baseBuilder(context, packId, packTitle)
            .setContentText(message)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .build())
    }

    private fun baseBuilder(context: Context, packId: String, packTitle: String): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(packTitle)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent(context, packId))

    private fun contentIntent(context: Context, packId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_PACK_ID, packId)
        }
        return PendingIntent.getActivity(
            context,
            notificationId(packId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationId(packId: String): Int = packId.hashCode()

    private fun post(context: Context, packId: String, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        NotificationManagerCompat.from(context).notify(notificationId(packId), notification)
    }
}
