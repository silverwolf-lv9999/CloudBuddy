package cn.cloudbuddy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型条目（[CustomModel]）的持久化与初值猜测。
 *
 * 这段最要命的是**兼容旧格式**：1.5.x 存的是纯 ID 字符串数组，升级后如果解析不出来，
 * 用户升级一次「我的模型」就空了，会话里也没有可选的模型。所以旧格式、
 * 新格式、脏数据三种都要覆盖。
 */
class CustomModelTest {

    @Test
    fun `旧版纯字符串数组能读成模型条目`() {
        val list = CustomModel.decode("""["deepseek-chat","deepseek-reasoner"]""")

        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), list.map { it.id })
        // 旧数据里没有显示名，界面要回落到 ID，不能显示空白。
        assertEquals("deepseek-chat", list.first().title)
        assertFalse(list.first().customized)
    }

    @Test
    fun `新旧格式混合也能读`() {
        val raw = """["a",{"id":"b","name":"模型 B","reasoning":true,"effort":"high","turns":6}]"""

        val list = CustomModel.decode(raw)
        assertEquals(2, list.size)
        assertEquals("模型 B", list[1].title)
        assertTrue(list[1].reasoning)
        assertEquals("high", list[1].thinkingEffort)
        assertEquals(6, list[1].contextTurns)
    }

    @Test
    fun `重复与空 ID 会被丢掉`() {
        val list = CustomModel.decode("""[{"id":"a"},{"id":"a","name":"第二个"},{"id":""}," "]""")

        assertEquals(1, list.size)
        // 保留第一条：用户先加的那个（可能已经配过参数）才是要留的。
        assertEquals("", list.first().name)
    }

    @Test
    fun `脏数据不抛异常`() {
        assertEquals(emptyList<CustomModel>(), CustomModel.decode(null))
        assertEquals(emptyList<CustomModel>(), CustomModel.decode(""))
        assertEquals(emptyList<CustomModel>(), CustomModel.decode("not json"))
        assertEquals(emptyList<CustomModel>(), CustomModel.decode("""{"id":"a"}"""))
        assertEquals(emptyList<CustomModel>(), CustomModel.decode("""[1,2,3]"""))
    }

    @Test
    fun `编码只写非默认字段且能回读`() {
        val source = listOf(
            CustomModel("deepseek-chat"),
            CustomModel(
                id = "deepseek-reasoner",
                name = "会思考的",
                reasoning = true,
                thinkingEffort = "high",
                systemPrompt = "先想三步",
                contextTurns = 8
            ),
        )

        val encoded = CustomModel.encode(source)
        // 默认值不落库：JSON 里出现一堆 "name":"" 只会让后续兼容判断更难写。
        assertFalse(encoded.contains("\"name\":\"\""))
        assertEquals(source, CustomModel.decode(encoded))
    }

    @Test
    fun `思考模型按 ID 猜测`() {
        assertTrue(CustomModel.guessReasoning("deepseek-reasoner"))
        assertTrue(CustomModel.guessReasoning("qwq-32b"))
        assertTrue(CustomModel.guessReasoning("Qwen3-30B-Thinking"))
        assertTrue(CustomModel.guessReasoning("o3-mini"))
        assertFalse(CustomModel.guessReasoning("deepseek-chat"))
        assertFalse(CustomModel.guessReasoning("gpt-4o"))
        assertFalse(CustomModel.guessReasoning("qwen-plus"))
    }

    @Test
    fun `能力开关 tools vision 往返保留`() {
        val source = CustomModel("gpt-4o", tools = false, vision = true)
        val back = CustomModel.decode(CustomModel.encode(listOf(source))).single()
        assertEquals(source, back)
        // 只在偏离默认值时落盘：默认值写进 JSON 会拖累旧版本解析
        val encoded = CustomModel.encode(listOf(CustomModel("m")))
        assertFalse(encoded.contains("tools"))
        assertFalse(encoded.contains("vision"))
    }

    @Test
    fun `读图模型按 ID 猜测`() {
        assertTrue(CustomModel.guessVision("qwen-vl-max"))
        assertTrue(CustomModel.guessVision("gpt-4o"))
        assertTrue(CustomModel.guessVision("glm-4v-flash"))
        assertTrue(CustomModel.guessVision("gemini-2.0-flash"))
        assertTrue(CustomModel.guessVision("claude-sonnet-4"))
        assertFalse(CustomModel.guessVision("deepseek-chat"))
        assertFalse(CustomModel.guessVision("deepseek-reasoner"))
    }

    @Test
    fun `关键词筛选要求全部命中且不分大小写`() {
        val list = listOf(
            CustomModel("deepseek-chat", "便宜"),
            CustomModel("deepseek-reasoner", "思考版"),
            CustomModel("qwen-plus"),
        )

        assertEquals(listOf("deepseek-reasoner"), CustomModel.filter(list, "DEEPSEEK 思考").map { it.id })
        assertEquals(2, CustomModel.filter(list, "deepseek").size)
        assertEquals(list, CustomModel.filter(list, "   "))
    }

    @Test
    fun `显示名留空时用 ID 且不算自定义`() {
        assertFalse(CustomModel("m", name = "   ").customized)
        assertTrue(CustomModel("m", name = "别名").customized)
        assertTrue(CustomModel("m", reasoning = true).customized)
        assertTrue(CustomModel("m", contextTurns = 3).customized)
    }
}
