package com.huihou.glasses

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** 系统通知只是兜底：镜片弹不出来时（没开悬浮窗权限）至少还有一条通知。 */
object Notifier {

    const val WORKING_ID = 1001
    private const val RESULT_ID = 1002
    private const val CHANNEL = "huihou"

    private fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "会后纪要", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    fun working(ctx: Context): Notification {
        channel(ctx)
        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("正在生成会议纪要")
            .setOngoing(true)
            .build()
    }

    fun result(ctx: Context, text: String, open: Intent) {
        channel(ctx)
        val pi = PendingIntent.getActivity(ctx, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(RESULT_ID, n) }
    }
}
