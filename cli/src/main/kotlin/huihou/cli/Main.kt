package huihou.cli

import huihou.core.Config
import huihou.core.FeishuCard
import huihou.core.FeishuClient
import huihou.core.GlassesText
import huihou.core.HuihouException
import huihou.core.Minutes
import huihou.core.MinutesParser
import huihou.core.MinutesPipeline
import huihou.core.Verifier
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

private const val USAGE = """
用法（在项目根目录）：
  ./gradlew :cli:run --args="<会议文字.txt> [--title 评审会] [--expect 标准答案.json] [--no-feishu]"

例子：
  ./gradlew :cli:run --args="testdata/review-meeting.txt --expect testdata/review-meeting.expected.json"

配置从 secrets.properties 读取（参照 secrets.properties.example）。
"""

@Serializable
data class ExpectedTodo(
    val owner: String,
    val keywords: List<String>,
    val mine: Boolean? = null,
    /** 可有可无：抽出来不算多，没抽出来不算漏（比如能和别的条目合并的） */
    val optional: Boolean = false,
)

@Serializable
data class Expected(
    @SerialName("user_names") val userNames: List<String> = emptyList(),
    val todos: List<ExpectedTodo> = emptyList(),
    val forbidden: List<String> = emptyList(),
)

private val expectJson = Json { ignoreUnknownKeys = true }

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] == "-h" || args[0] == "--help") {
        println(USAGE.trim())
        exitProcess(if (args.isEmpty()) 2 else 0)
    }
    val input = File(args[0])
    val title = optionValue(args, "--title")
    val expectFile = optionValue(args, "--expect")?.let(::File)
    val noFeishu = "--no-feishu" in args

    if (!input.exists()) fail("找不到会议文字文件：${input.path}")
    val props = Config.loadProperties(File("secrets.properties"))
    System.getenv("DASHSCOPE_API_KEY")?.let { if (it.isNotBlank()) props["DASHSCOPE_API_KEY"] = it }
    val expected = expectFile?.let {
        if (!it.exists()) fail("找不到标准答案文件：${it.path}")
        expectJson.decodeFromString(Expected.serializer(), it.readText(Charsets.UTF_8))
    }
    val base = Config.fromProperties(props)
    // 对答案时用答案里的“我”的称呼，结果不受个人配置影响
    val cfg = if (expected != null && expected.userNames.isNotEmpty()) base.copy(userNames = expected.userNames) else base
    if (cfg.apiKey.isBlank()) fail("secrets.properties 里还没填 DASHSCOPE_API_KEY（参照 secrets.properties.example）")

    val transcript = input.readText(Charsets.UTF_8)
    println("会议文字：${input.path}（${transcript.length} 字）")
    println("模型：${cfg.model}　我的称呼：${cfg.userNames.ifEmpty { listOf("（未配置）") }.joinToString("、")}")
    println("正在生成纪要…")

    val result = try {
        MinutesPipeline(cfg).generate(transcript, title)
    } catch (e: HuihouException) {
        fail(e.message ?: "生成失败")
    }
    val m = result.minutes

    println()
    println("用时：${"%.1f".format(result.elapsedMs / 1000.0)} 秒")
    println()
    println("── 眼镜上依次看到 ──")
    fun lens(l: huihou.core.Lens) = if (l.sub.isEmpty()) l.main else "${l.main}　｜小字：${l.sub}"
    println("通知：${lens(GlassesText.notice(m, cfg.hasUser))}")
    val items = GlassesText.items(m, cfg.hasUser)
    items.forEachIndexed { i, t ->
        println("单击：${lens(GlassesText.todo(t, i, items.size))}")
        println("　再单击：${lens(GlassesText.quote(t))}")
    }
    println()
    println("── 纪要 ──")
    println("标题：${m.title}")
    m.conclusions.forEach { println("结论：$it") }
    m.todos.forEach { println("待办：${if (it.mine) "【我】" else ""}${it.owner} · ${it.task}${if (it.due.isNotBlank()) " · ${it.due}" else ""}　原话（${it.time}）：${it.quote}") }
    m.openQuestions.forEach { println("没定：$it") }
    if (result.dropped.isNotEmpty()) {
        println()
        println("── 核对时丢掉的（模型给了但站不住） ──")
        result.dropped.forEach { println("丢掉：${it.todo.owner} · ${it.todo.task}　原因：${it.reason}　模型给的原话：${it.todo.quote}") }
    }

    val outDir = File("build/out").apply { mkdirs() }
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val outFile = File(outDir, "minutes-$stamp.json")
    outFile.writeText(MinutesParser.toJson(m), Charsets.UTF_8)
    println()
    println("纪要 JSON 已保存：${outFile.path}")

    var feishuOk: Boolean? = null
    if (!noFeishu) {
        if (cfg.feishuWebhook.isBlank()) {
            println("飞书：没配置 FEISHU_WEBHOOK，跳过")
        } else {
            feishuOk = try {
                val footer = "AI 眼镜 · 会后纪要　只存文字，不存录音　${LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))}"
                FeishuClient().send(cfg.feishuWebhook, cfg.feishuSecret, FeishuCard.build(m, cfg.hasUser, footer))
                println("飞书：已发送，去手机上看卡片")
                true
            } catch (e: HuihouException) {
                println("飞书：发送失败　${e.message}")
                false
            }
        }
    }

    if (expected != null) {
        val pass = check(m, expected, result.elapsedMs, feishuOk)
        exitProcess(if (pass) 0 else 1)
    }
}

