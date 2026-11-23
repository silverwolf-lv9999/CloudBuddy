package cn.cloudbuddy.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 提问载荷解析与回答文本化的规则。两种通道（ACP 事件 / REST 快照）共用同一套实现。 */
class AskParsingTest {

    private fun labels(vararg values: String): JSONArray = JSONArray().apply {
        values.forEach { put(JSONObject().put("label", it)) }
    }

    @Test
    fun `multiple questions keep every item and its multiSelect flag`() {
        val node = JSONObject().put(
            "questions",
            JSONArray()
                .put(
                    JSONObject().put("question", "用哪个模型？").put("header", "模型")
                        .put("options", labels("云端默认", "本地"))
                )
                .put(
                    JSONObject().put("question", "要包含哪些内容？").put("multiSelect", true)
                        .put("options", labels("代码", "截图", "日志"))
                )
        )
        val items = parseAskItems(node)
        assertEquals(2, items.size)
        assertEquals("模型", items[0].header)
        assertFalse(items[0].multiSelect)
        assertTrue(items[1].multiSelect)
        assertEquals(listOf("代码", "截图", "日志"), items[1].options.map { it.label })
    }

    @Test
    fun `legacy single question payload still parses`() {
        val node = JSONObject().put("question", "继续吗？").put("options", labels("继续", "停止"))
        val items = parseAskItems(node)
        assertEquals(1, items.size)
        assertEquals("继续吗？", items.single().question)
    }

    /**
     * 权限确认只有 options，正文在 toolCall.title 里。以前这条因为「问题为空」被整条丢掉，
     * 结果是云端在等确认、界面什么都不显示。
     */
    @Test
    fun `permission request without question text still becomes an ask`() {
        val node = JSONObject()
            .put("toolCall", JSONObject().put("title", "运行 npm install"))
            .put(
                "options",
                JSONArray()
                    .put(JSONObject().put("optionId", "opt-1").put("name", "Allow").put("kind", "allow_once"))
                    .put(JSONObject().put("optionId", "opt-2").put("name", "Reject").put("kind", "reject_always"))
            )
        val items = parseAskItems(node)
        assertEquals("运行 npm install", items.single().question)
        assertEquals(listOf("允许一次", "总是拒绝"), items.single().options.map { it.label })
    }

    @Test
    fun `permission option value prefers optionId over translated label`() {
        val node = JSONObject()
            .put("question", "允许执行？")
            .put("options", JSONArray().put(JSONObject().put("optionId", "opt-1").put("name", "Allow").put("kind", "allow_once")))
        val option = parseAskItems(node).single().options.single()
        assertEquals("允许一次", option.label)
        assertEquals("opt-1", option.value)
    }

    private val single = AskQuestion(
        question = "用哪个模型？",
        options = listOf(
            AskOption("云端默认", value = "cloud"),
            AskOption("本地", value = "local")
        )
    )

    @Test
    fun `single choice answer is just the label`() {
        val answer = AskAnswer(listOf(AskPick(listOf("本地"), listOf("local"))))
        assertEquals("本地", single.answerText(answer))
    }

    @Test
    fun `multi choice answer joins labels`() {
        val question = AskQuestion(
            question = "要包含哪些内容？",
            items = listOf(
                AskItem(
                    question = "要包含哪些内容？",
                    options = listOf(AskOption("代码"), AskOption("截图"), AskOption("日志")),
                    multiSelect = true
                )
            )
        )
        val answer = AskAnswer(listOf(AskPick(listOf("代码", "日志"), listOf("代码", "日志"))))
        assertEquals("代码、日志", question.answerText(answer))
    }

    @Test
    fun `multi question answer lists each item by header`() {
        val question = AskQuestion(
            question = "用哪个模型？",
            items = listOf(
                AskItem(question = "用哪个模型？", header = "模型", options = listOf(AskOption("云端默认"))),
                AskItem(question = "要不要缓存？", header = "缓存", options = listOf(AskOption("要")))
            )
        )
        val answer = AskAnswer(
            listOf(
                AskPick(listOf("云端默认")),
                AskPick(listOf("要"))
            )
        )
        assertEquals("模型：云端默认\n缓存：要", question.answerText(answer))
    }

