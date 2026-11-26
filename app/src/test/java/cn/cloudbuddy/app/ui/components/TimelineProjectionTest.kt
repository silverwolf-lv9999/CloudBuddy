package cn.cloudbuddy.app.ui.components

import cn.cloudbuddy.app.data.ChatMessage
import cn.cloudbuddy.app.data.ChatRole
import cn.cloudbuddy.app.data.FoldMode
import cn.cloudbuddy.app.data.ToolState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折叠的是「中间步骤」，不是正文：
 * 投影后只剩用户提问 + 本轮最终回答，其余合并进一个可展开的组。
 */
class TimelineProjectionTest {

    private fun msg(
        id: String,
        role: ChatRole,
        text: String = "x",
        turn: Int = 0,
        toolState: ToolState = ToolState.NONE,
        streaming: Boolean = false
    ) = ChatMessage(id, role, text, toolState, streaming, turn)

    @Test
    fun `user and final answer stay visible while steps are grouped`() {
        val messages = listOf(
            msg("u0", ChatRole.USER, "帮我做个页面", turn = 0),
            msg("t0", ChatRole.THOUGHT, "先看目录", turn = 0),
            msg("c0", ChatRole.TOOL, "Read", turn = 0, toolState = ToolState.DONE),
            msg("a0", ChatRole.ASSISTANT, "中间说明", turn = 0),
            msg("a1", ChatRole.ASSISTANT, "这是最终回答", turn = 0)
        )

        val items = projectTimeline(messages)

        assertEquals(3, items.size)
        assertTrue(items[0] is ChatItem.Single)
        assertEquals("u0", (items[0] as ChatItem.Single).message.id)

        val steps = items[1] as ChatItem.Steps
        assertEquals(listOf("t0", "c0", "a0"), steps.steps.map { it.id })

        assertTrue(items[2] is ChatItem.Single)
        assertEquals("a1", (items[2] as ChatItem.Single).message.id)
    }

    @Test
    fun `each turn is grouped on its own`() {
        val messages = listOf(
            msg("u0", ChatRole.USER, "第一个问题", turn = 0),
            msg("c0", ChatRole.TOOL, "Bash", turn = 0),
            msg("a0", ChatRole.ASSISTANT, "第一个回答", turn = 0),
            msg("u1", ChatRole.USER, "第二个问题", turn = 1),
            msg("c1", ChatRole.TOOL, "Read", turn = 1),
            msg("a1", ChatRole.ASSISTANT, "第二个回答", turn = 1)
        )

        val items = projectTimeline(messages)

        assertEquals(6, items.size)
        assertEquals(
            listOf("msg:u0", "steps:0", "msg:a0", "msg:u1", "steps:1", "msg:a1"),
            items.map { it.key }
        )
        assertEquals(
            listOf("c0"),
            (items[1] as ChatItem.Steps).steps.map { it.id }
        )
        assertEquals(
            listOf("c1"),
            (items[4] as ChatItem.Steps).steps.map { it.id }
        )
    }

    @Test
    fun `turn without a final answer is all steps`() {
        val messages = listOf(
            msg("u0", ChatRole.USER, "开始", turn = 0),
            msg("t0", ChatRole.THOUGHT, "思考中", turn = 0),
            msg("c0", ChatRole.TOOL, "Bash", turn = 0, toolState = ToolState.RUNNING)
        )

        val items = projectTimeline(messages)

        assertEquals(2, items.size)
        assertTrue(items[0] is ChatItem.Single)
        val steps = items[1] as ChatItem.Steps
        assertEquals(listOf("t0", "c0"), steps.steps.map { it.id })
        assertTrue(stepsRunning(steps.steps))
    }

    @Test
    fun `streaming answer already counts as the final one`() {
        val messages = listOf(
            msg("u0", ChatRole.USER, "写点东西", turn = 0),
            msg("c0", ChatRole.TOOL, "Write", turn = 0),
            msg("a0", ChatRole.ASSISTANT, "正在输出", turn = 0, streaming = true)
        )

        val items = projectTimeline(messages)

        assertTrue(items.last() is ChatItem.Single)
        assertEquals("a0", (items.last() as ChatItem.Single).message.id)
    }

