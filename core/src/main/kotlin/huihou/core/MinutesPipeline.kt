package huihou.core

data class PipelineResult(
    val minutes: Minutes,
    val dropped: List<Dropped>,
    val elapsedMs: Long,
)

/** 散会后：会议文字 → qwen-plus 出纪要 → 核对原话、标出我的待办。 */
class MinutesPipeline(private val cfg: Config, private val http: Http = UrlConnectionHttp) {

    fun generate(transcript: String, titleHint: String? = null): PipelineResult {
        val text = transcript.trim()
        if (text.length < MIN_CHARS) throw HuihouException("会议文字太少（${text.length} 字）")
        if (text.length > MAX_CHARS) throw HuihouException("会议文字超过 $MAX_CHARS 字，请分段")
        val t0 = System.nanoTime()
        val raw = QwenClient(cfg, http).chatJson(Prompt.system(cfg.userNames), Prompt.user(text, titleHint))
        val parsed = MinutesParser.parse(raw)
        val verified = Verifier.verify(parsed, text, cfg.userNames)
        val elapsed = (System.nanoTime() - t0) / 1_000_000
        return PipelineResult(verified.minutes, verified.dropped, elapsed)
    }

    companion object {
        const val MIN_CHARS = 20
        const val MAX_CHARS = 120_000
    }
}
