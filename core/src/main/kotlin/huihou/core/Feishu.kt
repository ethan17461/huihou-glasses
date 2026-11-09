package huihou.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 手机端：飞书群机器人推一张纪要卡片。 */
object FeishuCard {

    /** 飞书限制请求体 20KB，留点余量。 */
    const val MAX_BYTES = 19_000

    fun build(m: Minutes, hasUser: Boolean, footer: String): JsonObject {
        for (level in 0..3) {
            val card = build(m, hasUser, footer, level)
            if (card.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) return card
        }
        return build(m, hasUser, footer, 4)
    }

    /** 卡片只放“我的待办”和“其他人的待办”。level 越高越精简：0 全量；1 其他人的待办不带原话；2 截到 10 条；3 截到 5 条且原话截短；4 只留我的待办。 */
    private fun build(m: Minutes, hasUser: Boolean, footer: String, level: Int): JsonObject {
        val cap = when (level) { 0, 1 -> Int.MAX_VALUE; 2 -> 10; else -> 5 }
        val quoteMax = if (level >= 3) 40 else 80
        val sections = mutableListOf<String>()

        if (hasUser) {
            val mine = m.myTodos
            sections += if (mine.isEmpty()) {
                "**我的待办**\n没有分给你的待办"
            } else {
                "**我的待办（${mine.size}）**\n" + mine.take(cap).mapIndexed { i, t -> todoMd(i, t, withOwner = false, withQuote = true, quoteMax) }.joinToString("\n")
            }
            val others = m.otherTodos
            if (others.isNotEmpty() && level < 4) {
                sections += "**其他人的待办（${others.size}）**\n" +
                    others.take(cap).mapIndexed { i, t -> todoMd(i, t, withOwner = true, withQuote = level == 0, quoteMax) }.joinToString("\n")
            }
        } else {
            sections += if (m.todos.isEmpty()) "**待办**\n会上没有明确的待办" else
                "**待办（${m.todos.size}）**\n" + m.todos.take(cap).mapIndexed { i, t -> todoMd(i, t, withOwner = true, withQuote = level <= 1, quoteMax) }.joinToString("\n")
        }
        // 卡片只放待办；结论、还没定的事放在完整会议纪要里（minutes.json 照样保存）

        return buildJsonObject {
            put("msg_type", "interactive")
            putJsonObject("card") {
                putJsonObject("config") { put("wide_screen_mode", true) }
                putJsonObject("header") {
                    put("template", "blue")
                    putJsonObject("title") {
                        put("tag", "plain_text")
                        put("content", "${m.title.ifBlank { "会议" }} · 会议纪要")
                    }
                }
                putJsonArray("elements") {
                    sections.forEachIndexed { i, md ->
                        if (i > 0) addJsonObject { put("tag", "hr") }
                        addJsonObject {
                            put("tag", "div")
                            putJsonObject("text") { put("tag", "lark_md"); put("content", md) }
                        }
                    }
                    addJsonObject {
                        put("tag", "note")
                        putJsonArray("elements") {
                            addJsonObject { put("tag", "plain_text"); put("content", footer) }
                        }
                    }
                }
            }
        }
    }

    private fun todoMd(i: Int, t: Todo, withOwner: Boolean, withQuote: Boolean, quoteMax: Int): String = buildString {
        append(i + 1).append(". ")
        if (withOwner && t.owner.isNotBlank()) append(esc(t.owner)).append("：")
        append("**").append(esc(t.task)).append("**")
        if (t.due.isNotBlank()) append("　截止：").append(esc(t.due))
        if (withQuote && t.quote.isNotBlank()) {
            val q = if (t.quote.length > quoteMax) t.quote.take(quoteMax) + "…" else t.quote
            append("\n<font color='grey'>")
            if (t.time.isNotBlank()) append(esc(t.time)).append(" ")
            append(if (t.speaker.isNotBlank()) esc(t.speaker) + "：" else "原话：")
            append("“").append(esc(q)).append("”</font>")
        }
    }

    /** lark_md 里的尖括号和星号会被当成标记，换成全角。 */
    private fun esc(s: String) = s.replace("<", "＜").replace(">", "＞").replace("*", "＊")
}

class FeishuClient(private val http: Http = UrlConnectionHttp) {

    fun send(webhook: String, secret: String, payload: JsonObject, nowSec: Long = System.currentTimeMillis() / 1000) {
        if (webhook.isBlank()) throw HuihouException("没有配置 FEISHU_WEBHOOK")
        val body = if (secret.isBlank()) payload else JsonObject(
            payload + mapOf(
                "timestamp" to JsonPrimitive(nowSec.toString()),
                "sign" to JsonPrimitive(sign(nowSec, secret)),
            ),
        )
        val resp = http.postJson(webhook, body.toString(), emptyMap(), 15_000)
        if (resp.code != 200) throw HuihouException("飞书发送失败（HTTP ${resp.code}）：${resp.body.take(200)}")
        val obj = runCatching { Json.parseToJsonElement(resp.body).jsonObject }.getOrNull()
        val code = obj?.get("code")?.jsonPrimitive?.intOrNull ?: obj?.get("StatusCode")?.jsonPrimitive?.intOrNull
        if (code != 0) {
            val msg = obj?.get("msg")?.jsonPrimitive?.contentOrNull ?: resp.body.take(200)
            throw HuihouException("飞书发送失败（code=$code）：$msg")
        }
    }

    companion object {
        /** 飞书签名：以 "timestamp\nsecret" 为密钥，对空字符串做 HmacSHA256，再 Base64。 */
        fun sign(timestampSec: Long, secret: String): String {
            val key = "$timestampSec\n$secret".toByteArray(Charsets.UTF_8)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return Base64.getEncoder().encodeToString(mac.doFinal(ByteArray(0)))
        }
    }
}