    @Test
    fun `user message is never swallowed by a step group`() {
        val messages = listOf(
            msg("u0", ChatRole.USER, "一", turn = 0),
            msg("u1", ChatRole.USER, "二", turn = 0)
        )

        val items = projectTimeline(messages)

        assertEquals(2, items.size)
        assertTrue(items.all { it is ChatItem.Single })
    }

    @Test
    fun `blank assistant text does not count as the final answer`() {
        val messages = listOf(
            msg("u0", ChatRole.USER, "问题", turn = 0),
            msg("a0", ChatRole.ASSISTANT, "真的回答", turn = 0),
            msg("a1", ChatRole.ASSISTANT, "   ", turn = 0)
        )

        val items = projectTimeline(messages)

        // 空白消息既不占位，也不该把真正的回答挤进步骤组。
        assertEquals(2, items.size)
        assertTrue(items.last() is ChatItem.Single)
        assertEquals("a0", (items.last() as ChatItem.Single).message.id)
    }

    @Test
    fun `summary counts tools thoughts and notes`() {
        val steps = listOf(
            msg("t0", ChatRole.THOUGHT, turn = 0),
            msg("c0", ChatRole.TOOL, turn = 0),
            msg("c1", ChatRole.TOOL, turn = 0),
            msg("a0", ChatRole.ASSISTANT, turn = 0)
        )
        assertEquals("4 个步骤 · 2 次工具调用 · 1 段思考 · 1 段过程说明", stepsSummary(steps))
    }

    @Test
    fun `failed tool is surfaced even when collapsed`() {
        val steps = listOf(msg("c0", ChatRole.TOOL, turn = 0, toolState = ToolState.FAILED))
        assertTrue(stepsFailed(steps))
        assertEquals("1 个步骤 · 1 次工具调用", stepsSummary(steps))
    }

    /**
     * 折叠状态下组内工具行不可见，摘要必须自己说清「这轮读了什么、跑了什么」。
     * 认不出类别的（标题是文件名之类）只计入总数，不进括号，免得摘要里出现「工具 2」。
     */
    @Test
    fun `整轮折叠只有最终回答带底栏标记`() {
        val messages = listOf(
            msg("u0", ChatRole.USER, "问题", turn = 0),
            msg("c0", ChatRole.TOOL, "读取文件 a.txt", turn = 0),
            msg("a0", ChatRole.ASSISTANT, "过程中说一句", turn = 0),
            msg("a1", ChatRole.ASSISTANT, "最终回答", turn = 0)
        )

        val items = projectTimeline(messages, FoldMode.WHOLE)
        val singles = items.filterIsInstance<ChatItem.Single>()

        // 过程中的段落进了折叠组，只有最后那条正文是独立项且带标记
        assertTrue(singles.none { it.message.id == "a0" })
        assertEquals(listOf("a1"), singles.filter { it.finalAnswer }.map { it.message.id })
        // 用户提问永远不铺底栏
        assertTrue(singles.first { it.message.role == ChatRole.USER }.finalAnswer.not())
    }

    @Test
    fun `只折过程时中间段落独立显示但不带底栏标记`() {
        val messages = listOf(
            msg("u0", ChatRole.USER, "问题", turn = 0),
            msg("c0", ChatRole.TOOL, "运行 npm install", turn = 0),
            msg("a0", ChatRole.ASSISTANT, "先装依赖", turn = 0),
            msg("c1", ChatRole.TOOL, "运行 npm test", turn = 0),
            msg("a1", ChatRole.ASSISTANT, "都通过了", turn = 0)
        )

        val items = projectTimeline(messages, FoldMode.BODY)
        val singles = items.filterIsInstance<ChatItem.Single>()

        // 两段正文都露出来（这是 BODY 模式的意义），但只有最后一段挂底栏
        assertEquals(listOf("u0", "a0", "a1"), singles.map { it.message.id })
        assertEquals(listOf("a1"), singles.filter { it.finalAnswer }.map { it.message.id })
    }

