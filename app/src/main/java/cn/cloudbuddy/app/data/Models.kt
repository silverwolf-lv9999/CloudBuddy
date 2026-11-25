package cn.cloudbuddy.app.data

import org.json.JSONArray
import org.json.JSONObject

/** 云端会话在客户端的呈现状态（由 ACP 事件与 REST 轮询共同决定）。 */
enum class TurnState { IDLE, RUNNING, WAITING, DONE, FAILED, STOPPED }

enum class ChatRole { USER, ASSISTANT, THOUGHT, TOOL }

/**
 * 一条对话消息。TOOL 只保留标题与状态，避免把工具原始 JSON 铺满屏幕。
 *
 * [turn] 是「轮次」编号（用户一次提问 + 云端这一轮的完整过程）。
 * 界面靠它把同一轮里的思考 / 工具调用 / 过程叙述收进一个可展开的组，
 * 只留下用户提问与这一轮的最终回答。
 */
data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val toolState: ToolState = ToolState.NONE,
    val streaming: Boolean = false,
    val turn: Int = 0,
    /**
     * 这条不是模型的正式回答，而是「这一轮失败了 / 被中断了」的留痕。
     *
     * 单独一个字段而不是塞进 [text]：失败记录要显示（否则重进会话时这一轮凭空消失），
     * 但回放给模型时必须跳过——模型不该把一句报错当成自己上一轮说过的话。
     */
    val failed: Boolean = false
)

enum class ToolState { NONE, RUNNING, DONE, FAILED }

/** 云端产物（沙箱里的文件）。 */
data class Artifact(
    val name: String,
    val uri: String,
    val mimeType: String? = null,
    val size: Long? = null,
    val downloadUrl: String? = null
) {
    /** 沙箱内绝对路径，非沙箱文件为 null。 */
    val sandboxPath: String? get() = SandboxPath.of(uri)
}

/** 提问里的一个选项。`value` 是回传给云端的东西，权限请求里它是 `allow_once` 这类机器值。 */
data class AskOption(
    val label: String,
    val description: String? = null,
    val value: String = label
)

/** 一组提问里的单个问题。云端一次可以问 1~4 个，多选时要等用户按「提交」。 */
data class AskItem(
    val question: String,
    val header: String? = null,
    val options: List<AskOption> = emptyList(),
    val multiSelect: Boolean = false,
    /** 云端给的问题标识，回传时做 `answers` 字典的 key；缺省用 `q-序号`。 */
    val id: String = ""
)

/** 用户对某个问题的选择：勾了哪些选项，或者自己填了什么。 */
data class AskPick(
    val labels: List<String> = emptyList(),
    val values: List<String> = emptyList(),
    val custom: String? = null
)

/** 一次提问的完整回答，按下标与 [AskQuestion.allItems] 对应。 */
data class AskAnswer(val picks: List<AskPick>) {
    fun isBlank(): Boolean = picks.all { it.labels.isEmpty() && it.custom.isNullOrBlank() }
}

/** 会话里的提问（askuser / 中断请求）。 */
data class AskQuestion(
    val question: String,
    val header: String? = null,
    val options: List<AskOption> = emptyList(),
    val requestId: String? = null,
    /** 完整问题列表。只有一问时与上面三个字段等价（旧载荷可能没有 `items`）。 */
    val items: List<AskItem> = emptyList()
) {
    /** 统一成一问或多问的形状，界面不必为两种载荷写两套渲染。 */
    val allItems: List<AskItem>
        get() = items.ifEmpty { listOf(AskItem(question, header, options)) }

    /**
     * 把选择拼成云端能读的一段话：单问直接给标签，多问按「问题 → 答案」逐行列出。
     *
     * 回传通道 `_codebuddy.ai/resolveInterruption` 的 `answer` 只有一个字符串字段，
     * 结构化数据由 [AcpConnection.resolve] 另外放进 `answers` 数组带上。
     */
    fun answerText(answer: AskAnswer): String {
        val list = allItems
        if (list.size == 1) return answer.picks.firstOrNull()?.let { describe(it) }.orEmpty()
        return list.mapIndexed { index, item ->
            val title = item.header?.takeIf { it.isNotBlank() } ?: item.question.take(18)
            "$title：${answer.picks.getOrNull(index)?.let { describe(it) }.orEmpty().ifBlank { "未选择" }}"
        }.joinToString("\n")
    }

    private fun describe(pick: AskPick): String {
        val chosen = pick.labels.toMutableList()
        pick.custom?.takeIf { it.isNotBlank() }?.let { chosen += it }
        return chosen.joinToString("、")
    }
}

/**
 * 权限类提问的选项在协议里是机器值（`allow_once` / `reject_always`），
 * 原样显示给用户等于没翻译过。
 */
