package cn.cloudbuddy.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 一条落盘的消息。
 *
 * 结构与界面上的 [ChatMessage] 一一对应，只多一个写入时间 `at`
 * （重进会话时按它排序、也方便排查"这条到底什么时候写进去的"）。
 *
 * 为什么不直接存 `ChatMessage`：它带着 `streaming` 这种只对当前帧有意义的状态，
 * 落盘没有意义，读回来反而会得到一堆"永远在流式中"的消息。
 */
data class StoredMessage(
    val id: String,
    val role: ChatRole,
    val text: String,
    val toolState: ToolState = ToolState.NONE,
    val turn: Int = 0,
    val at: Long = 0L,
    /** 失败 / 中断留下的记录：照常显示，但不回放给模型。 */
    val failed: Boolean = false,
) {
    fun toChatMessage(): ChatMessage = ChatMessage(
        id = id,
        role = role,
        text = text,
        toolState = toolState,
        turn = turn,
        failed = failed,
    )

    fun encode(): JSONObject = JSONObject()
        .put("i", id)
        .put("r", role.name)
        .put("t", text)
        .put("s", toolState.name)
        .put("turn", turn)
        .put("at", at)
        .put("f", failed)

    companion object {
        fun of(message: ChatMessage, at: Long = System.currentTimeMillis()): StoredMessage =
            StoredMessage(
                id = message.id,
                role = message.role,
                text = message.text,
                toolState = message.toolState,
                turn = message.turn,
                at = at,
                failed = message.failed,
            )

        fun decode(o: JSONObject): StoredMessage? {
            val role = runCatching { ChatRole.valueOf(o.optString("r")) }.getOrNull() ?: return null
            val text = o.optString("t")
            // 空正文的对话消息读回来只会变成一条看不见的气泡，直接丢掉。
            // 工具占位允许为空：它有状态，没文字也是有效的一条。
            if (text.isEmpty() && role != ChatRole.TOOL) return null
            return StoredMessage(
                id = o.optString("i").ifBlank { "m-" + System.nanoTime() },
                role = role,
                text = text,
                toolState = runCatching { ToolState.valueOf(o.optString("s")) }
                    .getOrDefault(ToolState.NONE),
                turn = o.optInt("turn", 0),
                at = o.optLong("at", 0L),
                failed = o.optBoolean("f", false),
            )
        }
    }
}

/**
 * 本机会话的完整消息流存储（一个会话一个 JSON 文件）。
 *
 * 抄的是 rikkahub 的会话框架：**整条消息流才是会话的真相**，界面上看到什么
 * 就存什么——思考、工具调用、回答、失败记录，一条不少，重进时按原顺序放回去。
 * 之前的 `AgentTurn` 只存「提问 + 回答」两句话，于是思考与工具过程一关 App 就没了，
 * 用户看到的会话像是被人删过一半。
 *
 * 为什么不用 Room：rikkahub 用 Room 存 `MessageNodeEntity`（一个节点一行），
 * 那是为了支持分支与局部更新。我们这里一个会话只有一条线，整份快照几十 KB，
 * 引 Room 要加重 KSP、迁移和 DAO 三套东西，换不来什么。文件 + 原子替换足够，
 * 而且没有任何 Android 依赖，JVM 单测直接跑。
 *
 * 线程模型：读写在内存里做（[loaded] 首次从磁盘读一次），写盘走单线程队列，
 * 保证同一会话的落盘顺序与内存一致。UI 线程不会被 IO 挡住。
 */
