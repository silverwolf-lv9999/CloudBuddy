package cn.cloudbuddy.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * 提问载荷的解析与回传构造。
 *
 * 云端有三种下发方式，形状却基本一致：
 * - 服务端**请求**客户端方法 `_codebuddy.ai/question`（params = `{toolCallId, schema:{questions}}`）；
 * - `session/request_permission`（权限确认，只有 `options` 没有正文）；
 * - `tool_call` 里 toolName 为 `AskUserQuestion`，问题在 `rawInput.questions`。
 *
 * 三种都收敛成 [AskItem] 列表，界面只处理一种形状。解析写在一处，
 * 否则「多选支持了一边忘了另一边」的 bug 会反复出现（1.0.8 就踩过）。
 */

/** 一次提问请求：谁问的（toolCallId）+ 问了什么。 */
internal data class AskRequest(
    val toolCallId: String?,
    val question: AskQuestion
)

/**
 * 解析 `_codebuddy.ai/question` 的 params，或任何带 `questions` 的载荷。
 * 解析不出问题时返回 null，调用方据此回一个「不支持」而不是把服务端干等 5 分钟。
 */
internal fun parseAskRequest(node: JSONObject): AskRequest? {
    val items = parseAskItems(node)
    if (items.isEmpty()) return null
    val toolCallId = node.optString("toolCallId").ifBlank { node.optString("requestId") }
        .takeIf { it.isNotBlank() }
    val first = items.first()
    return AskRequest(
        toolCallId = toolCallId,
        question = AskQuestion(
            question = first.question.ifBlank { "云端需要你确认后继续" },
            header = first.header,
            options = first.options,
            requestId = toolCallId,
            items = items
        )
    )
}

internal fun parseAskItems(node: JSONObject): List<AskItem> {
    // `_codebuddy.ai/question` 把问题包在 schema 里；其它来源直接挂在根上。
    val root = node.optJSONObject("schema") ?: node
    val items = mutableListOf<AskItem>()
    root.optJSONArray("questions")?.let { array ->
        for (i in 0 until array.length()) {
            val question = array.optJSONObject(i) ?: continue
            val text = question.optString("question")
                .ifBlank { question.optString("prompt") }
                .ifBlank { question.optString("title") }
            if (text.isBlank()) continue
            items += AskItem(
                question = text,
                header = question.optString("header").takeIf { it.isNotBlank() },
                options = parseAskOptions(question.optJSONArray("options")),
                multiSelect = isMultiSelect(question),
                // answers 字典的 key：云端给了 id 就用它，没给就用序号（与官方 q-N 一致）。
                id = question.optString("id").ifBlank { "q-$i" }
            )
        }
    }
    if (items.isEmpty()) {
        // 老形状：question 与 options 直接挂在事件根上。
        val text = node.optString("question")
            .ifBlank { node.optString("prompt") }
            .ifBlank { node.optString("title") }
        val options = parseAskOptions(node.optJSONArray("options"))
        // 权限确认（session/request_permission）只有 options 没有正文，
        // 以前这条分支因为「问题为空」被整条丢掉，卡片根本不弹。
        if (text.isNotBlank() || options.isNotEmpty()) {
            val toolTitle = node.optJSONObject("toolCall")?.optString("title").orEmpty()
            items += AskItem(
                question = text.ifBlank { toolTitle }.ifBlank { "云端请求执行一个操作，需要你确认" },
                header = node.optString("header").takeIf { it.isNotBlank() },
                options = options,
                id = "q-0"
            )
        }
    }
    return items
}

/**
 * 把用户的勾选拼成云端要的 `answers` 字典：`{问题id: 单值 | [多值]}`。
 *
 * 单选必须给字符串、多选必须给数组——官方界面就是这么构造的
 * （`multiSelect ? [...answers] : answers[0]`），给错类型云端认不出选择。
 * 选项都没勾但自己填了文字时，用文字顶上。
 */
internal fun buildAnswers(items: List<AskItem>, answer: AskAnswer): JSONObject {
    val result = JSONObject()
    items.forEachIndexed { index, item ->
        val pick = answer.picks.getOrNull(index) ?: return@forEachIndexed
        val chosen = pick.values.ifEmpty { pick.labels }
        when {
            item.multiSelect -> {
                val values = chosen.toMutableList()
                pick.custom?.takeIf { it.isNotBlank() }?.let { values += it }
                if (values.isNotEmpty()) result.put(item.id, JSONArray(values))
            }
            else -> {
                val single = pick.custom?.takeIf { it.isNotBlank() }
                    ?: chosen.firstOrNull()
                if (!single.isNullOrBlank()) result.put(item.id, single)
            }
        }
    }
    return result
}

/** 官方客户端按名字识别这个工具，且会把 `-` `_` 和空格去掉再比。 */
internal fun isAskUserQuestionTool(name: String): Boolean =
    name.replace(Regex("[-_\\s]"), "").equals("askuserquestion", ignoreCase = true)

private fun parseAskOptions(array: JSONArray?): List<AskOption> {
    if (array == null) return emptyList()
    val result = mutableListOf<AskOption>()
    for (i in 0 until array.length()) {
        // 选项可能是纯字符串，也可能是 {label, value, description}。
        if (array.opt(i) is String) {
            val text = array.optString(i).trim()
            if (text.isNotEmpty()) result += AskOption(text)
            continue
        }
        val option = array.optJSONObject(i) ?: continue
        val kind = option.optString("kind")
        val raw = option.optString("label")
            .ifBlank { option.optString("name") }
            .ifBlank { option.optString("value") }
            .ifBlank { option.optString("optionId") }
            .ifBlank { option.optString("description") }
        if (raw.isBlank()) continue
        result += AskOption(
            label = askOptionLabel(kind, raw),
            description = option.optString("description").takeIf { it.isNotBlank() && it != raw },
            // 回传值优先取机器标识：权限请求认 optionId，普通问题才用原文标签。
            value = option.optString("optionId")
                .ifBlank { option.optString("value") }
                .ifBlank { kind }
                .ifBlank { raw }
        )
    }
    return result
}

private fun isMultiSelect(question: JSONObject): Boolean =
    question.optBoolean("multiSelect") ||
        question.optBoolean("multiple") ||
        question.optString("type").equals("multi", ignoreCase = true) ||
        question.optString("selectionMode").equals("multiple", ignoreCase = true)
