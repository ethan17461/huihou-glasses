package com.huihou.glasses

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import huihou.core.FeishuCard
import huihou.core.FeishuClient
import huihou.core.GlassesText
import huihou.core.HuihouException
import huihou.core.MinutesPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 后台生成纪要：会议文字 → qwen-plus → 核对 → 存档 → 飞书 → 镜片一行提示。断网会等联网后自动重试。 */
class MinutesWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val dir = inputData.getString(KEY_DIR)?.let(::File) ?: return@withContext Result.failure()
        val title = inputData.getString(KEY_TITLE)
        val store = MeetingStore(ctx)
        val cfg = AppConfig.load(ctx)

        if (cfg.apiKey.isBlank()) {
            Log.e(TAG, "没有配置 DASHSCOPE_API_KEY")
            GlassesActivity.showMessage(ctx, GlassesText.error("没配置 API Key"))
            return@withContext Result.failure()
        }

        try {
            val result = MinutesPipeline(cfg).generate(store.transcript(dir), title)
            store.saveMinutes(dir, result.minutes)
            Log.i(TAG, "纪要生成 ${result.elapsedMs}ms，待办 ${result.minutes.todos.size} 条，核对丢掉 ${result.dropped.size} 条")
            result.dropped.forEach { Log.i(TAG, "丢掉：${it.todo.owner}·${it.todo.task}（${it.reason}）") }

            if (cfg.feishuWebhook.isNotBlank()) {
                try {
                    val footer = "AI 眼镜 · 会后纪要　只存文字，不存录音　" +
                        SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date())
                    FeishuClient().send(cfg.feishuWebhook, cfg.feishuSecret, FeishuCard.build(result.minutes, cfg.hasUser, footer))
                    store.markFeishu(dir, null)
                } catch (e: Exception) {
                    Log.e(TAG, "飞书发送失败", e)
                    store.markFeishu(dir, e.message ?: e.javaClass.simpleName)
                }
            }

            val notice = GlassesText.notice(result.minutes, cfg.hasUser, store.feishuFailed(dir))
            GlassesActivity.showMinutes(ctx, dir, notice)
            Result.success()
        } catch (e: IOException) {
            // 网络问题：重试两次，再不行就告诉用户
            Log.w(TAG, "网络错误，第 ${runAttemptCount + 1} 次", e)
            if (runAttemptCount < MAX_RETRIES) {
                Result.retry()
            } else {
                GlassesActivity.showMessage(ctx, GlassesText.error("网络不通"))
                Result.failure()
            }
        } catch (e: HuihouException) {
            Log.e(TAG, "生成失败", e)
            GlassesActivity.showMessage(ctx, GlassesText.error(shortReason(e.message)))
            Result.failure()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(Notifier.WORKING_ID, Notifier.working(applicationContext))

    private fun shortReason(msg: String?): String = when {
        msg == null -> "未知错误"
        msg.contains("401") || msg.contains("API key", ignoreCase = true) -> "API Key 不对"
        msg.contains("太少") -> "会议文字太少"
        msg.contains("超过") -> "会议太长"
        else -> "模型出错"
    }

    companion object {
        private const val TAG = "Huihou"
        private const val KEY_DIR = "dir"
        private const val KEY_TITLE = "title"
        private const val MAX_RETRIES = 2

        fun enqueue(ctx: Context, dir: File, title: String?) {
            val req = OneTimeWorkRequestBuilder<MinutesWorker>()
                .setInputData(workDataOf(KEY_DIR to dir.absolutePath, KEY_TITLE to title))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                .addTag("minutes")
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        }
    }
}
