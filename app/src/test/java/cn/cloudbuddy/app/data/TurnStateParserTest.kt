package cn.cloudbuddy.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnStateParserTest {

    @Test
    fun `cloud status strings map to client states`() {
        assertEquals(TurnState.RUNNING, TurnStateParser.parse("running"))
        assertEquals(TurnState.RUNNING, TurnStateParser.parse("in_progress"))
        assertEquals(TurnState.WAITING, TurnStateParser.parse("waiting_user_input"))
        assertEquals(TurnState.DONE, TurnStateParser.parse("completed"))
        assertEquals(TurnState.FAILED, TurnStateParser.parse("failed"))
        assertEquals(TurnState.STOPPED, TurnStateParser.parse("cancelled"))
        assertNull(TurnStateParser.parse("who-knows"))
    }

    @Test
    fun `official session status vocabulary is understood`() {
        assertEquals(TurnState.RUNNING, TurnStateParser.parse("working"))
        assertEquals(TurnState.RUNNING, TurnStateParser.parse("streaming"))
        assertEquals(TurnState.RUNNING, TurnStateParser.parse("planning"))
        assertEquals(TurnState.WAITING, TurnStateParser.parse("waiting_permission"))
        assertEquals(TurnState.WAITING, TurnStateParser.parse("waiting_question"))
        assertEquals(TurnState.STOPPED, TurnStateParser.parse("terminated"))
        assertEquals(TurnState.IDLE, TurnStateParser.parse("idle"))
    }

    @Test
    fun `nested status object is understood`() {
        val conversation = JSONObject().put("status", JSONObject().put("state", "running"))
        assertEquals(TurnState.RUNNING, TurnStateParser.remote(conversation))
    }

    /** 官方 `toAgentState()` 取的是 `sessionStatus || status`，漏掉前者会让所有会话被误判。 */
    @Test
    fun `sessionStatus takes priority over status`() {
        val conversation = JSONObject()
            .put("sessionStatus", "working")
            .put("status", "completed")
        assertEquals(TurnState.RUNNING, TurnStateParser.remote(conversation))
    }

    @Test
    fun `sessionStatus alone is enough`() {
        assertEquals(TurnState.STOPPED, TurnStateParser.remote(JSONObject().put("sessionStatus", "interrupted")))
    }

    /** 1.0.1 的兜底是 `DONE`，导致列表里所有会话都显示「已完成」；官方 default 是 idle。 */
    @Test
    fun `missing status falls back to idle`() {
        assertEquals(TurnState.IDLE, TurnStateParser.remote(JSONObject()))
    }
}
