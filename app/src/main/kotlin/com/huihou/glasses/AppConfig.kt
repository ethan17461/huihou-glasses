package com.huihou.glasses

import android.content.Context
import huihou.core.Config
import java.io.File
import java.util.Properties

/**
 * 配置来源（后者覆盖前者）：
 * 1. 构建时 secrets.properties 带进来的默认值（BuildConfig）
 * 2. 眼镜上的 /sdcard/Android/data/com.huihou.glasses/files/config.properties（adb push 进去，改了不用重装）
 */
object AppConfig {

    fun configFile(ctx: Context): File = File(ctx.getExternalFilesDir(null), "config.properties")

    fun load(ctx: Context): Config {
        val p = Properties()
        p["DASHSCOPE_API_KEY"] = BuildConfig.DASHSCOPE_API_KEY
        p["DASHSCOPE_BASE_URL"] = BuildConfig.DASHSCOPE_BASE_URL
        p["QWEN_MODEL"] = BuildConfig.QWEN_MODEL
        p["FEISHU_WEBHOOK"] = BuildConfig.FEISHU_WEBHOOK
        p["FEISHU_SECRET"] = BuildConfig.FEISHU_SECRET
        p["USER_NAMES"] = BuildConfig.USER_NAMES
        Config.loadProperties(configFile(ctx), p)
        return Config.fromProperties(p)
    }
}
