package huihou.core

object Prompt {

    fun system(userNames: List<String>): String {
        val mineRule = if (userNames.isEmpty()) {
            "mine：一律填 false。"
        } else {
            val names = userNames.joinToString("、") { "“$it”" }
            "mine：负责人是 $names 中的任何一个时填 true（包括被这样称呼后被点名交代，或其本人答应要做），否则填 false。"
        }
        return """
你是会议纪要助手。输入是一场会议的文字记录，可能带时间和说话人，也可能不带。
请只输出一个 JSON 对象，格式如下：
{"title": "", "conclusions": [""], "todos": [{"owner": "", "task": "", "due": "", "quote": "", "time": "", "mine": false}], "open_questions": [""]}

规则：
1. title：会议名称，不超过 8 个字，从内容推断，例如“评审会”“周例会”。
2. conclusions：会上已经定下来的结论，每条一句话，不超过 30 字。没有就给空数组。
3. todos：只收会上明确分配给某人、或某人明确答应要做的事。只是讨论、建议、“可以考虑”、“再想想”的不算。
   - owner：负责人，用记录里的称呼。说话人自己答应的（如“我来”“我负责”“我周五前给”），owner 填说话人。看不出是谁就填“待确认”。
   - task：做什么，动词开头，不超过 20 字。不要在 task 里写时间，时间只写在 due。
   - due：什么时候完成，用原话里的说法，例如“周五前”“下周三”“明天”。没说就填空字符串。
   - quote：能证明这条待办的原话，必须从记录里逐字摘抄，不超过 60 字，不得改写、不得拼接。
   - time：这句原话在记录里的时间点，记录里没有时间就填空字符串。
   - $mineRule
4. open_questions：会上提出但没有定下来、需要后续确认的事，每条一句话。
5. 有人说“这个不用记”“这段别记”“不用写进纪要”之类的话时，相关内容不得出现在任何字段里。
6. 只能依据记录内容，不得编造记录里没有的人、事、时间。
7. 只输出 JSON，不要解释，不要 markdown。
""".trim()
    }

    fun user(transcript: String, titleHint: String?): String = buildString {
        if (!titleHint.isNullOrBlank()) append("会议名称提示：").append(titleHint.trim()).append('\n')
        append("会议文字记录如下：\n<<<\n")
        append(transcript.trim())
        append("\n>>>")
    }
}
