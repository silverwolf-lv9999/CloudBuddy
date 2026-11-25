package cn.cloudbuddy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令日志的边界：环形淘汰、输出截断、会话隔离。
 *
 * 这几条都是「出错时报得很隐蔽」的地方——日志串台会让用户对着 A 会话的终端
 * 看 B 沙箱的输出，比没有终端更坏。
 */
class CommandLogTest {

    private fun entry(id: Long = 1, cmd: String = "ls", stdout: String = "", stderr: String = "") =
        CmdEntry(
            id = id,
            command = cmd,
            source = CmdSource.AGENT,
            startedAt = 0,
            durationMs = 12,
            exitCode = 0,
            stdout = stdout,
            stderr = stderr,
        )

    @Test
    fun `记录按会话隔离，互不可见`() {
        val log = CommandLog()
        log.append("a", entry(cmd = "pwd"))
        log.append("b", entry(cmd = "whoami"))

        assertEquals(listOf("pwd"), log.of("a").map { it.command })
        assertEquals(listOf("whoami"), log.of("b").map { it.command })
        assertEquals(0, log.of("c").size)
    }

    @Test
    fun `超过上限时淘汰最旧的，保留最新的`() {
        val log = CommandLog(maxEntries = 3)
        repeat(5) { i -> log.append("a", entry(id = i.toLong(), cmd = "cmd$i")) }

        assertEquals(listOf("cmd2", "cmd3", "cmd4"), log.of("a").map { it.command })
        assertEquals(3, log.sizeOf("a"))
    }

    @Test
    fun `超长输出头尾都保留并标记截断`() {
        val log = CommandLog(maxStreamChars = 100)
        val head = "HEAD".repeat(10)   // 40
        val tail = "TAIL".repeat(30)   // 120
        val middle = "x".repeat(500)
        val stored = log.append("a", entry(stdout = head + middle + tail))

        assertTrue(stored.truncated)
        assertTrue(stored.stdout.startsWith("HEAD"))
        assertTrue(stored.stdout.endsWith("TAIL"))
        // 省略说明里要带上真实少了多少，否则读者不知道丢了多长的内容。
        assertTrue(stored.stdout.contains("省略"))
        assertTrue(stored.stdout.length < head.length + tail.length + middle.length)
    }

    @Test
    fun `短输出原样保留，不做任何标记`() {
        val log = CommandLog()
        val stored = log.append("a", entry(stdout = "hello"))
        assertEquals("hello", stored.stdout)
        assertFalse(stored.truncated)
    }

    @Test
    fun `stderr 也受同一套截断约束`() {
        val log = CommandLog(maxStreamChars = 40)
        val stored = log.append("a", entry(stderr = "E".repeat(400)))
        assertTrue(stored.truncated)
        assertTrue(stored.stderr.length <= 40 + 40)
    }

    @Test
    fun `清空只影响目标会话`() {
        val log = CommandLog()
        log.append("a", entry())
        log.append("b", entry())
        log.clear("a")
        assertEquals(0, log.sizeOf("a"))
        assertEquals(1, log.sizeOf("b"))
    }

    @Test
    fun `序号全局唯一，跨会话不重复`() {
        val log = CommandLog()
        val first = log.nextId()
        val second = log.nextId()
        assertNotEquals(first, second)
        assertEquals(second, first + 1)
    }

    @Test
    fun `退出码非零与协议错误都能区分出来`() {
        val failed = entry().copy(exitCode = 2, stderr = "boom")
        assertFalse(failed.ok)
        val broken = entry().copy(exitCode = null, error = "HTTP 401")
        assertFalse(broken.ok)
        assertTrue(entry().ok)
    }

    @Test
    fun `纯文本回放置顶命令并带退出码`() {
        val text = entry(cmd = "npm test", stdout = "ok", stderr = "warn").toPlainText()
        assertTrue(text.startsWith("$ npm test"))
        assertTrue(text.contains("ok"))
        assertTrue(text.contains("[stderr]"))
        assertTrue(text.contains("exit 0"))
    }

    @Test
    fun `多行命令的摘要压成一行`() {
        val summary = entry(cmd = "cd /workspace\necho hi\n  ls -la").summary()
        assertEquals("cd /workspace echo hi ls -la", summary)
        assertTrue(("x".repeat(200) + "\n").let { entry(cmd = it).summary(20).length } <= 21)
    }

    @Test
    fun `中断的条目保留已收到的输出且不算成功`() {
        // 用户按停 ping：那几十行回显是有效结果，不能被当成空记录丢掉，
        // 但也不能标成 exit 0——命令确实没跑完。
        val e = CmdEntry(
            id = 9, command = "ping qq.com", source = CmdSource.MANUAL, startedAt = 0,
            durationMs = 4_200, exitCode = null, stdout = "64 bytes from 1.2.3.4\n", stderr = "",
            aborted = true,
        )
        assertFalse(e.ok)
        assertTrue(e.toPlainText().contains("64 bytes from 1.2.3.4"))
        assertTrue(e.toPlainText().contains("已中断"))
    }

    @Test
    fun `复制出来的日志不带转义序列`() {
        val e = CmdEntry(
            id = 10, command = "ls", source = CmdSource.AGENT, startedAt = 0,
            durationMs = 3, exitCode = 0, stdout = "\u001B[34mdir\u001B[0m\n", stderr = "",
        )
        assertTrue(e.toPlainText().contains("dir"))
        assertFalse(e.toPlainText().contains("\u001B"))
    }

    @Test
    fun `同一会话的多个终端标签互不串台`() {
        val log = CommandLog()
        val one = CommandLog.keyOf("c1", 1)
        val two = CommandLog.keyOf("c1", 2)
        log.append(one, entry(id = 1, stdout = "aaa"))
        log.append(two, entry(id = 2, stdout = "bbb"))
        assertEquals(1, log.of(one).size)
        assertEquals("aaa", log.of(one).first().stdout)
        assertEquals(1, log.of(two).size)
        assertEquals("bbb", log.of(two).first().stdout)
    }

    @Test
    fun `删除会话时清掉它所有标签的日志`() {
        val log = CommandLog()
        log.append(CommandLog.keyOf("c1", 1), entry(id = 1))
        log.append(CommandLog.keyOf("c1", 2), entry(id = 2))
        // c10 是另一个会话：按前缀清时必须不被 c1 误伤，这也是分隔符用 # 的原因。
        log.append(CommandLog.keyOf("c10", 3), entry(id = 3))
        log.forget("c1")
        assertEquals(0, log.sizeOf(CommandLog.keyOf("c1", 1)))
        assertEquals(0, log.sizeOf(CommandLog.keyOf("c1", 2)))
        assertEquals(1, log.sizeOf(CommandLog.keyOf("c10", 3)))
    }
}
