package huihou.core

import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(val code: Int, val body: String)

/** 只需要 POST JSON。抽成接口方便单元测试替换。 */
fun interface Http {
    fun postJson(url: String, body: String, headers: Map<String, String>, readTimeoutMs: Int): HttpResponse
}

/** JVM 和安卓都自带 HttpURLConnection，不额外引库。 */
object UrlConnectionHttp : Http {
    override fun postJson(url: String, body: String, headers: Map<String, String>, readTimeoutMs: Int): HttpResponse {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return HttpResponse(code, text)
        } finally {
            conn.disconnect()
        }
    }
}