    @Test
    fun `custom text is appended and standalone custom still answers`() {
        val withPick = AskAnswer(listOf(AskPick(listOf("本地"), custom = "顺带看下显存")))
        assertEquals("本地、顺带看下显存", single.answerText(withPick))

        val onlyCustom = AskAnswer(listOf(AskPick(custom = "用 deepseek")))
        assertEquals("用 deepseek", single.answerText(onlyCustom))
    }

    @Test
    fun `blank answer is detected before sending`() {
        assertTrue(AskAnswer(listOf(AskPick())).isBlank())
        assertTrue(AskAnswer(listOf(AskPick(custom = "  "))).isBlank())
        assertFalse(AskAnswer(listOf(AskPick(custom = "x"))).isBlank())
    }

    // ---- 以下盯 1.0.10 的真 bug：服务端以 JSON-RPC 请求下发提问，客户端没接 ----

    /** `_codebuddy.ai/question` 的 params：问题包在 schema 里，工具调用 id 在根上。 */
    @Test
    fun `question request unwraps schema and keeps toolCallId`() {
        val params = JSONObject()
            .put("toolCallId", "call-42")
            .put("timeout", 300000)
            .put(
                "schema",
                JSONObject().put(
                    "questions",
                    JSONArray()
                        .put(
                            JSONObject().put("id", "model").put("header", "模型")
                                .put("question", "用哪个模型？")
                                .put("options", JSONArray().put("云端默认").put("本地"))
                        )
                        .put(
                            JSONObject().put("id", "scope").put("question", "包含哪些内容？")
                                .put("multiSelect", true)
                                .put("options", labels("代码", "截图"))
                        )
                )
            )
        val request = parseAskRequest(params)
        assertTrue(request != null)
        assertEquals("call-42", request!!.toolCallId)
        val items = request.question.allItems
        assertEquals(2, items.size)
        assertEquals(listOf("云端默认", "本地"), items[0].options.map { it.label })
        assertEquals("model", items[0].id)
        assertEquals("scope", items[1].id)
        assertTrue(items[1].multiSelect)
        assertEquals("call-42", request.question.requestId)
    }

    @Test
    fun `answers dictionary uses string for single and array for multi`() {
        val items = listOf(
            AskItem(question = "用哪个模型？", options = listOf(AskOption("云端默认", value = "cloud")), id = "model"),
            AskItem(
                question = "包含哪些内容？",
                options = listOf(AskOption("代码"), AskOption("截图")),
                multiSelect = true,
                id = "scope"
            )
        )
        val answer = AskAnswer(
            listOf(
                AskPick(listOf("云端默认"), listOf("cloud")),
                AskPick(listOf("代码", "截图"), listOf("代码", "截图"))
            )
        )
        val answers = buildAnswers(items, answer)
        assertEquals("cloud", answers.getString("model"))
        assertEquals(2, answers.getJSONArray("scope").length())
        assertEquals("截图", answers.getJSONArray("scope").getString(1))
    }

    @Test
    fun `custom text fills answers when no option picked`() {
        val items = listOf(AskItem(question = "用哪个模型？", id = "model"))
        val answers = buildAnswers(items, AskAnswer(listOf(AskPick(custom = "deepseek-v4"))))
        assertEquals("deepseek-v4", answers.getString("model"))
    }

    @Test
    fun `empty pick writes nothing so cloud does not read a fake answer`() {
        val answers = buildAnswers(listOf(AskItem(question = "q", id = "q-0")), AskAnswer(listOf(AskPick())))
        assertFalse(answers.has("q-0"))
    }

    /** 官方按名字认这个工具，并把 `-` `_` 空格去掉再比。 */
    @Test
    fun `ask user question tool name is matched loosely`() {
        assertTrue(isAskUserQuestionTool("AskUserQuestion"))
        assertTrue(isAskUserQuestionTool("ask_user_question"))
        assertTrue(isAskUserQuestionTool("ask-user-question"))
        assertTrue(isAskUserQuestionTool(" askuserquestion "))
        assertFalse(isAskUserQuestionTool("Bash"))
    }

    @Test
    fun `payload without questions is rejected instead of shown`() {
        assertTrue(parseAskRequest(JSONObject().put("toolCallId", "x")) == null)
    }
}
