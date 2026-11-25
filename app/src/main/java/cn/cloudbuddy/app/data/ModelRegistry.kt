package cn.cloudbuddy.app.data

import android.content.res.AssetManager
import org.json.JSONObject

/**
 * 模型能力目录（照 RikkaHub 的 ModelsDevCatalog + ModelRegistry 机制）。
 *
 * 数据来自 models.dev（https://models.dev/api.json，社区维护的公开模型目录，
 * 一万多条模型记录带模态 / 工具调用 / 思考 / 上下文长度）。打包进 assets 一份快照，
 * 应用内还能从同一地址刷新（见 [buildFromApiJson] 与 BuddyRepository.refreshModelCatalog）。
 *
 * 同一个模型会出现在多个提供商下、id 写法也各不相同（`claude-sonnet-4-5`、
 * `anthropic/claude-sonnet-4.5`、OpenRouter 的 `:free` 后缀渠道），所以建索引时
 * 每条记录按「完整 id / 去前缀名称 / canonical id 及其去前缀形式」多键登记，
 * 同键多条按多数投票合并——漏标能力的厂商多，票数持平时算支持。
 *
 * 快照格式（自己生成，非 models.dev 原文）：`{"模型键": [读图入,读图出,工具,思考,上下文K]}`。
 */
object ModelRegistry {

    /** 查到的模型元信息。[contextLength] 为 0 表示目录里没有记录。 */
    data class Info(
        val visionInput: Boolean,
        val visionOutput: Boolean,
        val tools: Boolean,
        val reasoning: Boolean,
        val contextLength: Int,
    )

    @Volatile
    private var index: Map<String, IntArray> = emptyMap()

    /** 启动时装载：先文件（远程刷新过的），没有再落回 assets 快照。都失败就保持空目录。 */
    fun install(assets: AssetManager, cached: String?) {
        if (!cached.isNullOrBlank() && tryInstall(cached)) return
        runCatching {
            assets.open(ASSET_NAME).use { stream ->
                tryInstall(stream.readBytes().decodeToString())
            }
        }
    }

    /** 运行中替换目录（远程刷新成功后调用），失败保持旧数据。 */
    fun installRaw(raw: String): Boolean = tryInstall(raw)

    private fun tryInstall(raw: String): Boolean = runCatching {
        index = parseIndex(raw)
    }.isSuccess

    /**
     * 查模型。命中顺序（照 models.dev 目录的 id 习惯逐级放宽）：
     * 完整 id → 去掉 `:` 后缀（OpenRouter 的 `:free` 渠道）→ 去提供商前缀 → 再去 `:` 后缀。
     */
    fun find(modelId: String): Info? {
        val id = modelId.trim().lowercase()
        if (id.isEmpty()) return null
        val name = id.substringAfterLast('/')
        val arr = index[id]
            ?: index[id.substringBefore(':')]
            ?: index[name]
            ?: index[name.substringBefore(':')]
            ?: return null
        return Info(
            visionInput = arr[0] == 1,
            visionOutput = arr[1] == 1,
            tools = arr[2] == 1,
            reasoning = arr[3] == 1,
            contextLength = arr[4] * 1000,
        )
    }

    /**
     * 把 models.dev 的 api.json 全文压成索引 JSON（纯函数，单测覆盖投票合并）。
     * 返回文本即 [parseIndex] 能读回的快照格式。
     */
    fun buildFromApiJson(raw: String): String {
        val root = JSONObject(raw)
        // key -> [总票数, 读图入, 读图出, 工具, 思考, ctx -> 票数]
        val votes = HashMap<String, IntArray>()
        val ctxVotes = HashMap<String, MutableMap<Int, Int>>()

        fun vote(key: String, vin: Boolean, vout: Boolean, tools: Boolean, reas: Boolean, ctx: Int) {
            val v = votes.getOrPut(key) { IntArray(5) }
            v[0] += 1
            if (vin) v[1] += 1
            if (vout) v[2] += 1
            if (tools) v[3] += 1
            if (reas) v[4] += 1
            if (ctx > 0) ctxVotes.getOrPut(key, { HashMap() }).merge(ctx, 1, Int::plus)
        }

        val providerKeys = root.keys()
        while (providerKeys.hasNext()) {
            val models = root.optJSONObject(providerKeys.next())?.optJSONObject("models") ?: continue
            val it = models.keys()
            while (it.hasNext()) {
                val mid = it.next()
                val m = models.optJSONObject(mid) ?: continue
                val mod = m.optJSONObject("modalities")
                val vin = mod?.optJSONArray("input")?.let { a -> (0 until a.length()).any { a.optString(it) == "image" } } ?: false
                val vout = mod?.optJSONArray("output")?.let { a -> (0 until a.length()).any { a.optString(it) == "image" } } ?: false
                val tools = m.optBoolean("tool_call", false)
                val reas = m.optBoolean("reasoning", false)
                val ctx = m.optJSONObject("limit")?.optInt("context", 0) ?: 0
                val keys = LinkedHashSet<String>()
                val low = mid.lowercase()
                keys.add(low)
                keys.add(low.substringAfterLast('/'))
                val canon = m.optString("canonical_model_id").trim().lowercase()
                if (canon.isNotEmpty()) {
                    keys.add(canon)
                    keys.add(canon.substringAfterLast('/'))
                }
                keys.forEach { if (it.isNotEmpty()) vote(it, vin, vout, tools, reas, ctx) }
            }
        }

        val out = JSONObject()
        votes.forEach { (key, v) ->
            val total = v[0]
            fun maj(c: Int) = c * 2 >= total
            val ctxMap = ctxVotes[key].orEmpty()
            // 票数最多的上下文长度，并列取大的
            val ctx = ctxMap.maxWithOrNull(compareBy({ it.value }, { it.key }))?.key ?: 0
            out.put(
                key,
                org.json.JSONArray()
                    .put(if (maj(v[1])) 1 else 0)
                    .put(if (maj(v[2])) 1 else 0)
                    .put(if (maj(v[3])) 1 else 0)
                    .put(if (maj(v[4])) 1 else 0)
                    .put(if (ctx > 0) ctx / 1000 else 0),
            )
        }
        return out.toString()
    }

    private fun parseIndex(raw: String): Map<String, IntArray> {
        val obj = JSONObject(raw)
        val out = HashMap<String, IntArray>(obj.length())
        val it = obj.keys()
        while (it.hasNext()) {
            val key = it.next()
            val a = obj.optJSONArray(key) ?: continue
            if (a.length() < 5) continue
            out[key] = IntArray(5) { i -> a.optInt(i) }
        }
        return out
    }

    private const val ASSET_NAME = "models_dev_slim.json"
}
