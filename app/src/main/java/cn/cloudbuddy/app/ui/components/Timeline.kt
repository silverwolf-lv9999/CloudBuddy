package cn.cloudbuddy.app.ui.components

import cn.cloudbuddy.app.data.ChatMessage
import cn.cloudbuddy.app.data.ChatRole
import cn.cloudbuddy.app.data.ToolState

/**
 * 对话的渲染项。
 *
 * 一轮对话里，用户提问和最终回答始终可见；中间的思考、工具调用、过程叙述
 * 收进 [Steps]，默认折叠成一行摘要，点开才展开明细。
 *
 * 这套「分组 + 摘要 + 默认折叠」的粒度参考 AgentsAnywhere 安卓端的
 * `groupTimelineMessages()` / `ToolRunGroup`：它折叠的是过程事件，不是正文。
 */
sealed interface ChatItem {
    val key: String

    data class Single(val message: ChatMessage) : ChatItem {
        override val key: String = "msg:${message.id}"
    }

    data class Steps(val turn: Int, val steps: List<ChatMessage>) : ChatItem {
        override val key: String = "steps:$turn"
    }
}

/**
 * 把消息列表投影成渲染项。
 *
 * 规则：
 * 1. 用户消息永远是独立项（这是「我发的消息」，任何折叠模式下都要看得见）；
 * 2. 同一轮里**最后一条非空助手消息**是这一轮的最终回答，独立显示；
 * 3. 该轮其余消息（思考、工具调用、更早的助手叙述）合成一个 [ChatItem.Steps]；
 * 4. 还没有最终回答的轮次（正在执行）整轮都是步骤，摘要行会显示进度。
 */
fun projectTimeline(messages: List<ChatMessage>): List<ChatItem> {
    if (messages.isEmpty()) return emptyList()
    val items = mutableListOf<ChatItem>()
    // 空白助手消息（流式刚起头的那一帧、或云端补的空分片）不占位，也不进步骤组。
    val visible = messages.filterNot { it.role == ChatRole.ASSISTANT && it.text.isBlank() }
    for ((_, turnMessages) in visible.groupBy { it.turn }) {
        val finalIndex = turnMessages.indexOfLast { it.isFinalAnswer() }
        var stepsEmitted = false
        turnMessages.forEachIndexed { index, message ->
            when {
                message.role == ChatRole.USER -> items += ChatItem.Single(message)
                index == finalIndex -> items += ChatItem.Single(message)
                stepsEmitted -> Unit
                else -> {
                    // 步骤组固定插在「本轮第一条过程消息」的位置，保持阅读顺序。
                    val steps = turnMessages.filterIndexed { i, m ->
                        i != finalIndex && m.role != ChatRole.USER
                    }
                    items += ChatItem.Steps(message.turn, steps)
                    stepsEmitted = true
                }
            }
        }
    }
    return items
}

/** 这一轮的回答是否已经落地：助手输出且正文非空。流式中的那条也算，用户要看着它长出来。 */
private fun ChatMessage.isFinalAnswer(): Boolean =
    role == ChatRole.ASSISTANT && text.isNotBlank()

/**
 * 时间线的可视窗口。
 *
 * 长会话一次性铺开几千条消息，Compose 首次布局就要测量全部项，打开会话会明显
 * 卡顿。这里按「轮」截取尾部窗口：默认只渲染最近若干轮，往上按需展开——
 * 对用户来说等价于「不一次性加载所有消息」。
 */
data class TimelineWindow(
    val items: List<ChatItem>,
    /** 被挡在上面的轮数。 */
    val hiddenTurns: Int,
    /** 被挡在上面的消息条数（含步骤）。 */
    val hiddenMessages: Int
) {
    val hasMore: Boolean get() = hiddenTurns > 0
}

/**
 * 取时间线尾部窗口。[turns] <= 0 表示全部渲染。
 *
 * 先在消息层面裁剪再投影：一轮的步骤组必须完整，按条裁剪会把折叠组切碎。
 */
fun windowTimeline(messages: List<ChatMessage>, turns: Int): TimelineWindow {
    if (messages.isEmpty()) return TimelineWindow(emptyList(), 0, 0)
    val visible = messages.filterNot { it.role == ChatRole.ASSISTANT && it.text.isBlank() }
    val order = visible.map { it.turn }.distinct()
    if (turns <= 0 || order.size <= turns) {
        return TimelineWindow(projectTimeline(visible), 0, 0)
    }
    val keepFrom = order[order.size - turns]
    val head = visible.filter { it.turn < keepFrom }
    val tail = visible.filter { it.turn >= keepFrom }
    return TimelineWindow(
        items = projectTimeline(tail),
        hiddenTurns = order.count { it < keepFrom },
        hiddenMessages = head.size
    )
}

/** 摘要行文案，例如「5 个步骤 · 3 次工具调用 · 2 段思考」。 */
fun stepsSummary(steps: List<ChatMessage>): String {
    if (steps.isEmpty()) return "过程"
    val tools = steps.count { it.role == ChatRole.TOOL }
    val thoughts = steps.count { it.role == ChatRole.THOUGHT }
    val notes = steps.count { it.role == ChatRole.ASSISTANT }
    val parts = buildList {
        add("${steps.size} 个步骤")
        if (tools > 0) add("$tools 次工具调用")
        if (thoughts > 0) add("$thoughts 段思考")
        if (notes > 0) add("$notes 段过程说明")
    }
    return parts.joinToString(" · ")
}

/** 组内是否有还在跑的工具调用——摘要行据此显示转圈而不是静态图标。 */
fun stepsRunning(steps: List<ChatMessage>): Boolean =
    steps.any { it.role == ChatRole.TOOL && it.toolState == ToolState.RUNNING }

/** 组内是否有失败的工具调用——摘要行据此把颜色改成错误色，避免失败被折叠藏起来。 */
fun stepsFailed(steps: List<ChatMessage>): Boolean =
    steps.any { it.role == ChatRole.TOOL && it.toolState == ToolState.FAILED }
