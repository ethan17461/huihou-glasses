package huihou.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerifierTest {

    private val transcript = File("../testdata/review-meeting.txt").readText()
    private val me = listOf("林然")

    private fun verify(vararg todos: Todo) = Verifier.verify(Minutes(title = "评审会", todos = todos.toList()), transcript, me)

    @Test fun keepsExactQuoteAndFillsTime() {
        val v = verify(Todo(owner = "林然", task = "发排期", due = "周五前", quote = "排期我周五前发给大家。"))
        assertEquals(1, v.minutes.todos.size)
        assertEquals("01:44", v.minutes.todos[0].time)
        assertEquals("林然", v.minutes.todos[0].speaker)
        assertTrue(v.minutes.todos[0].mine)
    }

    @Test fun keepsSlightlyRewordedQuote() {
        val v = verify(Todo(owner = "陈露", task = "交接口文档", quote = "陈露，接口文档这周四之前给到测试"))
        assertEquals(1, v.minutes.todos.size, "改了一两个字的原话应该保留")
    }

    @Test fun dropsFabricatedQuote() {
        val v = verify(Todo(owner = "林然", task = "写年终总结", quote = "林然你把年终总结写一下"))
        assertTrue(v.minutes.todos.isEmpty())
        assertEquals("原话在会议文字里找不到", v.dropped.single().reason)
    }

    @Test fun dropsTodoWithoutQuote() {
        val v = verify(Todo(owner = "林然", task = "发排期", quote = ""))
        assertTrue(v.minutes.todos.isEmpty())
    }

    @Test fun dropsNoRecordContent() {
        val v = verify(Todo(owner = "待确认", task = "推进调薪方案", quote = "年底调薪的方案还在走流程"))
        assertTrue(v.minutes.todos.isEmpty())
        assertEquals("属于“不用记”的内容", v.dropped.single().reason)
    }

    @Test fun forwardNoRecordBlocksNextLine() {
        val text = "[00:01] 周总：下面这个不用记\n[00:02] 周总：老王下周离职\n[00:03] 周总：林然你明天交方案"
        val blocked = Verifier.blockedLines(text.lines())
        assertEquals(setOf(0, 1), blocked)
    }

    @Test fun marksMineByNameEvenIfModelForgot() {
        val v = verify(Todo(owner = "林然", task = "整理竞品积分规则", quote = "林然你下周三之前把竞品的积分规则整理一份给我", mine = false))
        assertTrue(v.minutes.todos.single().mine)
    }

    @Test fun myTodosComeFirst() {
        val v = verify(
            Todo(owner = "陈露", task = "交接口文档", quote = "陈露，接口文档这周四前给到测试那边。"),
            Todo(owner = "林然", task = "发排期", quote = "排期我周五前发给大家。"),
        )
        assertEquals(listOf("林然", "陈露"), v.minutes.todos.map { it.owner })
    }

    @Test fun dedupesSameTask() {
        val v = verify(
            Todo(owner = "林然", task = "发排期", quote = "排期我周五前发给大家。"),
            Todo(owner = "林然", task = "发排期", quote = "林然，排期什么时候能出来？"),
        )
        assertEquals(1, v.minutes.todos.size)
    }

    @Test fun isUserMatching() {
        assertTrue(Verifier.isUser("林然", listOf("林然")))
        assertTrue(Verifier.isUser("林然（产品）", listOf("林然")))
        assertTrue(Verifier.isUser("小林", listOf("林然", "小林")))
        assertFalse(Verifier.isUser("林", listOf("林然")))
        assertFalse(Verifier.isUser("陈露", listOf("林然")))
        assertFalse(Verifier.isUser("", listOf("林然")))
    }

    @Test fun speakerParsing() {
        assertEquals("周总", Verifier.speaker("[00:00] 周总：好"))
        assertEquals("林然", Verifier.speaker("01:44 林然: 排期"))
        assertEquals("陈露", Verifier.speaker("陈露：我来吧"))
        assertNull(Verifier.speaker("没有说话人的一句话"))
    }
}

class ParserTest {

    @Test fun parsesFencedJsonWithNullsAndExtraKeys() {
        val raw = """
            好的，以下是纪要：
            ```json
            {"title": "评审会", "conclusions": ["入口放我的页面", " "], "todos": [
              {"owner": "林然", "task": "发排期", "due": null, "quote": "排期我周五前发给大家。", "time": null, "mine": true, "extra": 1},
              {"owner": "x", "task": "  ", "quote": "y"}
            ], "open_questions": null}
            ```
        """.trimIndent()
        val m = MinutesParser.parse(raw)
        assertEquals("评审会", m.title)
        assertEquals(listOf("入口放我的页面"), m.conclusions)
        assertEquals(1, m.todos.size, "task 为空的丢掉")
        assertEquals("", m.todos[0].due)
        assertTrue(m.openQuestions.isEmpty())
    }