    @Test
    fun `summary names tool kinds by their titles`() {
        val steps = listOf(
            msg("t0", ChatRole.THOUGHT, "想了想", turn = 0),
            msg("c0", ChatRole.TOOL, "读取文件 config.json", turn = 0),
            msg("c1", ChatRole.TOOL, "运行 npm install", turn = 0),
            msg("c2", ChatRole.TOOL, "读取文件 README.md", turn = 0),
            msg("c3", ChatRole.TOOL, "report.md", turn = 0)
        )
        assertEquals(
            "5 个步骤 · 4 次工具调用（读 2 · 运行 1） · 1 段思考",
            stepsSummary(steps)
        )
    }

    @Test
    fun `empty input projects to empty list`() {
        assertTrue(projectTimeline(emptyList()).isEmpty())
    }

    // ---------- 只折思考与工具（FoldMode.BODY） ----------

    @Test
    fun `body mode keeps every paragraph and folds only thoughts and tools`() {
        val messages = listOf(
            msg("u0", ChatRole.USER, "帮我做个页面", turn = 0),
            msg("t0", ChatRole.THOUGHT, "先看目录", turn = 0),
            msg("c0", ChatRole.TOOL, "Read", turn = 0, toolState = ToolState.DONE),
            msg("a0", ChatRole.ASSISTANT, "正文1", turn = 0),
            msg("t1", ChatRole.THOUGHT, "再改样式", turn = 0),
            msg("c1", ChatRole.TOOL, "Write", turn = 0, toolState = ToolState.DONE),
            msg("a1", ChatRole.ASSISTANT, "正文2", turn = 0)
        )

        val items = projectTimeline(messages, FoldMode.BODY)

        // 顺序：提问 → 折叠条 → 正文1 → 折叠条 → 正文2
        assertEquals(5, items.size)
        assertEquals(listOf("msg:u0", "steps:0", "msg:a0", "steps:0:1", "msg:a1"), items.map { it.key })
        val singles = items.filterIsInstance<ChatItem.Single>().map { it.message.id }
        assertEquals(listOf("u0", "a0", "a1"), singles)
        val groups = items.filterIsInstance<ChatItem.Steps>()
        assertEquals(2, groups.size)
        assertEquals(listOf("t0", "c0"), groups[0].steps.map { it.id })
        assertEquals(listOf("t1", "c1"), groups[1].steps.map { it.id })
    }

    @Test
    fun `body mode keeps consecutive paragraphs separate with no step between`() {
        val messages = listOf(
            msg("u0", ChatRole.USER, "讲讲思路", turn = 0),
            msg("a0", ChatRole.ASSISTANT, "第一段", turn = 0),
            msg("a1", ChatRole.ASSISTANT, "第二段", turn = 0)
        )

        val items = projectTimeline(messages, FoldMode.BODY)

        assertEquals(listOf("msg:u0", "msg:a0", "msg:a1"), items.map { it.key })
        assertTrue(items.none { it is ChatItem.Steps })
    }

    @Test
    fun `body mode still folds a trailing run without final answer`() {
        val messages = listOf(
            msg("u0", ChatRole.USER, "跑一下", turn = 0),
            msg("t0", ChatRole.THOUGHT, "在想", turn = 0),
            msg("c0", ChatRole.TOOL, "Bash", turn = 0, toolState = ToolState.RUNNING)
        )

        val items = projectTimeline(messages, FoldMode.BODY)

        assertEquals(listOf("msg:u0", "steps:0"), items.map { it.key })
        val group = items.last() as ChatItem.Steps
        assertTrue(stepsRunning(group.steps))
    }

