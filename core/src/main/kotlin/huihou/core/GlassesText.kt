package huihou.core

/** 镜片上的一次提示：主行（不超过 15 个字）+ 最多一行小字。 */
data class Lens(val main: String, val sub: String = "")

/**
 * 眼镜上的所有文案。规则来自需求文档第七章“眼镜端显示规范”：
 * 主行不超过 15 个字，最多再加一行小字；蓝色是提醒和答案，橙色是答应别人的事。
 */
object GlassesText {

    const val MAIN_MAX = 15

    /** 眼镜上要翻看的待办：配了名字就只看“我的”，没配就看全部。 */
    fun items(m: Minutes, hasUser: Boolean): List<Todo> = if (hasUser) m.myTodos else m.todos

    /** 主行超过 15 个字就截断加省略号。 */
    fun clip(s: String, max: Int = MAIN_MAX): String = if (s.length <= max) s else s.take(max - 1) + "…"

    fun working() = Lens("…", "正在整理纪要")

    fun noMinutes() = Lens("还没有会议纪要")

    fun lastOne() = Lens("已经是最后一条")

    fun sentToFeishu() = Lens("待办已发到飞书")

    fun stillWorking() = Lens("纪要还在生成", "好了再提醒你")

    fun ttsUnavailable() = Lens("不支持中文朗读")

    fun message(text: String) = Lens(clip(text))

    fun error(reason: String) = Lens("纪要没生成", reason)

    fun notice(m: Minutes, hasUser: Boolean, feishuFailed: Boolean = false): Lens {
        val n = items(m, hasUser).size
        val main = when {
            !hasUser -> "纪要已生成 · ${n}条待办"
            n == 0 -> "纪要已生成 · 没有你的待办"
            else -> "纪要已生成 · 你有${n}条待办"
        }
        val title = m.title.ifBlank { "会议" }
        val sub = when {
            feishuFailed -> "$title · 飞书未发出"
            n == 0 -> "$title · 待办已发到飞书"
            else -> "$title · 单击看待办"
        }
        return Lens(clip(main), sub)
    }

    fun todo(t: Todo, index: Int, total: Int): Lens {
        val main = if (t.due.isNotBlank()) "${t.due} · ${t.task}" else t.task
        return Lens(clip(main), "${index + 1}/$total · 单击看原话")
    }

    fun quote(t: Todo): Lens {
        val who = if (t.speaker.isNotBlank()) "${t.speaker}说" else "原话"
        val main = listOf(t.time, who).filter { it.isNotBlank() }.joinToString(" ")
        return Lens(clip(main), "“${t.quote}”")
    }

    /** “念给我听”的朗读稿。 */
    fun speech(m: Minutes, hasUser: Boolean): String {
        val list = items(m, hasUser)
        if (list.isEmpty()) return notice(m, hasUser).main + "。"
        return buildString {
            append(if (hasUser) "你有${list.size}条待办。" else "共${list.size}条待办。")
            list.forEachIndexed { i, t ->
                append("第${i + 1}条，")
                if (!hasUser && t.owner.isNotBlank()) append(t.owner).append("，")
                if (t.due.isNotBlank()) append(t.due).append("，")
                append(t.task).append("。")
            }
        }
    }
}
