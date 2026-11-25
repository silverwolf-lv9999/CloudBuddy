package cn.cloudbuddy.app.data

/**
 * 消息流 → 模型上下文的投影。
 *
 * 为什么要有这一层：落盘的是**完整消息流**（含思考与工具调用，界面要这个），
 * 而回放给模型的只有「提问 + 回答 + 上一轮用过哪些工具」。两者是同一份数据的
 * 两个视图，不能各存一份——各存一份的下场是两边对不上，用户看到的历史和模型
 * 看到的历史不是同一段对话。
 *
 * 抽成 object 是为了能单测：这段映射错了不会抛异常，只会表现为「模型失忆」。
 */
object Transcript {

    /**
     * 取出可以回放给模型的那部分，按原始顺序。
     *
     * - 用户提问原样保留；
     * - 回答保留，失败 / 中断留下的记录跳过（模型不该把一句报错当成自己说过的话）；
     * - 思考丢弃（模型重新想一遍比读自己的旧思考更有效，也省 token）；
     * - 工具调用折成`note`附在当轮回答前面：不然模型换个轮次就忘了自己读过哪些文件，
     *   会反复读同一个文件、重复执行同一条命令。
     */
    fun turnsOf(messages: List<StoredMessage>): List<AgentTurn> {
        val out = ArrayList<AgentTurn>(messages.size)
        val tools = LinkedHashSet<String>()
        var noted = false
        for (m in messages) {
            when (m.role) {
                ChatRole.USER -> {
                    tools.clear()
                    noted = false
                    out.add(AgentTurn(fromUser = true, text = m.text))
                }

                ChatRole.ASSISTANT -> {
                    if (m.failed) continue
                    // 一轮里可能有好几段正文，工具摘要只挂在第一段上，避免重复。
                    val note = if (!noted && tools.isNotEmpty()) tools.joinToString("；") else null
                    noted = true
                    out.add(AgentTurn(fromUser = false, text = m.text, note = note))
                }

                ChatRole.TOOL -> {
                    val line = m.text.removeSuffix(SessionStore.TOOL_INTERRUPTED).trim()
                    if (line.isNotEmpty()) tools.add(line)
                }

                ChatRole.THOUGHT -> Unit
            }
        }
        return out
    }

    /**
     * 消息流 → 界面消息，重新打开会话时用。
     *
     * 轮次号按消息流**重算**，不信任存储里的值：老版本写进去的编号是错的
     * （提问是第 N 轮、回答变成第 N+1 轮），直接拿来分组会让上一轮的思考
     * 挂到下一轮的折叠条上。
     */
    fun replay(messages: List<StoredMessage>): List<ChatMessage> {
        var turn = -1
        return messages.map { m ->
            if (m.role == ChatRole.USER) turn++
            m.toChatMessage().copy(turn = turn.coerceAtLeast(0))
        }
    }

    /**
     * 反向：把「提问 + 回答」的老格式铺成消息流。
     *
     * 只有两处用得到——旧版本存在 SharedPreferences 里的历史迁移，以及从云端 ACP
     * 历史一次性导入。工具摘要补成一条已完成工具，界面上的样子和它当初跑过的一致。
     */
    fun fromTurns(turns: List<AgentTurn>): List<StoredMessage> {
        val out = ArrayList<StoredMessage>(turns.size + 4)
        var turn = -1
        var seq = 0
        for (t in turns) {
            if (t.fromUser) turn++
            val at = turn.coerceAtLeast(0)
            if (!t.fromUser && !t.note.isNullOrBlank()) {
                out.add(
                    StoredMessage(
                        id = "legacy-tool-${seq++}",
                        role = ChatRole.TOOL,
                        text = t.note,
                        toolState = ToolState.DONE,
                        turn = at,
                    )
                )
            }
            out.add(
                StoredMessage(
                    id = "legacy-${seq++}",
                    role = if (t.fromUser) ChatRole.USER else ChatRole.ASSISTANT,
                    text = t.text,
                    turn = at,
                    failed = t.error,
                )
            )
        }
        return out
    }
}

