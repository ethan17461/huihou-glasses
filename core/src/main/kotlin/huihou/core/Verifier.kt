package huihou.core

data class Dropped(val todo: Todo, val reason: String)

data class Verified(val minutes: Minutes, val dropped: List<Dropped>)

/**
 * 不完全相信大模型：每条待办的原话必须能在会议文字里找到，
 * 落在“这个不用记”范围里的丢掉，“我的待办”再按名字核一遍。
 */
object Verifier {

    private val NO_RECORD = Regex("不用记|别记|不要记|不用写进|别写进|不要写进|不用录|别录")
    private val FORWARD = Regex("下面|接下来|后面|待会|等下|一会儿")
    private val TIME = Regex("""(\d{1,2}:\d{2}(?::\d{2})?)""")
    private val SPEAKER = Regex("""^\s*(?:\[[^\]]*]|\(?\d{1,2}:\d{2}(?::\d{2})?\)?)?\s*([^\s：:\[\]]{1,12})\s*[：:]""")

    fun verify(m: Minutes, transcript: String, userNames: List<String>): Verified {
        val lines = transcript.lines()
        val normLines = lines.map(::norm)
        val blocked = blockedLines(lines)
        val kept = mutableListOf<Todo>()
        val dropped = mutableListOf<Dropped>()

        for (t in m.todos) {
            val q = norm(t.quote)
            if (q.length < 2) {
                dropped += Dropped(t, "没有原话")
                continue
            }
            val idx = locate(q, normLines)
            if (idx == null) {
                dropped += Dropped(t, "原话在会议文字里找不到")
                continue
            }
            if (idx in blocked) {
                dropped += Dropped(t, "属于“不用记”的内容")
                continue
            }
            val mine = if (userNames.isEmpty()) t.mine else (t.mine || isUser(t.owner, userNames))
            val time = t.time.ifBlank { TIME.find(lines[idx])?.groupValues?.get(1) ?: "" }
            val speaker = t.speaker.ifBlank { speaker(lines[idx]) ?: "" }
            kept += t.copy(mine = mine, time = time, speaker = speaker)
        }

        val unique = kept.distinctBy { norm(it.owner) + "|" + norm(it.task) }
        // 我的待办排前面，组内保持会上出现的顺序
        val ordered = unique.filter { it.mine } + unique.filterNot { it.mine }
        return Verified(m.copy(todos = ordered), dropped)
    }

    fun isUser(owner: String, userNames: List<String>): Boolean {
        val o = norm(owner)
        if (o.isEmpty()) return false
        return userNames.any {
            val n = norm(it)
            n.isNotEmpty() && (o.contains(n) || (o.length >= 2 && n.contains(o)))
        }
    }

    /** 返回原话所在的行号；找不到返回 null。 */
    fun locate(q: String, normLines: List<String>): Int? {
        normLines.forEachIndexed { i, l -> if (l.contains(q)) return i }
        // 跨两行的原话
        for (i in 0 until normLines.size - 1) {
            if ((normLines[i] + normLines[i + 1]).contains(q)) return i
        }
        // 模型轻微改字：最长公共子串占原话 60% 以上
        val need = maxOf(6, (q.length * 0.6).toInt())
        var best = -1
        var bestLen = 0
        normLines.forEachIndexed { i, l ->
            val len = longestCommonSubstring(q, l)
            if (len > bestLen) {
                bestLen = len
                best = i
            }
        }
        return if (best >= 0 && bestLen >= need) best else null
    }

    /** “这个不用记”所在行，以及它指的那一行（同一说话人的上一句，或“下面这个不用记”的下一句）。 */
    fun blockedLines(lines: List<String>): Set<Int> {
        val out = mutableSetOf<Int>()
        lines.forEachIndexed { i, line ->
            if (!NO_RECORD.containsMatchIn(line)) return@forEachIndexed
            out += i
            val head = line.substringBefore(NO_RECORD.find(line)!!.value)
            if (FORWARD.containsMatchIn(head)) {
                nextContent(lines, i)?.let { out += it }
            } else {
                val prev = prevContent(lines, i)
                if (prev != null && speaker(lines[prev]) == speaker(line)) out += prev
            }
        }
        return out
    }

    fun speaker(line: String): String? = SPEAKER.find(line)?.groupValues?.get(1)

    fun norm(s: String): String = buildString {
        for (ch in s.lowercase()) {
            if (ch.isWhitespace()) continue
            val type = Character.getType(ch)
            val punct = type == Character.CONNECTOR_PUNCTUATION.toInt() ||
                type == Character.DASH_PUNCTUATION.toInt() ||
                type == Character.START_PUNCTUATION.toInt() ||
                type == Character.END_PUNCTUATION.toInt() ||
                type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
                type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
                type == Character.OTHER_PUNCTUATION.toInt() ||
                type == Character.MATH_SYMBOL.toInt() ||
                type == Character.OTHER_SYMBOL.toInt() ||
                type == Character.MODIFIER_SYMBOL.toInt()
            if (!punct) append(ch)
        }
    }

    fun longestCommonSubstring(a: String, b: String): Int {
        if (a.isEmpty() || b.isEmpty()) return 0
        var prev = IntArray(b.length + 1)
        var cur = IntArray(b.length + 1)
        var best = 0
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else 0
                if (cur[j] > best) best = cur[j]
            }
            val tmp = prev
            prev = cur
            cur = tmp
            cur.fill(0)
        }
        return best
    }

    private fun prevContent(lines: List<String>, i: Int): Int? =
        (i - 1 downTo 0).firstOrNull { lines[it].isNotBlank() }

    private fun nextContent(lines: List<String>, i: Int): Int? =
        (i + 1 until lines.size).firstOrNull { lines[it].isNotBlank() }
}
