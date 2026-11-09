package huihou.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** 阿里云百炼 OpenAI 兼容接口，JSON 模式。 */
class QwenClient(private val cfg: Config, private val http: Http = UrlConnectionHttp) {

    fun buildRequest(system: String, user: String): JsonObject = buildJsonObject {
        put("model", cfg.model)
        putJsonArray("messages") {
            addJsonObject { put("role", "system"); put("content", system) }
            addJsonObject { put("role", "user"); put("content", user) }
        }
        putJsonObject("response_format") { put("type", "json_object") }
        put("temperature", 0.1)
        // JSON 模式只支持非思考模式
        put("enable_thinking", false)
    }

    fun chatJson(system: String, user: String): String {
        if (cfg.apiKey.isBlank()) throw HuihouException("没有配置 DASHSCOPE_API_KEY")
        val url = cfg.baseUrl.trimEnd('/') + "/chat/completions"
        val resp = http.postJson(
            url,
            buildRequest(system, user).toString(),
            mapOf("Authorization" to "Bearer ${cfg.apiKey}"),
            READ_TIMEOUT_MS,
        )
        if (resp.code != 200) {
            throw HuihouException("大模型调用失败（HTTP ${resp.code}）：${errorMessage(resp.body)}")
        }
        val root = runCatching { Json.parseToJsonElement(resp.body).jsonObject }
            .getOrElse { throw HuihouException("大模型返回的不是 JSON：${resp.body.take(200)}") }
        return root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull
            ?: throw HuihouException("大模型返回里没有内容：${resp.body.take(200)}")
    }

    private fun errorMessage(body: String): String {
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return body.take(200)
        val err = obj["error"]
        val nested = (err as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
        return nested ?: obj["message"]?.jsonPrimitive?.contentOrNull ?: body.take(200)
    }

    companion object {
        const val READ_TIMEOUT_MS = 120_000
    }
}