fun askOptionLabel(kind: String, fallback: String): String = when (kind) {
    "allow_once", "allow-once" -> "允许一次"
    "allow_always", "allow-always" -> "总是允许"
    "reject_once", "reject-once" -> "拒绝一次"
    "reject_always", "reject-always" -> "总是拒绝"
    else -> fallback
}

/** 云端会话。`link`/`token` 就是 ACP 长连接的接入点与令牌。 */
data class CloudSession(
    val conversationId: String,
    val link: String,
    val token: String,
    val cwd: String? = null,
    val sandboxId: String? = null,
    val dataEndpoint: String? = null,
    val acpSessionId: String = conversationId,
    val model: String? = null
)

data class Conversation(
    val id: String,
    val title: String,
    val state: TurnState = TurnState.IDLE,
    val model: String? = null,
    val updatedAt: Long = 0L,
    val preview: String = "",
    val artifacts: List<Artifact> = emptyList(),
    val ask: AskQuestion? = null,
    val error: String? = null
)

data class ModelChoice(
    val id: String,
    val name: String,
    val description: String? = null,
    val credits: String? = null,
    val isDefault: Boolean = false
)

data class AccountInfo(
    val nickname: String = "",
    val avatarUrl: String = "",
    val phone: String = ""
)

data class UsagePack(val name: String, val total: Double, val left: Double, val expireAt: String? = null)

/**
 * 每日签到状态。字段对齐官方 `POST /billing/meter/checkin-activity-status`：
 * `active` / `today_checked_in` / `streak_days` / `daily_credit` / `today_credit`。
 */
data class CheckinInfo(
    val active: Boolean = true,
    val todayCheckedIn: Boolean = false,
    val streakDays: Int = 0,
    val dailyCredit: Int = 0,
    val todayCredit: Int = 0
)

/** 成长中心：积分余额与等级（`/activity/growth/energy` + `/v2/activity/growth/profile`）。 */
data class GrowthInfo(
    val balance: Int = 0,
    val level: Int = 0,
    val levelName: String = "",
    val completed: Int = 0,
    val total: Int = 0
)

/** 沙箱工作区里的一个条目。 */
data class SandboxEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long = 0L,
    val modifiedAt: Long = 0L,
    val mimeType: String? = null
)

/**
 * 从 ACP `session/load` / `session/new` 的响应里取可用模型。
 *
 * 官方客户端 `extractAvailableModels()` 就是这么做的：这是**最可靠**的模型来源——
 * 只要 ACP 连得上（能正常聊天），就一定拿得到，不依赖 console 的模型配置接口。
 */
object AcpModels {

    fun parse(result: JSONObject?): Pair<List<ModelChoice>, String?> {
        if (result == null) return emptyList<ModelChoice>() to null
        val models = result.optJSONObject("models") ?: result.optJSONObject("config")
            ?: return emptyList<ModelChoice>() to null
        val array: JSONArray? =
            models.optJSONObject("_meta")?.optJSONObject("codebuddy.ai")
                ?.optJSONArray("availableModels")?.takeIf { it.length() > 0 }
                ?: models.optJSONArray("availableModels")?.takeIf { it.length() > 0 }
                ?: result.optJSONObject("_meta")?.optJSONObject("codebuddy.ai")
                    ?.optJSONArray("availableModels")
        val list = array?.let { arr ->
            (0 until arr.length()).mapNotNull { i -> parseChoice(arr.opt(i)) }
        }.orEmpty()
        val current = sequenceOf(
            models.optString("currentModelId"),
            result.optString("currentModelId"),
            models.optString("modelId")
        ).firstOrNull { it.isNotBlank() && it != "null" }
        return list to current
    }

    private fun parseChoice(raw: Any?): ModelChoice? {
        val o = when (raw) {
            is JSONObject -> raw
            is String -> return if (raw.isBlank()) null else ModelChoice(raw, raw)
            else -> return null
        }
        val meta = o.optJSONObject("_meta")?.optJSONObject("codebuddy.ai")
        val id = sequenceOf(
            o.optString("id"),
            o.optString("modelId"),
            o.optString("model"),
            meta?.optString("id").orEmpty()
        ).firstOrNull { it.isNotBlank() && it != "null" } ?: return null
        val name = sequenceOf(
            o.optString("name"),
            o.optString("displayName"),
            meta?.optString("name").orEmpty()
        ).firstOrNull { it.isNotBlank() && it != "null" } ?: id
        val description = sequenceOf(
            o.optString("descriptionZh"),
            o.optString("description"),
            meta?.optString("description").orEmpty()
        ).firstOrNull { it.isNotBlank() && it != "null" }
        return ModelChoice(
            id = id,
            name = name,
            description = description,
            credits = o.optString("credits").takeIf { it.isNotBlank() && it != "null" },
            isDefault = o.optBoolean("isDefault") || o.optBoolean("default")
        )
    }
}
