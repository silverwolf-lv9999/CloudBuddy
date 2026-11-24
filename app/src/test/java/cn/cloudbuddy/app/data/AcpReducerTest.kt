package cn.cloudbuddy.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcpReducerTest {

    private fun chunk(kind: String, text: String, meta: JSONObject? = null): JSONObject {
        val update = JSONObject()
            .put("sessionUpdate", kind)
            .put("content", JSONObject().put("type", "text").put("text", text))
        if (meta != null) update.put("_meta", meta)
        return JSONObject()
            .put("method", "session/update")
            .put("params", JSONObject().put("update", update))
    }

    @Test
    fun `user echo of the sent prompt is not duplicated`() {
        val reducer = AcpReducer("session-1")
        reducer.beginTurn("帮我看看 /workspace")
        reducer.accept(chunk("user_message_chunk", "帮我看看 "), replaying = false)
        reducer.accept(chunk("user_message_chunk", "/workspace"), replaying = false)
        val snapshot = reducer.snapshot()
        assertEquals(1, snapshot.messages.size)
        assertEquals(ChatRole.USER, snapshot.messages[0].role)
        assertEquals("帮我看看 /workspace", snapshot.messages[0].text)
    }

    @Test
    fun `assistant chunks including whitespace are concatenated`() {
        val reducer = AcpReducer("session-1")
        reducer.beginTurn("hi")
        reducer.accept(chunk("agent_message_chunk", "Hello"), replaying = false)
        reducer.accept(chunk("agent_message_chunk", " "), replaying = false)
        reducer.accept(chunk("agent_message_chunk", "world"), replaying = false)
        val assistant = reducer.snapshot().messages.last()
        assertEquals(ChatRole.ASSISTANT, assistant.role)
        assertEquals("Hello world", assistant.text)
    }

    @Test
    fun `end turn marks the state done`() {
        val reducer = AcpReducer("session-1")
        reducer.beginTurn("hi")
        reducer.accept(chunk("agent_message_chunk", "done"), replaying = false)
        reducer.accept(
            JSONObject().put("method", "session/endTurn")
                .put("params", JSONObject().put("stopReason", "end_turn")),
            replaying = false
        )
        assertEquals(TurnState.DONE, reducer.snapshot().state)
    }

    @Test
    fun `history snapshot does not finish a live turn`() {
        val reducer = AcpReducer("session-1")
        reducer.beginTurn("hi")
        val meta = JSONObject()
            .put("codebuddy.ai/status", "completed")
            .put("codebuddy.ai/isSnapshot", true)
        reducer.accept(
            JSONObject().put("method", "session/update").put(
                "params",
                JSONObject().put("update", JSONObject().put("sessionUpdate", "session_info_update").put("_meta", meta))
            ),
            replaying = false
        )
        assertEquals(TurnState.RUNNING, reducer.snapshot().state)
    }

    @Test
    fun `tool call keeps a single row updated in place`() {
        val reducer = AcpReducer("session-1")
        reducer.beginTurn("run")
        fun tool(status: String) = JSONObject()
            .put("method", "session/update")
            .put(
                "params",
                JSONObject().put(
                    "update",
                    JSONObject().put("sessionUpdate", "tool_call")
                        .put("toolCallId", "call-1")
                        .put("title", "读取文件")
                        .put("status", status)
                )
            )
        reducer.accept(tool("in_progress"), replaying = false)
        reducer.accept(tool("completed"), replaying = false)
        val tools = reducer.snapshot().messages.filter { it.role == ChatRole.TOOL }
        assertEquals(1, tools.size)
        assertEquals("读取文件", tools[0].text)
        assertEquals(ToolState.DONE, tools[0].toolState)
    }

    @Test
    fun `artifact notification is collected`() {
        val reducer = AcpReducer("session-1")
        reducer.beginTurn("write a report")
        val artifact = JSONObject()
            .put("uri", "agent://files/workspace/report.md")
            .put("title", "report.md")
        reducer.accept(
            JSONObject().put("method", "_codebuddy.ai/artifact")
                .put("params", JSONObject().put("event", "created").put("artifact", artifact)),
            replaying = false
        )
        val files = reducer.snapshot().artifacts
        assertEquals(1, files.size)
        assertEquals("/workspace/report.md", files[0].sandboxPath)
    }

    @Test
    fun `resource links inside tool output are collected`() {
        val reducer = AcpReducer("session-1")
        reducer.beginTurn("chart")
        val content = JSONArray().put(
            JSONObject()
                .put("type", "resource_link")
                .put("uri", "file:///workspace/charts/a.png")
                .put("name", "a.png")
        )
        reducer.accept(
            JSONObject().put("method", "session/update").put(
                "params",
                JSONObject().put(
                    "update",
                    JSONObject().put("sessionUpdate", "tool_call")
                        .put("toolCallId", "t1")
                        .put("title", "生成图表")
                        .put("content", content)
                )
            ),
            replaying = false
        )
        assertTrue(reducer.snapshot().artifacts.any { it.uri == "file:///workspace/charts/a.png" })
    }

    @Test
    fun `askuser request turns the turn into waiting`() {
        val reducer = AcpReducer("session-1")
        reducer.beginTurn("do it")
        reducer.accept(
            JSONObject().put("method", "session/request_permission").put(
                "params",
                JSONObject()
                    .put("requestId", "req-9")
                    .put("question", "要覆盖同名文件吗？")
                    .put("options", JSONArray().put(JSONObject().put("label", "覆盖")).put(JSONObject().put("label", "跳过")))
            ),
            replaying = false
        )
        val snapshot = reducer.snapshot()
        assertEquals(TurnState.WAITING, snapshot.state)
        assertEquals("req-9", snapshot.ask?.requestId)
        assertEquals(2, snapshot.ask?.options?.size)
    }

    private fun sessionUpdate(update: JSONObject): JSONObject =
        JSONObject().put("method", "session/update")
            .put("params", JSONObject().put("update", update))

    /**
     * 顶部状态闪烁的直接成因：`update.sessionStatus` 说 working，
     * 而 `_meta` 里残留上一轮的 idle。官方 `UKr()` 的优先级是 update 在前。
     */
    @Test
    fun `update sessionStatus wins over stale meta status`() {
        val reducer = AcpReducer("session-1")
        reducer.beginTurn("hi")
        val update = JSONObject()
            .put("sessionUpdate", "session_info_update")
            .put("sessionStatus", "working")
            .put("_meta", JSONObject().put("codebuddy.ai/status", "idle"))
        reducer.accept(sessionUpdate(update), replaying = false)
        assertEquals(TurnState.RUNNING, reducer.snapshot().state)
    }

    @Test
    fun `idle report during a live turn does not demote the state`() {
        val reducer = AcpReducer("session-1")
        reducer.beginTurn("hi")
        reducer.accept(
            sessionUpdate(JSONObject().put("sessionUpdate", "state_update").put("currentState", "idle")),
            replaying = false
        )
        assertEquals(TurnState.RUNNING, reducer.snapshot().state)
    }

    /** 流式过程中的状态上报不是文本边界，否则一段回答会被切成多条消息（界面文字跳动）。 */
    @Test
    fun `state update mid-stream does not split the assistant message`() {
        val reducer = AcpReducer("session-1")
        reducer.beginTurn("hi")
        reducer.accept(chunk("agent_message_chunk", "第一段"), replaying = false)
        reducer.accept(
            sessionUpdate(JSONObject().put("sessionUpdate", "state_update").put("currentState", "working")),
            replaying = false
        )
        reducer.accept(chunk("agent_message_chunk", "第二段"), replaying = false)
        val assistant = reducer.snapshot().messages.filter { it.role == ChatRole.ASSISTANT }
        assertEquals(1, assistant.size)
        assertEquals("第一段第二段", assistant[0].text)
    }

    /** 云端回显常被规范化（裁空白、分段不同），按前缀去重会失配并多追加一条。 */
    @Test
    fun `user echo with normalized text is not duplicated`() {
        val reducer = AcpReducer("session-1")
        reducer.beginTurn("帮我看看 /workspace")
        reducer.accept(chunk("user_message_chunk", "  帮我看看"), replaying = false)
        reducer.accept(chunk("user_message_chunk", " /workspace  "), replaying = false)
        val users = reducer.snapshot().messages.filter { it.role == ChatRole.USER }
        assertEquals(1, users.size)
        assertEquals("帮我看看 /workspace", users[0].text)
    }

    private fun toolCall(id: String, title: String) = JSONObject()
        .put("method", "session/update")
        .put(
            "params",
            JSONObject().put(
                "update",
                JSONObject().put("sessionUpdate", "tool_call")
                    .put("toolCallId", id)
                    .put("title", title)
                    .put("status", "completed")
            )
        )

    /** 每次提问开启新的一轮，本轮的思考 / 工具调用都归入同一 turn，界面据此分组折叠。 */
    @Test    fun `each prompt opens a new turn and its steps belong to it`() {
        val reducer = AcpReducer("session-1")
        reducer.beginTurn("第一问")
        reducer.accept(chunk("agent_thought_chunk", "想一想"), replaying = false)
        reducer.accept(toolCall("call-1", "读取文件"), replaying = false)
        reducer.accept(chunk("agent_message_chunk", "第一个回答"), replaying = false)
        reducer.finishTurn(JSONObject().put("stopReason", "end_turn"))

        reducer.beginTurn("第二问")
        reducer.accept(toolCall("call-2", "写入文件"), replaying = false)
        reducer.accept(chunk("agent_message_chunk", "第二个回答"), replaying = false)
        reducer.finishTurn(JSONObject().put("stopReason", "end_turn"))

        val messages = reducer.snapshot().messages
        assertEquals(listOf(0, 0, 0, 0, 1, 1, 1), messages.map { it.turn })
        assertEquals(listOf(ChatRole.USER, ChatRole.THOUGHT, ChatRole.TOOL, ChatRole.ASSISTANT), messages.take(4).map { it.role })
    }

    /** 历史回放里没有 beginTurn，靠用户消息切分轮次，否则整个历史会挤进同一个折叠组。 */
    @Test
    fun `replayed history splits turns on user messages`() {
        val reducer = AcpReducer("session-1")
        reducer.accept(chunk("user_message_chunk", "旧问题一"), replaying = true)
        reducer.accept(chunk("agent_message_chunk", "旧回答一"), replaying = true)
        reducer.accept(chunk("user_message_chunk", "旧问题二"), replaying = true)
        reducer.accept(chunk("agent_message_chunk", "旧回答二"), replaying = true)

        val messages = reducer.snapshot().messages
        assertEquals(listOf(0, 0, 1, 1), messages.map { it.turn })
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER, ChatRole.ASSISTANT), messages.map { it.role })
    }

    private fun toolStatus(id: String, status: String) = JSONObject()
        .put("method", "session/update")
        .put(
            "params",
            JSONObject().put(
                "update",
                JSONObject().put("sessionUpdate", "tool_call")
                    .put("toolCallId", id)
                    .put("title", "运行命令")
                    .put("status", status)
            )
        )

    /**
     * 回放收尾必须把历史收敛成终态。
     *
     * 否则打开旧会话会看到：最后一条回答一直在「流式中」、历史里的工具调用还在转圈，
     * 也就是用户说的「进来以后会回放之前的步骤」。
     */
    @Test
    fun `finish replay clears streaming flag and running tools`() {
        val reducer = AcpReducer("session-1")
        reducer.accept(chunk("user_message_chunk", "旧问题"), replaying = true)
        reducer.accept(toolStatus("call-1", "in_progress"), replaying = true)
        reducer.accept(chunk("agent_message_chunk", "旧回答"), replaying = true)

        reducer.finishReplay()

        val messages = reducer.snapshot().messages
        assertTrue(messages.none { it.streaming })
        assertEquals(ToolState.NONE, messages.first { it.role == ChatRole.TOOL }.toolState)
        assertEquals(TurnState.IDLE, reducer.snapshot().state)
    }

    @Test
    fun `finish replay keeps completed tools as completed`() {
        val reducer = AcpReducer("session-1")
        reducer.accept(chunk("user_message_chunk", "旧问题"), replaying = true)
        reducer.accept(toolStatus("call-1", "completed"), replaying = true)

        reducer.finishReplay()

        assertEquals(ToolState.DONE, reducer.snapshot().messages.first { it.role == ChatRole.TOOL }.toolState)
    }

    // ---- AskUserQuestion 以工具调用形态下发：以前只画一条转圈步骤，卡片永不弹 ----

    private fun askTool(status: String): JSONObject {
        val raw = JSONObject().put(
            "questions",
            JSONArray().put(
                JSONObject().put("id", "model").put("header", "模型")
                    .put("question", "用哪个模型？")
                    .put("options", JSONArray().put("云端默认").put("本地"))
            )
        )
        val update = JSONObject()
            .put("sessionUpdate", "tool_call")
            .put("toolCallId", "call-ask")
            .put("title", "Ask User")
            .put("toolName", "AskUserQuestion")
            .put("status", status)
            .put("rawInput", raw)
        return JSONObject()
            .put("method", "session/update")
            .put("params", JSONObject().put("update", update))
    }

    @Test
    fun `ask user question tool call raises the card`() {
        val reducer = AcpReducer("session-1")
        reducer.beginTurn("go")
        reducer.accept(askTool("executing"), replaying = false)
        val snapshot = reducer.snapshot()
        val ask = snapshot.ask
        assertTrue(ask != null)
        assertEquals("call-ask", ask!!.requestId)
        assertEquals("用哪个模型？", ask.allItems.single().question)
        assertEquals(listOf("云端默认", "本地"), ask.allItems.single().options.map { it.label })
        assertEquals(TurnState.WAITING, snapshot.state)
        // 卡片已经代表这次交互，不该再画一条转圈的步骤行。
        assertTrue(snapshot.messages.none { it.role == ChatRole.TOOL })
    }

    @Test
    fun `answered ask user question tool call clears the card`() {
        val reducer = AcpReducer("session-1")
        reducer.beginTurn("go")
        reducer.accept(askTool("executing"), replaying = false)
        reducer.accept(askTool("executed"), replaying = false)
        assertTrue(reducer.snapshot().ask == null)
    }

    @Test
    fun `replayed history does not pop the ask card`() {
        val reducer = AcpReducer("session-1")
        reducer.accept(askTool("executing"), replaying = true)
        assertTrue(reducer.snapshot().ask == null)
    }

    /** 重装 App 后仍转圈：一条滞留的旧 working 事件不该把终态重新拉回运行中。 */
    @Test
    fun `stale working report cannot restart a finished turn`() {
        val reducer = AcpReducer("session-1")
        reducer.finishReplay()
        reducer.allowRemoteRunning = false
        reducer.accept(
            sessionUpdate(
                JSONObject().put("sessionUpdate", "session_info_update")
                    .put("sessionStatus", "working")
            ),
            replaying = false
        )
        assertEquals(TurnState.IDLE, reducer.snapshot().state)
    }

    /**
     * 大退重进后的反例：云端任务仍在跑，但不是本客户端发起的（没有 beginTurn）。
     * 回放收尾后闸门是开的，实时 working 必须点亮状态——否则消息在流、角标却显示空闲。
     */
    @Test
    fun `live working after reopen lights up state without a local prompt`() {
        val reducer = AcpReducer("session-1")
        reducer.finishReplay()
        // 不调用 beginTurn：这一轮由上一次打开 App 时发起，本客户端这一轮标记是 false。
        // 关键是回放收尾后 allowRemoteRunning 为 true（endReplay 设的），实时事件一律采信。
        assertTrue(reducer.allowRemoteRunning)
        reducer.accept(
            sessionUpdate(
                JSONObject().put("sessionUpdate", "session_info_update")
                    .put("sessionStatus", "working")
            ),
            replaying = false
        )
        assertEquals(TurnState.RUNNING, reducer.snapshot().state)
    }

    /**
     * 用户实测（1.4.7）：云端 agent 等一个几分钟的后台任务，期间一条中途的状态上报
     * 把角标顶成「已完成」，工具卡却还在转。只要还有工具在转圈，状态上报里的
     * 终态就不可信——真正的收尾只认 session/endTurn 与 prompt 响应。
     */
    @Test
    fun `completed status is ignored while a tool call is still running`() {
        val reducer = AcpReducer("session-1")
        reducer.beginTurn("看下构建结果")
        reducer.accept(toolStatus("call-1", "in_progress"), replaying = false)
        // 旁观场景：turnActive 为 false 也一样要拦。
        reducer.accept(
            sessionUpdate(
                JSONObject().put("sessionUpdate", "session_info_update")
                    .put("sessionStatus", "completed")
            ),
            replaying = false
        )
        assertEquals(TurnState.RUNNING, reducer.snapshot().state)
        // 工具收尾之后，终态上报照常采信。
        reducer.accept(toolStatus("call-1", "completed"), replaying = false)
        reducer.accept(
            sessionUpdate(
                JSONObject().put("sessionUpdate", "session_info_update")
                    .put("sessionStatus", "completed")
            ),
            replaying = false
        )
        assertEquals(TurnState.DONE, reducer.snapshot().state)
    }

    /** 收尾时还没等到 tool_call_update 的工具不该继续转圈（与 finishReplay 同款清理）。 */
    @Test
    fun `endTurn clears tools still showing a spinner`() {
        val reducer = AcpReducer("session-1")
        reducer.beginTurn("hi")
        reducer.accept(toolStatus("call-1", "in_progress"), replaying = false)
        reducer.accept(
            JSONObject().put("method", "session/endTurn")
                .put("params", JSONObject().put("stopReason", "end_turn")),
            replaying = false
        )
        val snapshot = reducer.snapshot()
        assertEquals(TurnState.DONE, snapshot.state)
        assertEquals(ToolState.NONE, snapshot.messages.last { it.role == ChatRole.TOOL }.toolState)
    }
}
