package cn.cloudbuddy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示词片段的拼装与存取。
 *
 * 这块错了不会抛异常，只会让模型「变笨」或者「忘规矩」——单测是唯一能把
 * 「顺序对不对、有没有漏、关了还注入没有」钉住的地方。
 */
class PromptComposerTest {

    private fun preset(
        name: String,
        content: String,
        position: PromptPosition = PromptPosition.AFTER_SYSTEM,
        enabled: Boolean = true,
        priority: Int = 0,
    ) = PromptPreset(
        id = name,
        name = name,
        content = content,
        enabled = enabled,
        position = position.key,
        priority = priority,
    )

    @Test
    fun `片段按「之前 → 全局提示词 → 之后」拼起来`() {
        val system = PromptComposer.system(
            custom = "你是我的助手",
            presets = listOf(
                preset("规范", "改完代码先跑测试", PromptPosition.AFTER_SYSTEM),
                preset("身份", "只谈技术", PromptPosition.BEFORE_SYSTEM),
            ),
        )

        val before = system.indexOf("只谈技术")
        val global = system.indexOf("你是我的助手")
        val after = system.indexOf("改完代码先跑测试")
        assertTrue("「之前」的片段要在最前", before in 0 until global)
        assertTrue("「之后」的片段要在全局提示词后面", after > global)
        // 沙箱规则仍然在：用户自定义提示词时不能被片段挤掉。
        assertTrue(system.contains("沙箱工作目录是 /workspace"))
    }

    @Test
    fun `关掉的片段不注入`() {
        val system = PromptComposer.system(
            custom = "",
            presets = listOf(preset("规范", "改完代码先跑测试", enabled = false)),
        )
        assertFalse(system.contains("改完代码先跑测试"))
    }

    @Test
    fun `同一位置的片段按优先级降序`() {
        val system = PromptComposer.system(
            custom = "",
            presets = listOf(
                preset("低", "低优先级内容", priority = 1),
                preset("高", "高优先级内容", priority = 9),
            ),
        )
        assertTrue(system.indexOf("高优先级内容") < system.indexOf("低优先级内容"))
    }

    @Test
    fun `每条消息之前的片段单独返回，不进系统提示词`() {
        val presets = listOf(preset("提醒", "先看 AGENTS.md", PromptPosition.BEFORE_USER))

        val system = PromptComposer.system("", presets)
        val prefix = PromptComposer.userPrefix(presets)

        assertFalse("贴消息的片段不该混进系统提示词", system.contains("先看 AGENTS.md"))
        assertEquals("先看 AGENTS.md", prefix)
    }

    @Test
    fun `没有片段时系统提示词与原来完全一致`() {
        // 老用户升级上来一条片段都没有，提示词必须逐字不变，否则等于悄悄改了所有人的模型行为。
        assertEquals(
            CustomModelConfig.composeSystemPrompt("自定义"),
            PromptComposer.system("自定义", emptyList())
        )
        assertEquals("", PromptComposer.userPrefix(emptyList()))
    }

    @Test
    fun `空内容与纯空白的片段不会被拼进去`() {
        val system = PromptComposer.system(
            custom = "",
            presets = listOf(preset("空", "   "))
        )
        assertFalse(system.contains("   "))
    }

    @Test
    fun `模型配置带上片段后取到的是拼好的提示词`() {
        val cfg = CustomModelConfig(
            baseUrl = "https://x.test",
            apiKey = "k",
            modelId = "m",
            presets = listOf(preset("规范", "改完代码先跑测试")),
        )
        assertTrue(cfg.effectiveSystemPrompt.contains("改完代码先跑测试"))
        assertEquals("", cfg.userPrefix)
    }
}

/**
 * 片段表本身的增删改与顺序。
 *
 * 顺序这条尤其容易写错：决定顺序的是 `priority`，只挪列表位置不会改变拼装结果，
 * 「点了上移却没反应」就是从这里来的。
 */
class PromptStoreTest {

    @Test
    fun `保存后能原样读回`() {
        val store = PromptStore(FakePrefs())
        store.upsert(
            PromptPreset(
                id = "a",
                name = "规范",
                content = "改完代码先跑测试",
                position = PromptPosition.BEFORE_SYSTEM.key,
                priority = 3,
            )
        )

        val one = store.list().single()
        assertEquals("规范", one.name)
        assertEquals(PromptPosition.BEFORE_SYSTEM, one.placed)
        assertEquals(3, one.priority)
    }

    @Test
    fun `同名 id 再存是覆盖不是新增`() {
        val store = PromptStore(FakePrefs())
        store.upsert(PromptPreset("a", "旧名", "内容一"))
        store.upsert(PromptPreset("a", "新名", "内容二"))

        assertEquals(1, store.list().size)
        assertEquals("内容二", store.list().single().content)
    }

    @Test
    fun `关掉之后不再参与拼装，但仍留在列表里`() {
        val store = PromptStore(FakePrefs())
        store.upsert(PromptPreset("a", "规范", "改完代码先跑测试"))

        store.setEnabled("a", false)

        assertTrue(store.enabled().isEmpty())
        assertEquals(1, store.list().size)
    }

    @Test
    fun `上移会真的改变拼装顺序`() {
        val store = PromptStore(FakePrefs())
        store.upsert(PromptPreset("a", "A", "内容 A", priority = 0))
        store.upsert(PromptPreset("b", "B", "内容 B", priority = 0))

        // priority 相同时按列表顺序，B 在后；上移后 B 应该排到前面。
        assertEquals(listOf("A", "B"), store.ordered().map { it.name })
        store.move("b", up = true)
        assertEquals(listOf("B", "A"), store.ordered().map { it.name })
    }

    @Test
    fun `已经在最前时上移不动`() {
        val store = PromptStore(FakePrefs())
        store.upsert(PromptPreset("a", "A", "内容 A"))

        store.move("a", up = true)

        assertEquals(listOf("A"), store.ordered().map { it.name })
    }

    @Test
    fun `删除后不再出现`() {
        val store = PromptStore(FakePrefs())
        store.upsert(PromptPreset("a", "A", "内容 A"))
        store.remove("a")
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `未知的位置取值回落到系统提示词之后`() {
        // 旧版本或手改出来的数据里可能有别的枚举值，不能因此把一条片段弄丢。
        val preset = PromptPreset.fromJson(
            org.json.JSONObject("""{"id":"a","n":"x","c":"内容","p":"at_depth"}""")
        )
        assertEquals(PromptPosition.AFTER_SYSTEM, preset?.placed)
    }

    @Test
    fun `没有内容的条目读不出来`() {
        val pref = PromptPreset.fromJson(org.json.JSONObject("""{"id":"a","n":"空","c":"  "}"""))
        assertTrue(pref == null)
    }
}
