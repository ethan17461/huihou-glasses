package com.huihou.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * 散会入口。会中模块（或 adb）发广播：
 *   action = com.huihou.glasses.MEETING_ENDED，package = com.huihou.glasses
 * 会议文字三选一：
 *   transcript_text  整场会的文字（字符串）
 *   transcript_uri   content:// 地址（发送方要加 FLAG_GRANT_READ_URI_PERMISSION）
 *   transcript_file  本 App 外部目录下的相对路径，如 inbox/meeting.txt（adb 测试用）
 * 可选：meeting_title 会议名称
 */
class MeetingEndedReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_MEETING_ENDED) return
        val pending = goAsync()
        val app = ctx.applicationContext
        Thread {
            try {
                val text = readTranscript(app, intent)
                if (text.isNullOrBlank()) {
                    Log.w(TAG, "散会广播里没有会议文字")
                    GlassesActivity.showMessage(app, "没收到会议文字")
                    return@Thread
                }
                val dir = MeetingStore(app).newMeeting(text)
                Log.i(TAG, "收到会议文字 ${text.length} 字，存到 ${dir.name}，开始生成纪要")
                MinutesWorker.enqueue(app, dir, intent.getStringExtra(EXTRA_TITLE))
                GlassesActivity.showWorking(app)
            } catch (e: Exception) {
                Log.e(TAG, "处理散会广播失败", e)
                GlassesActivity.showMessage(app, "会议文字读取失败")
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun readTranscript(ctx: Context, intent: Intent): String? {
        intent.getStringExtra(EXTRA_TEXT)?.let { if (it.isNotBlank()) return it }

        @Suppress("DEPRECATION")
        val uri: Uri? = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: intent.getParcelableExtra(EXTRA_URI)
        if (uri != null) {
            return ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        }

        intent.getStringExtra(EXTRA_FILE)?.let { path ->
            val base = ctx.getExternalFilesDir(null)
            val f = if (path.startsWith("/")) File(path) else File(base, path)
            if (f.exists()) return f.readText(Charsets.UTF_8)
            Log.w(TAG, "找不到文件 ${f.absolutePath}")
        }
        return null
    }

    companion object {
        const val TAG = "Huihou"
        const val ACTION_MEETING_ENDED = "com.huihou.glasses.MEETING_ENDED"
        const val EXTRA_TEXT = "transcript_text"
        const val EXTRA_URI = "transcript_uri"
        const val EXTRA_FILE = "transcript_file"
        const val EXTRA_TITLE = "meeting_title"
    }
}
