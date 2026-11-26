package cn.cloudbuddy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 会话消息流的落盘行为。
 *
 * 这里锁的是「退出去再进来消息没了」那个 bug 的全部边界：写进去的必须原样读回来、
 * 工具调用的状态要跟着更新、进程被杀留下的残局要能收尾、文件写坏了不能连累整个会话。
 */
class SessionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(dir: File = tmp.newFolder(), maxMessages: Int = 400) =
        SessionStore(dir, maxMessages = maxMessages)

    private fun msg(
        id: String,
        role: ChatRole,
        text: String,
        turn: Int = 0,
        state: ToolState = ToolState.NONE,
        failed: Boolean = false,
    ) = StoredMessage(id, role, text, state, turn, at = 1L, failed = failed)

    /** 工具输出要跟着落盘：重进会话后点开工具行，还能看到当时那条命令打印了什么。 */
    @Test
    fun `工具输出跟着消息一起落盘`() {
        val store = store()
        store.append(
            "c1",
            StoredMessage(
                id = "1",
                role = ChatRole.TOOL,
                text = "运行 npm install",
                toolState = ToolState.DONE,
                turn = 0,
                at = 1L,
                detail = "added 120 packages",
            )
        )
        val back = store.messages("c1").single()
        assertEquals("added 120 packages", back.detail)
    }

    @Test
    fun `完整消息流原样读回，思考与工具都不丢`() {
        val store = store()
        store.append("c1", msg("1", ChatRole.USER, "看下这个仓库"))
        store.append("c1", msg("2", ChatRole.THOUGHT, "先列目录"))
        store.append("c1", msg("3", ChatRole.TOOL, "run_shell · ls", state = ToolState.DONE))
        store.append("c1", msg("4", ChatRole.ASSISTANT, "一共三个文件"))

        val back = store.messages("c1")
        assertEquals(listOf(ChatRole.USER, ChatRole.THOUGHT, ChatRole.TOOL, ChatRole.ASSISTANT), back.map { it.role })
        assertEquals("先列目录", back[1].text)
        assertEquals(ToolState.DONE, back[2].toolState)
    }

    @Test
    fun `重启后从文件读回同样的内容`() {
        val dir = tmp.newFolder()
        val first = store(dir)
        first.append("c1", msg("1", ChatRole.USER, "问题一"))
        first.append("c1", msg("2", ChatRole.THOUGHT, "想了想"))
        first.flush()

        // 新实例 = 进程重启：内存缓存没了，只能靠磁盘。
        val second = store(dir)
        val back = second.messages("c1")
        assertEquals(2, back.size)
        assertEquals("想了想", back[1].text)
    }

    @Test
    fun `同一句提问连着写两次只留一条`() {
        // 仓储拿到沙箱前写一次、agent 发请求前再写一次，两条一样的提问会让模型以为用户在催。
        val store = store()
        store.append("c1", msg("1", ChatRole.USER, "看下 README"))
        store.append("c1", msg("2", ChatRole.USER, "看下 README"))

        assertEquals(1, store.messages("c1").size)
    }

    @Test
    fun `工具调用从运行中改成完成后落盘也是完成态`() {
        val dir = tmp.newFolder()
        val store = store(dir)
        store.append("c1", msg("1", ChatRole.USER, "跑一下"))
        store.append("c1", msg("2", ChatRole.TOOL, "run_shell · sleep 3", state = ToolState.RUNNING))

        store.update("c1", "2") { it.copy(toolState = ToolState.DONE) }
        store.flush()

        val back = store(dir).messages("c1")
        assertEquals(ToolState.DONE, back.last().toolState)
    }

    @Test
    fun `进程被杀留下的运行中工具会被收尾成失败`() {
        // 不给 sealInterrupted 的话，重进会话永远看到一条转圈的调用，用户以为它还在跑。
        val store = store()
        store.append("c1", msg("1", ChatRole.USER, "跑个长任务"))
        store.append("c1", msg("2", ChatRole.TOOL, "run_shell · make", state = ToolState.RUNNING))
        store.append("c1", msg("3", ChatRole.ASSISTANT, "好了"))

        assertTrue(store.sealInterrupted("c1"))

        val tool = store.messages("c1").first { it.role == ChatRole.TOOL }
        assertEquals(ToolState.FAILED, tool.toolState)
        assertTrue("要标出它是被打断的", tool.text.endsWith(SessionStore.TOOL_INTERRUPTED))
        // 已经有回答的一轮不该再补说明。
        assertEquals(3, store.messages("c1").size)
    }

    @Test
    fun `提问之后什么都没有时补一条说明`() {
        val store = store()
        store.append("c1", msg("1", ChatRole.USER, "看下这个仓库", turn = 0))

        assertTrue(store.sealInterrupted("c1"))

        val all = store.messages("c1")
        assertEquals(2, all.size)
        assertTrue(all.last().failed)
        assertEquals(ChatRole.ASSISTANT, all.last().role)
        assertTrue(all.last().text.contains("没有跑完"))
    }

    @Test
    fun `重复收尾不会叠加说明`() {
        val store = store()
        store.append("c1", msg("1", ChatRole.USER, "提问"))
        store.sealInterrupted("c1")
        store.sealInterrupted("c1")

        assertEquals(2, store.messages("c1").size)
    }

    @Test
    fun `超长正文截断但保留开头`() {
        val store = store()
        store.append("c1", msg("1", ChatRole.ASSISTANT, "x".repeat(50_000)))
        assertEquals(16_000, store.messages("c1").single().text.length)
    }

    @Test
    fun `条数超出上限时整轮丢弃，且第一条仍是提问`() {
        val store = store(maxMessages = 3)
        store.append("c1", msg("1", ChatRole.USER, "第一问", turn = 0))
        store.append("c1", msg("2", ChatRole.ASSISTANT, "第一答", turn = 0))
        store.append("c1", msg("3", ChatRole.USER, "第二问", turn = 1))
        store.append("c1", msg("4", ChatRole.ASSISTANT, "第二答", turn = 1))

        val all = store.messages("c1")
        // 丢掉的是完整的第一轮，而不是「思考」或「回答」这种半截。
        assertEquals(2, all.size)
        assertEquals(ChatRole.USER, all.first().role)
        assertEquals("第二问", all.first().text)
        assertEquals("第二答", all.last().text)
    }

    @Test
    fun `文件内容坏掉时读成空会话而不是崩溃`() {
        val dir = tmp.newFolder()
        val store = store(dir)
        store.append("c1", msg("1", ChatRole.USER, "提问"))
        store.flush()

        // 模拟写到一半被杀掉留下的半截 JSON。
        dir.listFiles()!!.first { it.name.endsWith(".json") }.writeText("{\"messages\":[{\"r\":\"US")

        assertTrue(store(dir).messages("c1").isEmpty())
    }

    @Test
    fun `clear 只删指定会话`() {
        val store = store()
        store.append("c1", msg("1", ChatRole.USER, "A"))
        store.append("c2", msg("2", ChatRole.USER, "B"))

        store.clear("c1")
        assertTrue(store.messages("c1").isEmpty())
        assertEquals("B", store.messages("c2").single().text)
    }

    /**
     * 回归：clear 删掉文件之后，之前排队中的落盘任务（快照已取、还没写盘）
     * 不许把旧消息写回去。这是真实出现过的竞态——删完会话马上重进，
     * 消息复活。用可控执行器把写任务扣到 clear 之后才放行，确定性复现。
     */
    @Test
    fun `clear 之后还在排队的落盘任务会被作废`() {
        val pending = ArrayDeque<Runnable>()
        val store = SessionStore(
            tmp.newFolder(),
            io = object : java.util.concurrent.AbstractExecutorService() {
                override fun execute(command: Runnable) { pending.add(command) }
                override fun shutdown() = Unit
                override fun shutdownNow() = mutableListOf<Runnable>()
                override fun isShutdown() = false
                override fun isTerminated() = false
                override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit) = false
            },
        )

        store.append("c1", msg("1", ChatRole.USER, "会删掉的消息"))
        assertTrue(pending.isNotEmpty()) // 任务被扣住，还没写盘

        store.clear("c1")
        while (pending.isNotEmpty()) pending.removeFirst().run() // 旧任务带着旧代数，必须作废

        assertTrue(store.messages("c1").isEmpty())
    }

    @Test
    fun `会话 id 含中文与斜杠时也不会串台`() {
        val store = store()
        store.append("会话/一", msg("1", ChatRole.USER, "A"))
        store.append("会话_一", msg("2", ChatRole.USER, "B"))

        assertEquals("A", store.messages("会话/一").single().text)
        assertEquals("B", store.messages("会话_一").single().text)
    }

    @Test
    fun `replace 用导入的历史整体覆盖`() {
        val store = store()
        store.append("c1", msg("1", ChatRole.USER, "旧的"))
        store.replace("c1", listOf(msg("9", ChatRole.USER, "导入的提问"), msg("10", ChatRole.ASSISTANT, "导入的回答")))

        assertEquals(listOf("导入的提问", "导入的回答"), store.messages("c1").map { it.text })
    }

    @Test
    fun `sealInterrupted 对空会话什么都不做`() {
        val store = store()
        assertFalse(store.sealInterrupted("nobody"))
        assertTrue(store.messages("nobody").isEmpty())
        assertNotNull(store.messages("nobody"))
    }
}
