package cn.cloudbuddy.app.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 思考参数（[ThinkingParams]）与它在请求体里的落点。
 *
 * 这块没有「编译期」保障：字段名发错了，服务端一声不响地忽略，表现只是
 * 「模型还是不想」。所以除了纯函数单测，还起了一个 MockWebServer，
 * 把真正发出去的 JSON 抓下来核对。
 */
class ThinkingParamsTest {

    // ---- 写法判定 ----

    @Test
    fun `按地址猜写法`() {
        assertEquals(
            ThinkingStyle.THINKING,
            ThinkingParams.autoStyle("https://api.deepseek.com")
        )
        assertEquals(
            ThinkingStyle.THINKING,
            ThinkingParams.autoStyle("https://open.bigmodel.cn/api/paas/v4")
        )
        assertEquals(
            ThinkingStyle.ENABLE_THINKING,
            ThinkingParams.autoStyle("https://dashscope.aliyuncs.com/compatible-mode/v1")
        )
        // 认不出来的（OpenAI 官方、自建 vLLM）走 reasoning_effort。
        assertEquals(
            ThinkingStyle.EFFORT,
            ThinkingParams.autoStyle("https://api.openai.com/v1")
        )
        assertEquals(ThinkingStyle.EFFORT, ThinkingParams.autoStyle("http://127.0.0.1:8000"))
    }

    @Test
    fun `手动选的写法优先于自动`() {
        assertEquals(
            ThinkingStyle.EFFORT,
            ThinkingParams.resolveStyle("https://api.deepseek.com", ThinkingStyle.EFFORT.key)
        )
        assertEquals(
            ThinkingStyle.THINKING,
            ThinkingParams.resolveStyle("https://api.openai.com", ThinkingStyle.THINKING.key)
        )
    }

    // ---- 字段形态 ----

    @Test
    fun `thinking 写法带开关与强度`() {
        val f = ThinkingParams.fields("https://api.deepseek.com", "auto", "high")
        assertEquals("enabled", (f["thinking"] as JsonObject)["type"]!!.jsonPrimitive.content)
        assertEquals("high", f["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun `关闭时要显式发 disabled`() {
        // 这家的思考默认开着，不发字段等于「继续想」，关不掉。
        val f = ThinkingParams.fields("https://api.deepseek.com", "auto", "off")
        assertEquals("disabled", (f["thinking"] as JsonObject)["type"]!!.jsonPrimitive.content)
        assertFalse(f.containsKey("reasoning_effort"))
    }

    @Test
    fun `通义用 enable_thinking 布尔`() {
        val on = ThinkingParams.fields("https://dashscope.aliyuncs.com", "auto", "low")
        assertEquals("true", on["enable_thinking"]!!.jsonPrimitive.content)
        assertEquals("low", on["reasoning_effort"]!!.jsonPrimitive.content)
        val off = ThinkingParams.fields("https://dashscope.aliyuncs.com", "auto", "off")
        assertEquals("false", off["enable_thinking"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reasoning_effort 写法关不掉就一个字都不发`() {
        // 没有「关」这个值，硬发 none 在 o 系列以外的模型上容易换回 400。
        assertTrue(ThinkingParams.fields("https://api.openai.com", "auto", "off").isEmpty())
    }

    @Test
    fun `gpt-4o 这类模型不发 reasoning_effort`() {
        // OpenAI 对不认识的参数是报错而不是忽略，默认强度「中」会让人第一次就撞 400。
        assertTrue(
            ThinkingParams.fields("https://api.openai.com", "auto", "high", "gpt-4o-mini").isEmpty()
        )
        assertTrue(
            ThinkingParams.fields("https://api.openai.com", "auto", "high", "gpt-4.1").isEmpty()
        )
        // 推理模型照发。
        assertEquals(
            "high",
            ThinkingParams.fields("https://api.openai.com", "auto", "high", "o3")["reasoning_effort"]
                ?.jsonPrimitive?.content
        )
    }

    @Test
    fun `合并进请求体不影响原有字段`() {
        val body = """{"model":"deepseek-chat","messages":[],"temperature":0.3}"""
        val merged = ThinkingParams.apply(body, ThinkingParams.fields("https://api.deepseek.com", "auto", "high"))
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(merged).jsonObject
        assertEquals("deepseek-chat", obj["model"]!!.jsonPrimitive.content)
        assertEquals("0.3", obj["temperature"]!!.jsonPrimitive.content)
        assertEquals("enabled", (obj["thinking"] as JsonObject)["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `字段为空时原样返回`() {
        val body = """{"model":"m"}"""
        assertEquals(body, ThinkingParams.apply(body, emptyMap()))
    }

    @Test
    fun `请求体不是对象时不要把它搞坏`() {
        assertEquals("not-json", ThinkingParams.apply("not-json", mapOf("a" to JsonPrimitive(1))))
    }

    // ---- 请求体组装：思考字段直接平铺进 JSON ----

    @Test
    fun `thinking 写法真的进了请求体`() {
        val body = capture(
            config(
                baseUrl = "https://api.deepseek.com",
                style = ThinkingStyle.THINKING.key,
                effort = ThinkingEffort.HIGH.key,
            )
        )
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        assertEquals("enabled", (obj["thinking"] as JsonObject)["type"]!!.jsonPrimitive.content)
        assertEquals("high", obj["reasoning_effort"]!!.jsonPrimitive.content)
        assertTrue("messages 不能丢", obj.containsKey("messages"))
    }

    @Test
    fun `落不出写法的端点只发 reasoning_effort`() {
        // 自建端点会被判成 EFFORT 写法。
        val body = capture(
            config(
                baseUrl = "http://127.0.0.1:8000",
                style = ThinkingStyle.AUTO.key,
                effort = ThinkingEffort.MEDIUM.key,
            )
        )
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        assertEquals("medium", obj["reasoning_effort"]!!.jsonPrimitive.content)
        assertFalse(obj.containsKey("thinking"))
    }

    @Test
    fun `关掉思考后不发强度`() {
        val body = capture(
            config(
                baseUrl = "https://api.openai.com/v1",
                style = ThinkingStyle.EFFORT.key,
                effort = ThinkingEffort.OFF.key,
            )
        )
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
        assertFalse(obj.containsKey("reasoning_effort"))
        assertFalse(obj.containsKey("thinking"))
    }

    /** 组一次最小请求的请求体（自研引擎后组装是纯函数，直接核对）。 */
    private fun capture(cfg: CustomModelConfig): String =
        ChatRequest.build(cfg, listOf(ChatMsg.System("sys"), ChatMsg.User("hi")), emptyList())

    private fun config(baseUrl: String, style: String, effort: String) = CustomModelConfig(
        baseUrl = baseUrl,
        apiKey = "sk-test",
        modelId = "deepseek-chat",
        thinkingStyle = style,
        thinkingEffort = effort,
    )
}
