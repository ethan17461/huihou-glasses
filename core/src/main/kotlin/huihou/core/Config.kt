package huihou.core

import java.io.File
import java.util.Properties

data class Config(
    val apiKey: String,
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val feishuWebhook: String = "",
    val feishuSecret: String = "",
    /** 戴眼镜的人在会上的称呼，用来判断“我的待办”。 */
    val userNames: List<String> = emptyList(),
) {
    val hasUser: Boolean get() = userNames.isNotEmpty()

    companion object {
        const val DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        const val DEFAULT_MODEL = "qwen-plus"

        fun fromProperties(p: Properties): Config = Config(
            apiKey = p.str("DASHSCOPE_API_KEY"),
            baseUrl = p.str("DASHSCOPE_BASE_URL").ifEmpty { DEFAULT_BASE_URL },
            model = p.str("QWEN_MODEL").ifEmpty { DEFAULT_MODEL },
            feishuWebhook = p.str("FEISHU_WEBHOOK"),
            feishuSecret = p.str("FEISHU_SECRET"),
            userNames = splitNames(p.str("USER_NAMES")),
        )

        /** 按 UTF-8 读 properties（Properties.load(InputStream) 默认是 ISO-8859-1，中文会乱码）。 */
        fun loadProperties(file: File, into: Properties = Properties()): Properties {
            if (file.exists()) file.reader(Charsets.UTF_8).use { into.load(it) }
            return into
        }

        fun splitNames(raw: String): List<String> =
            raw.split(',', '，', '、', ';', '；').map { it.trim() }.filter { it.isNotEmpty() }

        private fun Properties.str(key: String) = (getProperty(key) ?: "").trim()
    }
}
