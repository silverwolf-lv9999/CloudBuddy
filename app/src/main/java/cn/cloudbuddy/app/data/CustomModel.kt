package cn.cloudbuddy.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义端点上的一条模型配置。
 *
 * 为什么不是一串 ID：同一个端点上的模型能力差得很远（`deepseek-reasoner` 会思考、
 * `deepseek-chat` 不会；`qwen3` 系列还能调思考预算），一串 ID 只能表达「用哪个」，
 * 表达不了「怎么用」。开源客户端（RikkaHub 等）都是把模型做成对象挂在提供商下面，
 * 每个模型带自己的显示名、能力标记和参数覆盖，这里照着这个结构来。
 *
 * 所有覆盖项都允许为空/0，表示**跟随全局设置**：用户只给某个模型改一处时，
 * 不必把全局那套再抄一遍，改全局时也不会被这些抄本拖住。
 */
data class CustomModel(
    /** 端点上的真实模型 ID，请求时原样下发。 */
    val id: String,
    /** 显示名；空则回落到 [id]。只影响界面，不影响请求。 */
    val name: String = "",
    /** 所属提供商（[LlmProvider.id]）。空 = 旧数据，挂在迁移前的全局端点下。 */
    val providerId: String = "",
    /** 是否是会思考的模型。只用于打标签，以及决定默认要不要显示思考强度。 */
    val reasoning: Boolean = false,
    /** 该模型用的思考强度（见 [ThinkingEffort]）；空表示跟随全局。 */
    val thinkingEffort: String = "",
    /** 该模型的系统提示词；空表示跟随全局。 */
    val systemPrompt: String = "",
    /** 该模型回放的历史轮数；0 表示跟随全局。 */
    val contextTurns: Int = 0,
    /**
     * 能否工具调用（照 RikkaHub 的 ModelAbility.TOOL）。
     * 关掉后引擎不下发 tools 字段——不支持 function calling 的端点/纯聊天模型
     * 用它，否则有些服务端会直接 400 或凭空编造工具名。
     */
    val tools: Boolean = true,
    /**
     * 能否读图（照 RikkaHub 的 Modality.IMAGE 输入）。
     * 开着：聊天里发的图片以 image_url 多模态消息直发给模型；
     * 关着：图片保持旧路径——放进沙箱，模型用工具按路径读。
     */
    val vision: Boolean = false,
) {
    /** 界面上真正显示的名字。 */
    val title: String get() = name.trim().ifBlank { id }

    /**
     * 全局唯一的选择键。不同提供商下可以有同名模型（两个中转都有
     * `deepseek-v3`），选型存裸 ID 会串台——存储与选型一律用这个键，
     * 请求时再拆回模型 ID。
     */
    val selKey: String get() = if (providerId.isBlank()) id else "$providerId/$id"

    /** 有没有任何覆盖项——列表里据此标一个「已自定义」。 */
    val customized: Boolean
        get() = name.isNotBlank() || reasoning || thinkingEffort.isNotBlank() ||
            systemPrompt.isNotBlank() || contextTurns > 0 || !tools || vision

    /** 按空格分词、全部命中的关键词筛选（与 RikkaHub 的 filterByKeywords 同规则）。 */
    fun matches(keywords: List<String>): Boolean = keywords.all {
        id.contains(it, ignoreCase = true) || name.contains(it, ignoreCase = true)
    }

    companion object {
        /**
         * 猜测是不是思考模型。
         *
         * 各家命名没有统一约定（models.dev 那种目录也常有滞后），所以只在**新加入**
         * 一个模型时给个初值，用户随时能改。宁可漏判：把不思考的模型标成思考，
         * 会让用户以为思考参数没生效。
         */
        fun guessReasoning(id: String): Boolean {
            val key = id.lowercase()
            return REASONING_HINTS.any { key.contains(it) }
        }

        /**
         * 猜测能否读图。同样宁可漏判：没标读图的模型发了图走沙箱路径兜底，
         * 标错的会让不支持图片的端点直接报错。
         * 线索是各家多模态命名的公约数：`-vl`（Qwen-VL/GLM-4V）、vision、
         * `4o`（gpt-4o）、omni、gemini / claude（全线多模态）。
         */
        fun guessVision(id: String): Boolean {
            val key = id.lowercase()
            return VISION_HINTS.any { key.contains(it) }
        }

        private val REASONING_HINTS = listOf(
            "reasoner", "reasoning", "thinking", "-think", "qwq",
            "deepseek-r", "-r1", "/r1", "magistral",
            "o1-", "o1", "o3-", "o3", "o4-mini",
        )

        private val VISION_HINTS = listOf(
            "-vl", "vision", "glm-4v", "4o", "omni", "gemini", "claude",
        )

        /** 关键词筛选，空查询返回原列表。 */
        fun filter(models: List<CustomModel>, query: String): List<CustomModel> {
            val keywords = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (keywords.isEmpty()) return models
            return models.filter { it.matches(keywords) }
        }

    fun encode(models: List<CustomModel>): String {
        val array = JSONArray()
        models.forEach { m ->
            if (m.id.isBlank()) return@forEach
            array.put(
                JSONObject().apply {
                    put("id", m.id)
                    if (m.providerId.isNotBlank()) put("p", m.providerId)
                    if (m.name.isNotBlank()) put("name", m.name)
                    if (m.reasoning) put("reasoning", true)
                    if (m.thinkingEffort.isNotBlank()) put("effort", m.thinkingEffort)
                    if (m.systemPrompt.isNotBlank()) put("prompt", m.systemPrompt)
                    if (m.contextTurns > 0) put("turns", m.contextTurns)
                    // 只在偏离默认值时落盘：旧数据解析自然得到 true/false 的默认
                    if (!m.tools) put("tools", false)
                    if (m.vision) put("vision", true)
                }
            )
        }
        return array.toString()
    }

        /**
         * 解析，兼容旧格式。
         *
         * 1.5.x 存的是纯字符串数组 `["deepseek-chat"]`，升级上来的用户的列表不能丢，
         * 所以字符串元素按「只有 ID 的模型」处理。重复条目按选择键去重，空 ID 丢掉。
         */
        fun decode(raw: String?): List<CustomModel> {
            if (raw.isNullOrBlank()) return emptyList()
            val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
            val out = LinkedHashMap<String, CustomModel>()
            for (i in 0 until array.length()) {
                val model = when (val item = array.opt(i)) {
                    is JSONObject -> {
                        val id = item.optString("id").trim()
                        if (id.isBlank()) continue
                        CustomModel(
                            id = id,
                            providerId = item.optString("p").trim(),
                            name = item.optString("name").trim(),
                            reasoning = item.optBoolean("reasoning", false),
                            thinkingEffort = item.optString("effort").trim(),
                            systemPrompt = item.optString("prompt"),
                            contextTurns = item.optInt("turns", 0).coerceAtLeast(0),
                            tools = item.optBoolean("tools", true),
                            vision = item.optBoolean("vision", false),
                        )
                    }

                    is String -> CustomModel(item.trim())
                    else -> continue
                }
                if (model.id.isNotBlank()) out.putIfAbsent(model.selKey, model)
            }
            return out.values.toList()
        }
    }
}