/** 按验收标准对答案：漏 ≤ 1 条、多出 0 条、“不用记”的内容 0 次出现、用时 ≤ 60 秒、“我的”标记正确。 */
private fun check(m: Minutes, exp: Expected, elapsedMs: Long, feishuOk: Boolean?): Boolean {
    val actual = m.todos.toMutableList()
    val matched = mutableListOf<Pair<ExpectedTodo, huihou.core.Todo>>()
    val missed = mutableListOf<ExpectedTodo>()
    // 先对必有的，再对可有可无的；每条答案挑关键词命中最多的那条
    for (e in exp.todos.sortedBy { it.optional }) {
        val hit = actual
            .filter { a -> Verifier.isUser(a.owner, listOf(e.owner)) }
            .map { a -> a to e.keywords.count { k -> a.task.contains(k) || a.quote.contains(k) } }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first
        when {
            hit != null -> { matched += e to hit; actual.remove(hit) }
            !e.optional -> missed += e
        }
    }
    val extra = actual
    val mineWrong = matched.filter { (e, a) -> e.mine != null && e.mine != a.mine }
    val dump = MinutesParser.toJson(m)
    val forbiddenHits = exp.forbidden.filter { dump.contains(it) }

    println()
    println("── 对答案 ──")
    val required = exp.todos.count { !it.optional }
    val optional = exp.todos.size - required
    println("标准答案必有 $required 条${if (optional > 0) "、可有可无 $optional 条" else ""}，抽中 ${matched.size} 条，漏 ${missed.size} 条，多出 ${extra.size} 条")
    missed.forEach { println("漏掉：${it.owner} · ${it.keywords.joinToString("/")}") }
    extra.forEach { println("多出：${it.owner} · ${it.task}　原话：${it.quote}") }
    mineWrong.forEach { (e, a) -> println("“我的”标错：${a.owner} · ${a.task}　应为 ${e.mine}") }
    forbiddenHits.forEach { println("“不用记”的内容出现了：$it") }

    val rows = listOf(
        Triple("速度", elapsedMs <= 60_000, "${"%.1f".format(elapsedMs / 1000.0)} 秒（≤ 60 秒）"),
        Triple("待办漏抽", missed.size <= 1, "${missed.size} 条（≤ 1 条）"),
        Triple("凭空多出", extra.isEmpty(), "${extra.size} 条（应为 0）"),
        Triple("我的待办", mineWrong.isEmpty(), "标错 ${mineWrong.size} 条（应为 0）"),
        Triple("不用记", forbiddenHits.isEmpty(), "出现 ${forbiddenHits.size} 次（应为 0）"),
        Triple("有据可查", m.todos.all { it.quote.isNotBlank() }, "每条待办都带原话"),
    ) + (if (feishuOk == null) emptyList() else listOf(Triple("飞书", feishuOk, if (feishuOk) "已送达" else "发送失败")))
    println()
    rows.forEach { (name, ok, detail) -> println("${if (ok) "✓" else "✗"} $name：$detail") }
    val pass = rows.all { it.second }
    println(if (pass) "验收通过" else "验收未通过")
    return pass
}

private fun optionValue(args: Array<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}

private fun fail(msg: String): Nothing {
    System.err.println(msg)
    exitProcess(2)
}
