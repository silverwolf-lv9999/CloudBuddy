package cn.cloudbuddy.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型能力目录（models.dev）测试：索引解析、查找键放宽、api.json → 快照的投票合并。
 */
class ModelRegistryTest {

    @Test
    fun `快照解析与查找`() {
        val slim = JSONObject()
            .put("deepseek-chat", JSONArray().put(0).put(0).put(1).put(0).put(64))
            .put("claude-sonnet-4-5", JSONArray().put(1).put(0).put(1).put(1).put(200))
            .toString()
        assertTrue(ModelRegistry.installRaw(slim))
        try {
            val chat = ModelRegistry.find("deepseek-chat")!!
            assertTrue(chat.tools)
            assertFalse(chat.visionInput)
            assertFalse(chat.reasoning)
            assertEquals(64_000, chat.contextLength)

            val sonnet = ModelRegistry.find("Claude-Sonnet-4-5")!!
            assertTrue(sonnet.visionInput)
            assertTrue(sonnet.reasoning)
            assertEquals(200_000, sonnet.contextLength)

            assertNull(ModelRegistry.find("not-in-catalog"))
            assertNull(ModelRegistry.find("  "))
        } finally {
            // 装回空目录，避免影响其他测试（单测进程共享静态单例）
            ModelRegistry.installRaw("{}")
        }
    }

    @Test
    fun `查找键逐级放宽`() {
        ModelRegistry.installRaw(
            JSONObject().put("claude-sonnet-4-5", JSONArray().put(1).put(0).put(1).put(1).put(200)).toString()
        )
        try {
            // 提供商前缀（anthropic/...）
            assertNotNull(ModelRegistry.find("anthropic/claude-sonnet-4-5"))
            // OpenRouter 渠道后缀（:free）
            assertNotNull(ModelRegistry.find("claude-sonnet-4-5:free"))
            // 前缀 + 后缀一起
            assertNotNull(ModelRegistry.find("deepseek/claude-sonnet-4-5:free"))
        } finally {
            ModelRegistry.installRaw("{}")
        }
    }

    @Test
    fun `api_json 投票合并与压平`() {
        val api = """
        {
          "anthropic": {"models": {
            "claude-sonnet-4-5": {"tool_call": true, "reasoning": true,
              "modalities": {"input": ["text", "image"], "output": ["text"]},
              "limit": {"context": 200000}},
            "x/claude-sonnet-4-5": {"tool_call": false, "reasoning": true,
              "modalities": {"input": ["text"], "output": ["text"]}}
          }},
          "openrouter": {"models": {
            "anthropic/claude-sonnet-4-5": {"canonical_model_id": "claude-sonnet-4-5",
              "tool_call": true, "reasoning": false,
              "modalities": {"input": ["text"], "output": ["text"]}}
          }}
        }
        """.trimIndent()
        val slim = ModelRegistry.buildFromApiJson(api)
        ModelRegistry.installRaw(slim)
        try {
            // 同键三条（完整 id 两条 + canonical 一条）：工具 2/3 多数=支持；
            // 读图 1/3 少数=不支持；思考 2/3=支持；上下文 200000 唯一记录。
            val info = ModelRegistry.find("claude-sonnet-4-5")!!
            assertTrue(info.tools)
            assertTrue(info.reasoning)
            assertFalse(info.visionInput)
            assertEquals(200_000, info.contextLength)
        } finally {
            ModelRegistry.installRaw("{}")
        }
    }

    @Test
    fun `坏数据静默拒绝`() {
        assertFalse(ModelRegistry.installRaw("not json"))
        assertFalse(ModelRegistry.installRaw("[1,2,3]"))
    }
}
