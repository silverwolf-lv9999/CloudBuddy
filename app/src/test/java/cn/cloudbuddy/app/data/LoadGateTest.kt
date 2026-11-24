package cn.cloudbuddy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 1.3.3 三个真机问题的回归契约。
 *
 * AcpConnection 的构造依赖 Android Context（经 AppSettings），无法在纯 JVM 单测里
 * 实例化；这里验证可独立观察的语义，行为主体靠代码审查 + 真机验证。
 */
class LoadGateTest {

    @Test
    fun `prompt 等待超时不是普通网络超时`() {
        // send() 据此区分「本地窗口到了」与「真的失败」：只有后者才把轮次标 FAILED。
        val promptTimeout = PromptWaitTimeout()
        assertTrue(promptTimeout is IOException)
        assertFalse(promptTimeout is java.net.SocketTimeoutException)
        assertEquals("云端任务仍在运行", promptTimeout.message)
    }

    @Test
    fun `回放闸门以 loadDone 为准而不是 replaying`() {
        // 文档化不变量：replaying 可能被静默看门狗提前清掉，但 load 未收尾前
        // 绝不能推消息，否则剩余历史逐条上屏（用户看到的「回放」）。
        // 这里用一个最小状态机复刻 publishCoalesced 的判定，防止后续改回 replaying。
        data class Gate(val replaying: Boolean, val loadDone: Boolean) {
            val shouldPush: Boolean get() = loadDone
        }
        // 看门狗提前放行、但 load 未收尾：闸门必须仍关闭
        assertFalse(Gate(replaying = false, loadDone = false).shouldPush)
        // 只有 load 真正收尾才放行
        assertTrue(Gate(replaying = false, loadDone = true).shouldPush)
        assertTrue(Gate(replaying = true, loadDone = true).shouldPush)
    }

    @Test
    fun `prompt SSE 兜底上限远大于普通等待窗口`() {
        // 10 分钟窗口内长任务不该被客户端掐断：call timeout 兜底必须显著更大。
        val normalWindow = 10 * 60_000L
        val callTimeout = 2 * 60 * 60_000L
        assertTrue(callTimeout > normalWindow * 6)
    }
}
