package cn.cloudbuddy.app.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 一段提示词插在哪儿。
 *
 * 取值参考 rikkahub 的 `InjectionPosition`，但只保留对**编程 agent**有意义的三档：
 * 它的 `TOP_OF_CHAT` / `AT_DEPTH` 是为角色扮演准备的（把设定按深度塞进对话里），
 * 我们的场景是「编码规范」「项目约定」这类常驻内容，放在系统提示词两侧就够了。
 *
 * 结构上留的是字符串而不是布尔，以后加档位（比如「只在第一轮」）不用改存储格式。
 */
enum class PromptPosition(val key: String, val label: String, val hint: String) {
    BEFORE_SYSTEM("before_system", "系统提示词之前", "适合放身份与全局规矩"),
    AFTER_SYSTEM("after_system", "系统提示词之后", "放项目约定、编码规范，模型最买账"),
    BEFORE_USER("before_user", "每条消息之前", "贴在这句话前面，临时提醒，不占系统提示词");

    companion object {
        fun of(key: String?): PromptPosition = entries.firstOrNull { it.key == key } ?: AFTER_SYSTEM
    }
}

/**
 * 一条提示词片段。
 *
 * 有了它，「编码规范」「这次别动数据库」「回答用中文」这类内容不必再挤进同一个
 * 系统提示词文本框：可以各自开关、各自归位、各自调顺序。全局系统提示词仍然保留，
 * 它是「这次任务的总体要求」，片段是「一批可以复用的补充」——两件事，分开管。
 */
data class PromptPreset(
    val id: String,
    val name: String,
    val content: String,
    val enabled: Boolean = true,
    /** 见 [PromptPosition]；存字符串，老版本读到未知值时回落到「系统提示词之后」。 */
    val position: String = PromptPosition.AFTER_SYSTEM.key,
    /** 越大越靠前（与 rikkahub 的 `sortedByDescending { priority }` 一致）。 */
    val priority: Int = 0,
) {
    val placed: PromptPosition get() = PromptPosition.of(position)

    /** 列表里的一行摘要：把换行压平，避免一条多行规范把整张卡片撑开。 */
    val summary: String get() = content.replace(Regex("\\s+"), " ").trim()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("n", name)
        .put("c", content)
        .put("e", enabled)
        .put("p", position)
        .put("pri", priority)

    companion object {
        fun blank(): PromptPreset = PromptPreset(
            id = UUID.randomUUID().toString(),
            name = "",
            content = "",
        )

        fun fromJson(o: JSONObject): PromptPreset? {
            val id = o.optString("id").ifBlank { return null }
            val content = o.optString("c")
            if (content.isBlank()) return null
            return PromptPreset(
                id = id,
                name = o.optString("n").ifBlank { "未命名片段" },
                content = content,
                enabled = o.optBoolean("e", true),
                position = o.optString("p").ifBlank { PromptPosition.AFTER_SYSTEM.key },
                priority = o.optInt("pri", 0),
            )
        }
    }
}

/**
 * 提示词片段表。
 *
 * 与 MCP 表一样存 SharedPreferences 的 JSON 数组：条数很少（几条到几十条），
 * 引数据库不值得；而每一段文本都不长，整份重写的开销可以忽略。
 */
class PromptStore(private val prefs: SharedPreferences) {

    fun list(): List<PromptPreset> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let(PromptPreset::fromJson)
        }
    }

    fun save(list: List<PromptPreset>) {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    /** 新增或按 id 覆盖。 */
    fun upsert(preset: PromptPreset) {
        val current = list().toMutableList()
        val index = current.indexOfFirst { it.id == preset.id }
        if (index >= 0) current[index] = preset else current.add(preset)
        save(current)
    }

    fun remove(id: String) {
        save(list().filterNot { it.id == id })
    }

    fun setEnabled(id: String, enabled: Boolean) {
        save(list().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    /**
     * 上下挪一位。
     *
     * 交换的是 priority 而不是列表位置：真正决定顺序的是 priority（同值才看列表顺序），
     * 只改位置的话，两条 priority 相同的片段永远不会换序。
     */
    fun move(id: String, up: Boolean) {
        val sorted = ordered()
        val index = sorted.indexOfFirst { it.id == id }
        if (index < 0) return
        val swapWith = if (up) index - 1 else index + 1
        if (swapWith !in sorted.indices) return
        val a = sorted[index]
        val b = sorted[swapWith]
        // 同 priority 时给一个能拉开差距的值，否则交换完排序不变。
        val pa = if (a.priority == b.priority) b.priority + if (up) 1 else -1 else b.priority
        val pb = a.priority
        save(
            list().map {
                when (it.id) {
                    a.id -> it.copy(priority = pa)
                    b.id -> it.copy(priority = pb)
                    else -> it
                }
            }
        )
    }

    /** 真正参与拼装的片段：启用且按 priority 降序。 */
    fun enabled(): List<PromptPreset> = ordered().filter { it.enabled }

    /** 全部片段按注入顺序排列（界面列表也用这个顺序）。 */
    fun ordered(): List<PromptPreset> = list().sortedByDescending { it.priority }

    private companion object {
        const val KEY = "prompt_presets"
    }
}

/**
 * 拼提示词的纯函数。
 *
 * 抽出来是因为它错了不会抛异常，只会让模型「变笨」——这类逻辑必须能单测。
 *
 * 顺序：`系统提示词之前` 的片段 → 全局系统提示词（含沙箱规则） → `系统提示词之后` 的片段。
 * 前两者用空行隔开；片段之间也用空行，避免两条规范粘成一句读不通的话。
 */
object PromptComposer {

    fun system(custom: String, presets: List<PromptPreset>): String {
        val before = blocks(presets, PromptPosition.BEFORE_SYSTEM)
        val after = blocks(presets, PromptPosition.AFTER_SYSTEM)
        val base = CustomModelConfig.composeSystemPrompt(custom)
        return (before + base + after)
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
    }

    /** 贴在每条用户消息前面的片段；没有就返回空串。 */
    fun userPrefix(presets: List<PromptPreset>): String =
        blocks(presets, PromptPosition.BEFORE_USER).joinToString("\n\n")

    private fun blocks(presets: List<PromptPreset>, position: PromptPosition): List<String> =
        presets
            .filter { it.enabled && it.placed == position && it.content.isNotBlank() }
            .sortedByDescending { it.priority }
            .map { it.content.trim() }
}
