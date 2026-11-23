package cn.cloudbuddy.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcpModelsTest {

    @Test
    fun `session load result exposes available models`() {
        val result = JSONObject().put(
            "models",
            JSONObject()
                .put("currentModelId", "hy4-preview")
                .put(
                    "availableModels",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("id", "hy4-preview")
                                .put("name", "混元 4")
                                .put("isDefault", true)
                                .put("credits", "x0.21")
                        )
                        .put(JSONObject().put("id", "claude-sonnet").put("descriptionZh", "推理更强"))
                )
        )

        val (models, current) = AcpModels.parse(result)
        assertEquals("hy4-preview", current)
        assertEquals(2, models.size)
        assertEquals("混元 4", models[0].name)
        assertEquals("x0.21", models[0].credits)
        assertTrue(models[0].isDefault)
        assertEquals("claude-sonnet", models[1].id)
        assertEquals("推理更强", models[1].description)
    }

    @Test
    fun `codebuddy meta takes precedence and string ids are accepted`() {
        val result = JSONObject().put(
            "models",
            JSONObject()
                .put("availableModels", JSONArray().put("ignored"))
                .put(
                    "_meta",
                    JSONObject().put(
                        "codebuddy.ai",
                        JSONObject().put("availableModels", JSONArray().put("meta-model"))
                    )
                )
        )
        val (models, _) = AcpModels.parse(result)
        assertEquals(1, models.size)
        assertEquals("meta-model", models.single().id)
    }

    @Test
    fun `missing models block is harmless`() {
        val (models, current) = AcpModels.parse(JSONObject())
        assertTrue(models.isEmpty())
        assertEquals(null, current)
        assertTrue(AcpModels.parse(null).first.isEmpty())
    }
}
