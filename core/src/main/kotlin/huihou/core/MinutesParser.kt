package huihou.core

import kotlinx.serialization.json.Json

object MinutesParser {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** 容错：去掉 ```json 包裹、前后多余文字，null 当默认值。 */
    fun parse(raw: String): Minutes {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) throw HuihouException("大模型没有返回 JSON：${raw.take(200)}")
        val m = try {
            json.decodeFromString(Minutes.serializer(), raw.substring(start, end + 1))
        } catch (e: Exception) {
            throw HuihouException("纪要 JSON 解析失败：${e.message?.take(200)}", e)
        }
        return Minutes(
            title = m.title.trim().ifEmpty { "会议" }.take(12),
            conclusions = m.conclusions.clean(),
            todos = m.todos
                .map {
                    val due = it.due.trim()
                    it.copy(owner = it.owner.trim(), task = stripDue(it.task.trim(), due), due = due, quote = it.quote.trim(), time = it.time.trim())
                }
                .filter { it.task.isNotEmpty() },
            openQuestions = m.openQuestions.clean(),
        )
    }

    /** 眼镜上显示“截止 · 任务”，任务开头再写一遍时间就重复了：“周五前 · 周五前发排期” → “周五前 · 发排期”。 */
    fun stripDue(task: String, due: String): String {
        if (due.isBlank()) return task
        val bases = listOf(due, due.removePrefix("这").removePrefix("本"))
        val variants = bases.flatMap { listOf(it, it.replace("之前", "前"), it.removeSuffix("之前"), it.removeSuffix("前")) }
            .filter { it.length >= 2 }
            .distinct()
            .sortedByDescending { it.length }
        for (v in variants) {
            if (task.startsWith(v)) {
                val rest = task.removePrefix(v).trimStart(' ', '，', ',', '、', '：', ':')
                return if (rest.length >= 2) rest else task
            }
        }
        return task
    }

    fun toJson(m: Minutes): String = prettyJson.encodeToString(Minutes.serializer(), m)

    private val prettyJson = Json { prettyPrint = true }

    private fun List<String>.clean() = map { it.trim() }.filter { it.isNotEmpty() }
}