    @Test fun stripsDueFromTask() {
        assertEquals("发排期", MinutesParser.stripDue("周五前发排期", "周五前"))
        assertEquals("整理三家竞品积分规则", MinutesParser.stripDue("下周三前整理三家竞品积分规则", "下周三之前"))
        assertEquals("给接口文档到测试", MinutesParser.stripDue("周四前给接口文档到测试", "这周四前"))
        assertEquals("发设计稿", MinutesParser.stripDue("明天发设计稿", "明天"))
        assertEquals("跟进供应商", MinutesParser.stripDue("跟进供应商", ""))
        assertEquals("周五前", MinutesParser.stripDue("周五前", "周五前"), "剥完不剩内容就保留原样")
    }

    @Test fun emptyTitleBecomesDefault() {
        assertEquals("会议", MinutesParser.parse("""{"title": ""}""").title)
    }

    @Test fun rejectsNonJson() {
        assertFailsWith<HuihouException> { MinutesParser.parse("抱歉，我无法处理") }
    }
}

class GlassesTextTest {

    private val m = Minutes(
        title = "评审会",
        todos = listOf(
            Todo(owner = "林然", task = "给王总出排期", due = "周五前", quote = "排期我周五前发给大家", time = "01:44", mine = true),
            Todo(owner = "陈露", task = "交接口文档", due = "周四", quote = "周四没问题", mine = false),
        ),
    )

    @Test fun noticeLines() {
        assertEquals(Lens("纪要已生成 · 你有1条待办", "评审会 · 单击看待办"), GlassesText.notice(m, hasUser = true))
        assertEquals(Lens("纪要已生成 · 2条待办", "评审会 · 单击看待办"), GlassesText.notice(m, hasUser = false))
        assertEquals(Lens("纪要已生成 · 没有你的待办", "评审会 · 待办已发到飞书"), GlassesText.notice(m.copy(todos = m.otherTodos), hasUser = true))
        assertEquals("评审会 · 飞书未发出", GlassesText.notice(m, hasUser = true, feishuFailed = true).sub)
    }

    @Test fun todoAndQuoteLines() {
        assertEquals(Lens("周五前 · 给王总出排期", "1/1 · 单击看原话"), GlassesText.todo(m.todos[0], 0, 1))
        assertEquals(Lens("无截止的事", "2/2 · 单击看原话"), GlassesText.todo(Todo(task = "无截止的事"), 1, 2))
        assertEquals(Lens("01:44 林然说", "“排期我周五前发给大家”"), GlassesText.quote(m.todos[0].copy(speaker = "林然")))
        assertEquals(Lens("原话", "“周四没问题”"), GlassesText.quote(m.todos[1]))
    }

    @Test fun mainLineNeverOver15Chars() {
        val long = Todo(task = "整理三家竞品的积分规则并发给周总和全组", due = "下周三之前")
        val line = GlassesText.todo(long, 0, 1).main
        assertEquals(15, line.length)
        assertTrue(line.endsWith("…"))
        val all = listOf(
            GlassesText.notice(m, true), GlassesText.notice(m, false), GlassesText.working(),
            GlassesText.todo(long, 0, 1), GlassesText.quote(long.copy(time = "01:50", speaker = "周总")),
            GlassesText.error("API Key 不对"), GlassesText.message("这是一句特别特别特别特别特别长的提示"),
        )
        all.forEach { assertTrue(it.main.length <= GlassesText.MAIN_MAX, "主行超过 15 字：${it.main}") }
    }

    @Test fun speechOnlyMine() {
        assertEquals("你有1条待办。第1条，周五前，给王总出排期。", GlassesText.speech(m, hasUser = true))
    }
}

class FeishuTest {

    @Test fun signMatchesOpensslVector() {
        // printf '' | openssl dgst -sha256 -hmac $'1599360473\ntest-secret' -binary | base64
        assertEquals("wSds2BzzFIIGf/WrhUO+NI1q/9j+FRJd3JNHKAq0NZY=", FeishuClient.sign(1599360473, "test-secret"))
    }

    @Test fun cardPutsMyTodosFirstAndEscapes() {
        val m = Minutes(
            title = "评审会",
            conclusions = listOf("入口放<我的>页面"),
            todos = listOf(
                Todo(owner = "林然", task = "发排期", due = "周五前", quote = "排期我周五前发给大家", time = "01:44", mine = true),
                Todo(owner = "陈露", task = "交*接口*文档", mine = false),
            ),
            openQuestions = listOf("积分有效期"),
        )
        val card = FeishuCard.build(m, hasUser = true, footer = "测试")
        val elements = card["card"]!!.jsonObject["elements"]!!.jsonArray
        val first = elements[0].jsonObject["text"]!!.jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(first.startsWith("**我的待办（1）**"), first)
        assertTrue(first.contains("01:44 原话：“排期我周五前发给大家”"))
        val all = card.toString()
        assertTrue(all.contains("交＊接口＊文档"))
        assertFalse(all.contains("结论") || all.contains("入口放"), "卡片只放待办，结论不上卡片")
        assertFalse(all.contains("还没定的事") || all.contains("积分有效期"), "还没定的事不上卡片")
        assertEquals("interactive", card["msg_type"]!!.jsonPrimitive.content)
    }

