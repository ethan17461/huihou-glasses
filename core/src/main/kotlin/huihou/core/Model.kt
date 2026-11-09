package huihou.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 一条待办。quote 必须是会议文字里的原话，用来“有据可查”。 */
@Serializable
data class Todo(
    val owner: String = "",
    val task: String = "",
    val due: String = "",
    val quote: String = "",
    val time: String = "",
    val mine: Boolean = false,
    /** 原话是谁说的（从会议文字那一行解析出来） */
    val speaker: String = "",
)

/** 一场会的纪要。 */
@Serializable
data class Minutes(
    val title: String = "",
    val conclusions: List<String> = emptyList(),
    val todos: List<Todo> = emptyList(),
    @SerialName("open_questions") val openQuestions: List<String> = emptyList(),
) {
    val myTodos: List<Todo> get() = todos.filter { it.mine }
    val otherTodos: List<Todo> get() = todos.filterNot { it.mine }
}

class HuihouException(message: String, cause: Throwable? = null) : Exception(message, cause)