    @Test
    fun `whole mode collapses the same turn into a single group`() {
        val messages = listOf(
            msg("u0", ChatRole.USER, "帮我做个页面", turn = 0),
            msg("t0", ChatRole.THOUGHT, "先看目录", turn = 0),
            msg("a0", ChatRole.ASSISTANT, "正文1", turn = 0),
            msg("t1", ChatRole.THOUGHT, "再改样式", turn = 0),
            msg("a1", ChatRole.ASSISTANT, "正文2", turn = 0)
        )

        val items = projectTimeline(messages, FoldMode.WHOLE)

        // 整轮折叠：中间那段正文也被收进折叠条，只剩最后一条回答。
        assertEquals(listOf("msg:u0", "steps:0", "msg:a1"), items.map { it.key })
        assertEquals(3, (items[1] as ChatItem.Steps).steps.size)
    }

    // ---------- 长会话开窗 ----------

    /** 三轮对话，每轮三条消息（提问 + 工具 + 回答）。 */
    private fun threeTurns(): List<ChatMessage> = (0..2).flatMap { turn ->
        listOf(
            msg("u$turn", ChatRole.USER, "问题$turn", turn = turn),
            msg("c$turn", ChatRole.TOOL, "Tool$turn", turn = turn, toolState = ToolState.DONE),
            msg("a$turn", ChatRole.ASSISTANT, "回答$turn", turn = turn)
        )
    }

    @Test
    fun `window keeps only the last turns`() {
        val window = windowTimeline(threeTurns(), turns = 2)

        assertEquals(1, window.hiddenTurns)
        assertEquals(3, window.hiddenMessages)
        assertTrue(window.hasMore)
        assertEquals(listOf("msg:u1", "steps:1", "msg:a1", "msg:u2", "steps:2", "msg:a2"),
            window.items.map { it.key })
    }

    @Test
    fun `window never splits a step group`() {
        val window = windowTimeline(threeTurns(), turns = 1)

        val steps = window.items.filterIsInstance<ChatItem.Steps>()
        assertEquals(listOf(2), steps.map { it.turn })
        assertEquals(listOf("c2"), steps.single().steps.map { it.id })
    }

    @Test
    fun `zero turns means everything stays visible`() {
        val window = windowTimeline(threeTurns(), turns = 0)

        assertEquals(0, window.hiddenTurns)
        assertEquals(0, window.hiddenMessages)
        assertTrue(!window.hasMore)
        assertEquals(9, window.items.size)
    }

    @Test
    fun `short conversation is not windowed`() {
        val window = windowTimeline(threeTurns(), turns = 50)

        assertEquals(0, window.hiddenTurns)
        assertEquals(9, window.items.size)
    }

    @Test
    fun `blank assistant frames do not count as hidden messages`() {
        val messages = threeTurns() + msg("blank", ChatRole.ASSISTANT, " ", turn = 2)
        val window = windowTimeline(messages, turns = 2)

        // 空白分片本来就不渲染，也不该被算进「还有 N 条未加载」。
        assertEquals(3, window.hiddenMessages)
    }

    @Test
    fun `steps page keeps the tail and reports what is hidden`() {
        val steps = (0 until 30).map { msg("t$it", ChatRole.TOOL, "Read $it", toolState = ToolState.DONE) }

        val page = pageSteps(steps, showAll = false, size = 24)

        assertEquals(24, page.rows.size)
        assertEquals(6, page.hidden)
        assertTrue(page.hasMore)
        // 铺的是最近的一页：最新的那条必须在，最旧的那条被压住。
        assertEquals("t29", page.rows.last().id)
        assertEquals("t6", page.rows.first().id)
    }

    @Test
    fun `short steps list is not paged`() {
        val steps = (0 until 5).map { msg("t$it", ChatRole.TOOL, "Read $it", toolState = ToolState.DONE) }

        val page = pageSteps(steps, showAll = false, size = 24)

        assertEquals(5, page.rows.size)
        assertEquals(0, page.hidden)
        assertTrue(!page.hasMore)
    }

    @Test
    fun `asking for all steps drops the page boundary`() {
        val steps = (0 until 30).map { msg("t$it", ChatRole.TOOL, "Read $it", toolState = ToolState.DONE) }

        val page = pageSteps(steps, showAll = true, size = 24)

        assertEquals(30, page.rows.size)
        assertEquals(0, page.hidden)
    }
}
