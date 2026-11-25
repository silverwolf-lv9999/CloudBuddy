package cn.cloudbuddy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 消息流 → 模型上下文的投影。
 *
 * 落盘的是完整消息流（思考、工具都在里面），回放给模型的却只能是「提问 + 回答 +
 * 上一轮用过哪些工具」。这段映射错了不会抛异常，只会表现为「模型失忆」或者
 * 「模型把自己的一句报错当成上轮回答」，所以逐条钉住。
 */
class TranscriptMemoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = SessionStore(tmp.newFolder())

    private fun msg(role: ChatRole, text: String, failed: Boolean = false) =
        StoredMessage(id = "m-" + text.hashCode(), role = role, text = text, failed = failed)

    @Test
    fun `思考不进上下文，回答与提问都在`() {
        val turns = Transcript.turnsOf(
            listOf(
                msg(ChatRole.USER, "看下这个仓库"),
                msg(ChatRole.THOUGHT, "先列目录再读 README"),
                msg(ChatRole.ASSISTANT, "一共三个文件"),
            )
        )

        assertEquals(2, turns.size)
        assertEquals(listOf(true, false), turns.map { it.fromUser })
        assertTrue(turns.none { it.text.contains("先列目录") })
    }

    @Test
    fun `失败记录不喂给模型`() {
        // 否则模型会把「调用自定义模型失败：timeout」当成自己上一轮说过的话。
        val turns = Transcript.turnsOf(
            listOf(
                msg(ChatRole.USER, "看下这个仓库"),
                msg(ChatRole.ASSISTANT, "调用自定义模型失败：timeout", failed = true),
            )
        )

        assertEquals(1, turns.size)
        assertTrue(turns.single().fromUser)
    }

    @Test
    fun `工具调用折成当轮回答的 note`() {
        val turns = Transcript.turnsOf(
            listOf(
                msg(ChatRole.USER, "看下 README"),
                msg(ChatRole.TOOL, "run_shell: wc -l README.md"),
                msg(ChatRole.TOOL, "read_file: README.md"),
                msg(ChatRole.ASSISTANT, "120 行"),
            )
        )

        assertEquals(2, turns.size)
        assertEquals("run_shell: wc -l README.md；read_file: README.md", turns[1].note)
    }

    @Test
    fun `工具摘要不会串到下一轮`() {
        val turns = Transcript.turnsOf(
            listOf(
                msg(ChatRole.USER, "第一问"),
                msg(ChatRole.TOOL, "run_shell: ls"),
                msg(ChatRole.ASSISTANT, "第一答"),
                msg(ChatRole.USER, "第二问"),
                msg(ChatRole.ASSISTANT, "第二答"),
            )
        )

        assertEquals(4, turns.size)
        assertEquals("run_shell: ls", turns[1].note)
        assertNull("上一轮的工具不该挂到这一轮上", turns[3].note)
    }

    @Test
    fun `旧格式数据第一次读时搬到消息流`() {
        val store = store()
        val legacy = FakeLegacy(
            listOf(
                AgentTurn(fromUser = true, text = "老问题"),
                AgentTurn(fromUser = false, text = "老回答", note = "run_shell: ls"),
            )
        )
        val memory = TranscriptMemory(store, legacy)

        val turns = memory.load("c1")
        assertEquals(listOf("老问题", "老回答"), turns.map { it.text })
        // 搬迁要真的写进消息流，否则下次打开又要搬一遍（同时会丢掉界面上的过程记录）。
        assertEquals(
            listOf(ChatRole.USER, ChatRole.TOOL, ChatRole.ASSISTANT),
            store.messages("c1").map { it.role }
        )
        assertEquals(1, legacy.loads)
    }

    @Test
    fun `消息流已有内容时不再用旧存储覆盖`() {
        val store = store()
        store.append("c1", StoredMessage("1", ChatRole.USER, "新格式的提问"))
        val legacy = FakeLegacy(listOf(AgentTurn(true, "老问题")))

        val turns = TranscriptMemory(store, legacy).load("c1")

        assertEquals("新格式的提问", turns.single().text)
        assertEquals(0, legacy.loads)
    }

    @Test
    fun `append 会把提问与回答写进同一条消息流`() {
        val store = store()
        val memory = TranscriptMemory(store)

        memory.append("c1", AgentTurn(true, "提问"))
        memory.append("c1", AgentTurn(false, "回答", note = "run_shell: ls"))
        memory.append("c1", AgentTurn(false, "失败", error = true))

        val stored = store.messages("c1")
        assertEquals(3, stored.size)
        assertEquals(listOf(0, 0, 0), stored.map { it.turn })
        assertTrue(stored.last().failed)
    }

    @Test
    fun `连续两条相同提问只落一条`() {
        val store = store()
        val memory = TranscriptMemory(store)

        memory.append("c1", AgentTurn(true, "看下 README"))
        memory.append("c1", AgentTurn(true, "看下 README"))

        assertEquals(1, store.messages("c1").size)
    }

    @Test
    fun `重放时同一轮的思考与工具归到同一个轮次`() {
        val stored = listOf(
            StoredMessage("1", ChatRole.USER, "第一问", turn = 0),
            StoredMessage("2", ChatRole.THOUGHT, "想了一会儿", turn = 0),
            StoredMessage("3", ChatRole.TOOL, "run_shell · ls", ToolState.DONE, turn = 0),
            StoredMessage("4", ChatRole.ASSISTANT, "第一答", turn = 0),
            StoredMessage("5", ChatRole.USER, "第二问", turn = 1),
            StoredMessage("6", ChatRole.ASSISTANT, "第二答", turn = 1),
        )

        assertEquals(listOf(0, 0, 0, 0, 1, 1), Transcript.replay(stored).map { it.turn })
    }

    @Test
    fun `存储里的旧轮次号是错的时候按消息流纠正`() {
        // 老版本按「当前用户消息条数」算轮次：提问拿到 0、回答拿到 1。
        // 直接用会把这条回答划到下一轮，折叠条里就出现「上一轮的思考 + 下一轮的回答」。
        val stored = listOf(
            StoredMessage("1", ChatRole.USER, "第一问", turn = 0),
            StoredMessage("2", ChatRole.THOUGHT, "想", turn = 0),
            StoredMessage("3", ChatRole.ASSISTANT, "第一答", turn = 1),
            StoredMessage("4", ChatRole.USER, "第二问", turn = 1),
        )

        assertEquals(listOf(0, 0, 0, 1), Transcript.replay(stored).map { it.turn })
    }

    @Test
    fun `重启后重放，思考与工具调用都还在`() {
        val dir = tmp.newFolder()
        val first = SessionStore(dir)
        first.append("c1", StoredMessage("1", ChatRole.USER, "看下这个仓库", turn = 0))
        first.append("c1", StoredMessage("2", ChatRole.THOUGHT, "先列目录", turn = 0))
        first.append("c1", StoredMessage("3", ChatRole.TOOL, "run_shell · ls", ToolState.DONE, turn = 0))
        first.append("c1", StoredMessage("4", ChatRole.ASSISTANT, "一共三个文件", turn = 0))
        first.flush()

        val replayed = Transcript.replay(SessionStore(dir).messages("c1"))

        assertEquals(
            listOf(ChatRole.USER, ChatRole.THOUGHT, ChatRole.TOOL, ChatRole.ASSISTANT),
            replayed.map { it.role }
        )
        assertEquals("先列目录", replayed[1].text)
        assertEquals(ToolState.DONE, replayed[2].toolState)
        assertEquals(listOf(0, 0, 0, 0), replayed.map { it.turn })
    }

    @Test
    fun `失败记录重放时保留 failed 标记`() {
        val stored = listOf(
            StoredMessage("1", ChatRole.USER, "提问", turn = 0),
            StoredMessage("2", ChatRole.ASSISTANT, "调用自定义模型失败：timeout", turn = 0, failed = true),
        )

        assertTrue(Transcript.replay(stored).last().failed)
    }

    /** 旧版本存在 SharedPreferences 里的历史。 */
    private class FakeLegacy(private val turns: List<AgentTurn>) : AgentMemory {
        var loads = 0
        override fun load(conversationId: String): List<AgentTurn> {
            loads++
            return turns
        }

        override fun save(conversationId: String, turns: List<AgentTurn>) = Unit
        override fun clear(conversationId: String) = Unit
    }
}