/**
 * [AgentMemory] 的实现：模型上下文从消息流里派生，不再单独存一份对话历史。
 *
 * 旧版本把历史存在 SharedPreferences（[AgentMemoryStore]），升级上来的用户
 * 第一次打开老会话时在这里一次性搬到消息流里，之后只认消息流。
 */
class TranscriptMemory(
    private val store: SessionStore,
    private val legacy: AgentMemory? = null,
) : AgentMemory {

    override fun load(conversationId: String): List<AgentTurn> {
        migrate(conversationId)
        return Transcript.turnsOf(store.messages(conversationId))
    }

    /**
     * 追加一条。
     *
     * 去重有两条规则：
     * 1. 连续重复（上一条就是同角色同文本）：提问有两个写入入口（仓储拿到沙箱前
     *    先记一笔、agent 真正发请求前再记一笔），漏一处就是一轮消息消失；
     * 2. **本轮内已有同文本消息**：流式回答是仓储边收边写进消息流的，agent 收尾
     *    时还会再 remember 一次「完整回答」——中间隔着思考消息，规则 1 拦不住，
     *    重开会话就会看到两条一模一样的回答。只看本轮（最后一条提问之后），
     *    跨轮的重复回答是正常对话，不能误杀。
     */
    override fun append(conversationId: String, turn: AgentTurn) {
        migrate(conversationId)
        val role = if (turn.fromUser) ChatRole.USER else ChatRole.ASSISTANT
        val existing = store.messages(conversationId)
        val last = existing.lastOrNull()
        if (last != null && last.role == role && last.text == turn.text) return
        // 同一句话的两个版本：界面先写了干净的显示文本（没有 <attachments> 提示块），
        // agent 发请求前又记了带提示块的全文。不能追加成第二条（重进会话会看到
        // 两句一样的提问），就地升级：text 换成全文（模型回放用），display 与
        // 附件元数据保留（界面用）。
        if (last != null && last.role == role && turn.display.isNotBlank() && last.text == turn.display) {
            store.replace(
                conversationId,
                existing.dropLast(1) + last.copy(text = turn.text, display = turn.display),
            )
            return
        }
        val lastUserIndex = existing.indexOfLast { it.role == ChatRole.USER }
        if (lastUserIndex >= 0) {
            val sameInTurn = existing.subList(lastUserIndex + 1, existing.size)
                .any { it.role == role && it.text == turn.text && !it.failed }
            if (sameInTurn) return
        }
        val users = existing.count { it.role == ChatRole.USER }
        // 轮次编号跟着界面走：提问开启新的一轮，其余消息归到当前轮（第一条提问是 0）。
        val turnNo = if (turn.fromUser) users else (users - 1).coerceAtLeast(0)
        store.append(
            conversationId,
            StoredMessage(
                id = "m-" + System.nanoTime(),
                role = role,
                text = turn.text,
                display = turn.display,
                turn = turnNo,
                failed = turn.error,
            )
        )
    }

    /**
     * 整份替换，**只给一次性导入用**（云端历史搬过来、旧格式迁移）。
     *
     * 别拿它做「裁剪历史」：那会把思考与工具过程一起抹掉，重进会话就只剩干巴巴的问答。
     * 上下文长度由 [SessionAgent] 在拼 prompt 时截取，存储层不做裁剪。
     */
    override fun save(conversationId: String, turns: List<AgentTurn>) {
        store.replace(conversationId, Transcript.fromTurns(turns))
    }

    override fun clear(conversationId: String) {
        store.clear(conversationId)
    }

    /** 老会话搬家：只在消息流为空、旧存储有内容时执行一次。 */
    private fun migrate(conversationId: String) {
        if (store.has(conversationId)) return
        val old = legacy?.load(conversationId).orEmpty()
        if (old.isEmpty()) return
        store.replace(conversationId, Transcript.fromTurns(old))
    }
}