    @Test fun hugeCardStaysUnder20KB() {
        val big = (1..300).map { Todo(owner = "人$it", task = "任务$it".repeat(5), quote = "很长的原话".repeat(20), mine = it % 3 == 0) }
        val m = Minutes(title = "长会", conclusions = (1..100).map { "结论$it".repeat(10) }, todos = big)
        val bytes = FeishuCard.build(m, hasUser = true, footer = "x").toString().toByteArray().size
        assertTrue(bytes <= FeishuCard.MAX_BYTES, "实际 $bytes 字节")
    }

    @Test fun sendAddsSignatureAndChecksCode() {
        var sent = ""
        val ok = Http { _, body, _, _ -> sent = body; HttpResponse(200, """{"code":0,"msg":"success","data":{}}""") }
        FeishuClient(ok).send("https://open.feishu.cn/open-apis/bot/v2/hook/x", "test-secret", Json.parseToJsonElement("""{"msg_type":"text"}""").jsonObject, 1599360473)
        val obj = Json.parseToJsonElement(sent).jsonObject
        assertEquals("1599360473", obj["timestamp"]!!.jsonPrimitive.content)
        assertEquals("wSds2BzzFIIGf/WrhUO+NI1q/9j+FRJd3JNHKAq0NZY=", obj["sign"]!!.jsonPrimitive.content)

        val bad = Http { _, _, _, _ -> HttpResponse(200, """{"code":19021,"msg":"sign match fail or timestamp is not within one hour from current time"}""") }
        val e = runCatching { FeishuClient(bad).send("https://x", "", Json.parseToJsonElement("{}").jsonObject) }.exceptionOrNull()
        assertTrue(e is HuihouException && e.message!!.contains("19021"))
    }
}

class PipelineTest {

    private val transcript = File("../testdata/review-meeting.txt").readText()

    @Test fun endToEndWithFakeModel() {
        var request = ""
        val modelContent = """{"title":"评审会","conclusions":["积分兑换入口放在我的页面"],"todos":[
            {"owner":"林然","task":"发排期","due":"周五前","quote":"排期我周五前发给大家。","time":"01:44","mine":true},
            {"owner":"林然","task":"写年终总结","due":"","quote":"林然你把年终总结写一下","time":"","mine":true},
            {"owner":"陈露","task":"跟进供应商发货","due":"","quote":"我来吧，我跟供应商对一下。","time":"02:47","mine":false}
        ],"open_questions":["积分有效期是否改成两年"]}"""
        val apiBody = Json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("choices", kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("message", kotlinx.serialization.json.buildJsonObject {
                            put("role", kotlinx.serialization.json.JsonPrimitive("assistant"))
                            put("content", kotlinx.serialization.json.JsonPrimitive(modelContent))
                        })
                    })
                })
            },
        )
        val http = Http { url, body, headers, _ ->
            request = body
            assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", url)
            assertEquals("Bearer k", headers["Authorization"])
            HttpResponse(200, apiBody)
        }
        val res = MinutesPipeline(Config(apiKey = "k", userNames = listOf("林然")), http).generate(transcript)

        val req = Json.parseToJsonElement(request).jsonObject
        assertEquals("json_object", req["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertTrue(req.toString().contains("JSON"), "JSON 模式要求提示词里出现 JSON")
        assertEquals(listOf("发排期", "跟进供应商发货"), res.minutes.todos.map { it.task })
        assertEquals(1, res.dropped.size, "编造的待办被丢掉")
        assertEquals("纪要已生成 · 你有1条待办", GlassesText.notice(res.minutes, hasUser = true).main)
        assertEquals("林然", res.minutes.todos[0].speaker, "原话是谁说的从会议文字里解析")
    }

    @Test fun reportsApiError() {
        val http = Http { _, _, _, _ -> HttpResponse(401, """{"error":{"message":"Incorrect API key provided."}}""") }
        val e = runCatching { MinutesPipeline(Config(apiKey = "bad"), http).generate(transcript) }.exceptionOrNull()
        assertTrue(e is HuihouException && e.message!!.contains("401") && e.message!!.contains("Incorrect API key"), e?.message)
    }

    @Test fun rejectsTooShort() {
        val e = runCatching { MinutesPipeline(Config(apiKey = "k")).generate("太短") }.exceptionOrNull()
        assertTrue(e is HuihouException)
    }
}