class SessionStore(
    private val dir: File,
    private val maxMessages: Int = 400,
    private val maxText: Int = 16_000,
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cb-session-store").apply { isDaemon = true }
    },
) {

    private val cache = HashMap<String, MutableList<StoredMessage>>()

    /**
     * 每个会话的落盘代数，`clear` 时 +1。
     *
     * 为什么需要它：`persist` 的快照在调用时取（持锁），但写盘在 IO 线程排着队。
     * 排队期间如果发生 `clear`——文件删了，随后轮到这个写任务——旧消息会被原样写回去，
     * 会话「删了又复活」。写任务带上排队时的代数，落盘前对一下当前代数，对不上就作废。
     */
    private val generations = HashMap<String, Int>()

    /** 某个会话的完整消息流（副本，调用方改不到内部状态）。 */
    @Synchronized
    fun messages(conversationId: String): List<StoredMessage> = loaded(conversationId).toList()

    @Synchronized
    fun has(conversationId: String): Boolean =
        loaded(conversationId).isNotEmpty() || fileOf(conversationId).isFile

    /**
     * 追加一条并落盘。
     *
     * 连续两条完全相同的同角色消息会被合并：提问有两处入口（仓储拿到沙箱前先记一笔、
     * agent 真正发请求前再记一笔），不去重的话模型会以为用户催了一遍。
     */
    @Synchronized
    fun append(conversationId: String, message: StoredMessage): StoredMessage {
        val list = loaded(conversationId)
        val last = list.lastOrNull()
        if (last != null && last.role == message.role && last.text == message.text) return last
        val stored = message.copy(
            text = message.text.take(maxText),
            at = if (message.at > 0) message.at else System.currentTimeMillis(),
        )
        list.add(stored)
        trim(list)
        persist(conversationId)
        return stored
    }

    /** 按 id 原地改一条（工具调用从「运行中」变「已完成」走这里）。 */
    @Synchronized
    fun update(
        conversationId: String,
        messageId: String,
        block: (StoredMessage) -> StoredMessage,
    ): StoredMessage? {
        val list = loaded(conversationId)
        val index = list.indexOfFirst { it.id == messageId }
        if (index < 0) return null
        val old = list[index]
        val updated = block(old)
        if (updated == old) return old
        list[index] = updated
        persist(conversationId)
        return updated
    }

    /** 整份替换（只给「从云端历史一次性导入」用）。 */
    @Synchronized
    fun replace(conversationId: String, messages: List<StoredMessage>) {
        val list = messages
            .map { it.copy(text = it.text.take(maxText)) }
            .toMutableList()
        trim(list)
        cache[conversationId] = list
        persist(conversationId)
    }

    /**
     * 删整个会话。文件删除是同步的：`File.delete()` 只是一个系统调用，贵的是写 JSON；
     * 放到 IO 队列反而会开一个窗口——刚删完立刻读 `messages()`，磁盘文件还在，
     * 整条消息流被加载回来并可能再次落盘，等于「删了又复活」。
     *
     * 同步删还不够：排队中的写任务仍可能在删完之后把旧内容写回去（快照先取、写盘后到），
     * 所以这里同时把代数 +1，让那些任务落盘前自己作废。
     */
    @Synchronized
    fun clear(conversationId: String) {
        cache.remove(conversationId)
        generations[conversationId] = (generations[conversationId] ?: 0) + 1
        runCatching { fileOf(conversationId).delete() }
    }

    @Synchronized
    fun clearAll() {
        val keys = cache.keys.toList()
        cache.clear()
        keys.forEach { generations[it] = (generations[it] ?: 0) + 1 }
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    /**
     * 收尾上一次没跑完的痕迹。打开会话时必须先调它，再读 [messages]。
     *
     * 进程被系统回收时内存里的一切都没了，磁盘上会留下两种残局：
     * 1. 某次工具调用还挂着「运行中」——不处理的话重进会话永远在转圈；
     * 2. 最后一条是用户提问，后面什么都没有——用户会以为回答被吞了。
     *
     * 后者补一条说明，前者标成失败。rikkahub 的 `cancelInterruptedTools()` 干的是同一件事。
     */
    @Synchronized
    fun sealInterrupted(conversationId: String): Boolean {
        val list = loaded(conversationId)
        if (list.isEmpty()) return false
        var changed = false
        for (i in list.indices) {
            val m = list[i]
            if (m.role == ChatRole.TOOL && m.toolState == ToolState.RUNNING) {
                list[i] = m.copy(toolState = ToolState.FAILED, text = m.text + TOOL_INTERRUPTED)
                changed = true
            }
        }
        val last = list.last()
        if (last.role == ChatRole.USER) {
            list.add(
                StoredMessage(
                    id = "seal-" + System.currentTimeMillis(),
                    role = ChatRole.ASSISTANT,
                    text = ORPHAN_NOTICE,
                    turn = last.turn,
                    at = System.currentTimeMillis(),
                    failed = true,
                )
            )
            changed = true
        }
        if (changed) persist(conversationId)
        return changed
    }

    /** 等所有排队的写盘做完（单测用；界面不需要）。 */
    fun flush() {
        runCatching { io.submit {}.get(5, TimeUnit.SECONDS) }
    }

    // ---------- 内部 ----------

    /** 必须在持有锁时调用。 */
    private fun loaded(conversationId: String): MutableList<StoredMessage> =
        cache.getOrPut(conversationId) { readFile(conversationId).toMutableList() }

    /** 必须在持有锁时调用。快照在这里取，而不是在 IO 任务里：取晚了会撞上并发的 clear。 */
    private fun persist(conversationId: String) {
        val snapshot = cache[conversationId]?.toList() ?: return
        val gen = generations.getOrDefault(conversationId, 0)
        io.execute {
            val current = synchronized(this) { generations.getOrDefault(conversationId, 0) }
            if (current == gen) writeFile(conversationId, snapshot)
        }
    }

    private fun readFile(conversationId: String): List<StoredMessage> {
        val file = fileOf(conversationId)
        if (!file.isFile) return emptyList()
        val raw = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray("messages") ?: return emptyList()
        val out = ArrayList<StoredMessage>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            StoredMessage.decode(item)?.let(out::add)
        }
        return out
    }

    /**
     * 原子写：先写临时文件再改名。
     *
     * 直接覆写时如果进程正好被杀，留下的就是半截 JSON，下次打开整个会话读不出来——
     * 那正是我们最想避免的"消息消失"。改名在同一文件系统里是原子的，最多丢最后一次改动。
     */
    private fun writeFile(conversationId: String, messages: List<StoredMessage>) {
        runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) return
            val array = JSONArray()
            for (m in messages) array.put(m.encode())
            val json = JSONObject()
                .put("v", VERSION)
                .put("id", conversationId)
                .put("updatedAt", System.currentTimeMillis())
                .put("messages", array)
                .toString()
            val target = fileOf(conversationId)
            val temp = File(dir, target.name + ".tmp")
            temp.writeText(json)
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) temp.delete()
            }
        }
    }

    /**
     * 篇幅闸门：单条按字符截断，整个会话只留最近 [maxMessages] 条。
     *
     * 超限时**整轮丢弃**而不是逐条从头删：逐条删会留下半轮（一个没有提问的折叠条，
     * 或者一条没有回答的提问），用户看到的会话是残缺的，比少几轮旧消息更让人困惑。
     */
    private fun trim(list: MutableList<StoredMessage>) {
        while (list.size > maxMessages) {
            val firstUser = list.indexOfFirst { it.role == ChatRole.USER }
            // 从第二个提问开始保留，等于丢掉完整的第一轮。
            val cut = (firstUser + 1 until list.size)
                .firstOrNull { list[it].role == ChatRole.USER }
                ?: break
            list.subList(0, cut).clear()
        }
        // 只有一轮却仍然超限（单轮工具调用极多）时，只能从头删。
        while (list.size > maxMessages) list.removeAt(0)
        // 头部必须是提问：留下半轮的过程消息，界面上就是一个没有提问的折叠条。
        while (list.size > 1 && list.first().role != ChatRole.USER) list.removeAt(0)
    }

    private fun fileOf(conversationId: String): File {
        val safe = conversationId
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(48)
            .ifBlank { "session" }
        return File(dir, "$safe-${shortHash(conversationId)}.json")
    }

    /** 会话 id 里出现中文或斜杠时清洗后的名字可能撞车，带一段哈希兜底。 */
    private fun shortHash(conversationId: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(conversationId.toByteArray(Charsets.UTF_8))
        return digest.take(6).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val VERSION = 1

        /** 工具调用被进程回收打断时，补在原文后面的标记。 */
        const val TOOL_INTERRUPTED = " · 已中断"

        /** 上一轮提问之后什么都没有时补的说明。 */
        const val ORPHAN_NOTICE = "上一轮没有跑完（应用被关闭或进程被回收），这条消息之后的内容没有保存。可以重新发送。"
    }
}
