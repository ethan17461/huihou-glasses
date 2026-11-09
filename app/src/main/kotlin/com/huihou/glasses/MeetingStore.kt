package com.huihou.glasses

import android.content.Context
import huihou.core.Minutes
import huihou.core.MinutesParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 每场会一个文件夹：transcript.txt（会议文字）、minutes.json（纪要）、feishu.ok / feishu.error。
 * 只存文字，不存录音。纪要也是给会前、会中复用的“卡片”。
 */
class MeetingStore(ctx: Context) {

    private val root = File(ctx.filesDir, "meetings").apply { mkdirs() }

    /** adb 推会议文字进来的地方：/sdcard/Android/data/com.huihou.glasses/files/inbox */
    val inbox: File = File(ctx.getExternalFilesDir(null), "inbox").apply { mkdirs() }

    fun newMeeting(transcript: String): File {
        val base = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var dir = File(root, base)
        var n = 1
        while (dir.exists()) dir = File(root, "$base-${n++}")
        dir.mkdirs()
        File(dir, TRANSCRIPT).writeText(transcript, Charsets.UTF_8)
        return dir
    }

    fun transcript(dir: File): String = File(dir, TRANSCRIPT).readText(Charsets.UTF_8)

    fun saveMinutes(dir: File, m: Minutes) = File(dir, MINUTES).writeText(MinutesParser.toJson(m), Charsets.UTF_8)

    fun loadMinutes(dir: File): Minutes? {
        val f = File(dir, MINUTES)
        if (!f.exists()) return null
        return runCatching { MinutesParser.json.decodeFromString(Minutes.serializer(), f.readText(Charsets.UTF_8)) }.getOrNull()
    }

    fun markFeishu(dir: File, error: String?) {
        File(dir, FEISHU_OK).delete()
        File(dir, FEISHU_ERROR).delete()
        if (error == null) File(dir, FEISHU_OK).writeText(System.currentTimeMillis().toString())
        else File(dir, FEISHU_ERROR).writeText(error, Charsets.UTF_8)
    }

    fun feishuFailed(dir: File): Boolean = File(dir, FEISHU_ERROR).exists()

    /** 最近一场已经生成纪要的会。 */
    fun latestWithMinutes(): File? =
        root.listFiles()?.filter { File(it, MINUTES).exists() }?.maxByOrNull { it.name }

    companion object {
        const val TRANSCRIPT = "transcript.txt"
        const val MINUTES = "minutes.json"
        const val FEISHU_OK = "feishu.ok"
        const val FEISHU_ERROR = "feishu.error"
    }
}
