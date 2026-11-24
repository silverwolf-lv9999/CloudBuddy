package cn.cloudbuddy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态仲裁的回归测试。
 *
 * 这三类错位都在真机上出现过：云端跑完了本地还转圈、云端在跑本地显示已停止、
 * 云端在跑本地还允许发消息。判定逻辑集中在 [StateReconciler]，这里逐条钉住。
 */
class StateReconcilerTest {

    private fun decide(local: TurnState, remote: TurnState, inFlight: Boolean = false) =
        StateReconciler.decide(local, remote, inFlight)

    @Test
    fun `状态一致时不做任何事`() {
        TurnState.values().forEach { state ->
            assertEquals(StateReconciler.Action.NONE, decide(state, state))
        }
    }

    @Test
    fun `云端跑完了本地还在执行中要采纳云端`() {
        // 列表里一直转圈就是这个：本地没收到收尾信号，服务端早就 done 了。
        assertEquals(
            StateReconciler.Action.ADOPT_REMOTE_AND_RELOAD,
            decide(TurnState.RUNNING, TurnState.DONE)
        )
        assertEquals(
            StateReconciler.Action.ADOPT_REMOTE_AND_RELOAD,
            decide(TurnState.WAITING, TurnState.DONE)
        )
    }

    @Test
    fun `云端还在跑本地显示停止或空闲要采纳云端并补历史`() {
        assertEquals(
            StateReconciler.Action.ADOPT_REMOTE_AND_RELOAD,
            decide(TurnState.STOPPED, TurnState.RUNNING)
        )
        assertEquals(
            StateReconciler.Action.ADOPT_REMOTE_AND_RELOAD,
            decide(TurnState.IDLE, TurnState.RUNNING)
        )
        // 等待用户确认也是一种「在跑」：输入栏要显示暂停，不能让人继续发消息。
        assertEquals(
            StateReconciler.Action.ADOPT_REMOTE_AND_RELOAD,
            decide(TurnState.DONE, TurnState.WAITING)
        )
    }

    @Test
    fun `本地这一轮还在推流时以本地为准`() {
        // SSE 正在推进度，服务端记录常常落后一拍；此时盖成本地会闪。
        assertEquals(StateReconciler.Action.NONE, decide(TurnState.RUNNING, TurnState.DONE, inFlight = true))
        assertEquals(StateReconciler.Action.NONE, decide(TurnState.RUNNING, TurnState.IDLE, inFlight = true))
    }

    @Test
    fun `同属终态之间的差异只改角标不重载`() {
        assertEquals(StateReconciler.Action.ADOPT_REMOTE, decide(TurnState.DONE, TurnState.FAILED))
        assertEquals(StateReconciler.Action.ADOPT_REMOTE, decide(TurnState.FAILED, TurnState.STOPPED))
    }

    @Test
    fun `对账间隔按前后台与运行状态分档`() {
        assertEquals(0L, StateReconciler.intervalMs(foreground = false, anyActive = true))
        assertEquals(30_000L, StateReconciler.intervalMs(foreground = true, anyActive = true, localTurnInFlight = true))
        assertEquals(10_000L, StateReconciler.intervalMs(foreground = true, anyActive = true))
        assertEquals(45_000L, StateReconciler.intervalMs(foreground = true, anyActive = false))
    }

    @Test
    fun `补历史按服务端 updatedAt 去重`() {
        assertTrue(StateReconciler.shouldResync(1_700_000_100_000, 1_700_000_000_000, TurnState.RUNNING))
        assertFalse(StateReconciler.shouldResync(1_700_000_000_000, 1_700_000_000_000, TurnState.RUNNING))
        // 服务端没给时间戳时，只有「在跑」才值得补一次。
        assertTrue(StateReconciler.shouldResync(0, 0, TurnState.RUNNING))
        assertFalse(StateReconciler.shouldResync(0, 0, TurnState.DONE))
    }

    @Test
    fun `滞留的运行中够老够久才自愈`() {
        val idle = 6 * 60_000L   // 内容六分钟没动
        val seen = 5 * 60_000L   // 观察到在跑五分钟
        assertTrue(StateReconciler.shouldHeal(true, false, idle, seen, tries = 0))
    }

    @Test
    fun `刚发起的任务一律不动`() {
        // 内容才安静一分钟：可能只是长工具调用，不能去戳它。
        assertFalse(StateReconciler.shouldHeal(true, false, 60_000L, 10 * 60_000L, tries = 0))
        // 内容虽老，但「在跑」这件事本身才观察到一分钟，也可能是刚发起的任务。
        assertFalse(StateReconciler.shouldHeal(true, false, 10 * 60_000L, 60_000L, tries = 0))
    }

    @Test
    fun `本机这一轮在推流或已不再运行时不介入`() {
        assertFalse(StateReconciler.shouldHeal(false, false, 60 * 60_000L, 60 * 60_000L, tries = 0))
        assertFalse(StateReconciler.shouldHeal(true, true, 60 * 60_000L, 60 * 60_000L, tries = 0))
    }

    @Test
    fun `自愈次数用完就停手`() {
        assertFalse(
            StateReconciler.shouldHeal(
                true, false, 60 * 60_000L, 60 * 60_000L,
                tries = StateReconciler.HEAL_MAX_TRIES
            )
        )
    }
}
