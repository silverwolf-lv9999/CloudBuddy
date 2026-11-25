package cn.cloudbuddy.app.data

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义模型模式的对话历史，按会话存在本机。
 *
 * 为什么要落盘：自定义模式的推理在**本机**完成，云端会话记录里没有这些消息。
 * 不存下来的话，App 一重启（或进程被系统回收）上下文就全丢了，
 * 用户接着问"刚才那个文件改完了吗"，模型完全不知道在说什么。
 *
 * 按 conversationId 分 key：沙箱跟随会话，历史也必须跟着会话走，
 * 不能串到别的沙箱的上下文里。
 *
 * 存在 SharedPreferences 而不是数据库，是因为数据量很小（每会话最多几十轮、
 * 单条有截断），引一个 Room 只为存这点文本不划算。
 */
class AgentMemoryStore(private val prefs: SharedPreferences) : AgentMemory {

    override fun load(conversationId: String): List<AgentTurn> {
        val raw = prefs.getString(key(conversationId), null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<AgentTurn>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val text = o.optString("t")
            if (text.isEmpty()) continue
            out.add(
                AgentTurn(
                    fromUser = o.optBoolean("u", false),
                    text = text,
                    note = o.optString("n").ifBlank { null },
                    // 老数据没有这个字段：默认 false，正好是「正常一轮」。
                    error = o.optBoolean("e", false),
                )
            )
        }
        return out
    }

    override fun save(conversationId: String, turns: List<AgentTurn>) {
        val arr = JSONArray()
        for (t in turns) {
            arr.put(
                JSONObject()
                    .put("u", t.fromUser)
                    .put("t", t.text.take(MAX_TEXT))
                    .put("n", t.note.orEmpty().take(MAX_NOTE))
                    .put("e", t.error)
            )
        }
        prefs.edit().putString(key(conversationId), arr.toString()).apply()
    }

    override fun clear(conversationId: String) {
        prefs.edit().remove(key(conversationId)).apply()
    }

    /** 会话被删除时一并清掉，不留垃圾。 */
    fun clearAll() {
        val edit = prefs.edit()
        for (k in prefs.all.keys) {
            if (k.startsWith(PREFIX)) edit.remove(k)
        }
        edit.apply()
    }

    private fun key(conversationId: String) = PREFIX + conversationId

    private companion object {
        const val PREFIX = "agent_hist:"

        /** 单条回答的截断长度：SharedPreferences 存超长字符串会拖慢读写。 */
        const val MAX_TEXT = 8_000
        const val MAX_NOTE = 300
    }
}
