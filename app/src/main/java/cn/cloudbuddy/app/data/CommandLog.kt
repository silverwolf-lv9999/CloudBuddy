package cn.cloudbuddy.app.data

/**
 * 一条命令的来源。
 *
 * 终端里两种来源要能区分开：agent 自己跑的那些是「它为什么卡住了」的答案，
 * 用户手敲的是自己的调试动作。混在一起看就是一锅粥。
 */
enum class CmdSource {
    /** 模型通过工具调用触发。 */
    AGENT,

    /** 用户在终端里自己敲的。 */
    MANUAL,
}

/**
 * 一次沙箱命令的完整记录。
 *
 * 之前这些内容只存在于拼给模型的那段文本里，用户完全看不到——
 * 自定义模式下模型说「我已经跑过了」，用户没有任何办法核实。
 */
data class CmdEntry(
    /** 自增序号，UI 拿来当 LazyColumn 的 key，也用来中断指定命令。 */
    val id: Long,
    /** 原始命令（未截断，可能是多行脚本）。 */
    val command: String,
    val source: CmdSource,
    /** 开始执行的时间（毫秒时间戳）。 */
    val startedAt: Long,
    val durationMs: Long,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    /** 协议层/网络层错误，比如 HTTP 401、执行超时。与 stderr 不是一回事。 */
    val error: String? = null,
    /** 输出被截断过（首尾保留、中间省略）。 */
    val truncated: Boolean = false,
    /**
     * 用户手动中断。
     *
     * 单独一个字段而不是塞进 [error]：中断不是失败——`ping` 跑了十秒我按停，
     * 那十秒的输出仍然是有效结果，标成红字错误会让整块记录看起来像出错了。
     */
    val aborted: Boolean = false,
) {
    val ok: Boolean get() = error == null && !aborted && (exitCode == null || exitCode == 0)

    /** 单行摘要，终端里折叠状态下显示。 */
    fun summary(maxChars: Int = 90): String {
        val one = command.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= maxChars) one else one.take(maxChars) + "…"
    }

    /** 一整段可复制的纯文本，粘贴出去还能看懂。 */
    fun toPlainText(): String = buildString {
        append("$ ")
        append(command)
        append('\n')
        // 复制出去的是给人看/给人贴的，转义序列只会变成乱码，这里统一剥掉。
        if (stdout.isNotEmpty()) append(Ansi.plain(stdout)).append('\n')
        if (stderr.isNotEmpty()) append("[stderr]\n").append(Ansi.plain(stderr)).append('\n')
        if (error != null) append("[error] ").append(error).append('\n')
        append("[exit ")
        append(exitCode?.toString() ?: "?")
        append(" · ")
        append(durationMs)
        append("ms]")
        if (aborted) append("（已中断）")
        if (truncated) append("（输出已截断）")
    }
}

/**
 * 按会话隔离的命令日志（环形缓冲）。
 *
 * 为什么一定要有上限：agent 一次 `cat` 一个几十兆的日志、或者 `npm install` 刷屏，
 * 都能把手机的堆直接打满。这里两道闸——单条输出按字符数截断，整个会话只留最近
 * [maxEntries] 条。沙箱那边该跑还是跑完了，只是本地不留全量。
 */
class CommandLog(
    private val maxEntries: Int = 200,
    private val maxStreamChars: Int = 16_000,
) {

    private val logs = HashMap<String, ArrayDeque<CmdEntry>>()
    private var seq = 0L

    /** 分配下一个序号；ID 全局唯一，跨会话也不会撞。 */
    @Synchronized
    fun nextId(): Long = ++seq

    /**
     * 写入一条记录，返回真正入库的版本（可能已截断）。
     *
     * 入参 [stdout] / [stderr] 给的是原始文本，截断在这里统一做，
     * 调用方不用关心上限怎么算。
     */
    @Synchronized
    fun append(key: String, entry: CmdEntry): CmdEntry {
        if (key.isBlank()) return entry
        val (out, cutOut) = clamp(entry.stdout)
        val (err, cutErr) = clamp(entry.stderr)
        val stored = entry.copy(
            stdout = out,
            stderr = err,
            truncated = entry.truncated || cutOut || cutErr,
        )
        val q = logs.getOrPut(key) { ArrayDeque() }
        q.addLast(stored)
        while (q.size > maxEntries) q.removeFirst()
        return stored
    }

    /**
     * 某个分区（会话 × 标签）的全部记录，按时间正序。返回副本，UI 拿不到内部队列。
     *
     * 键用 [keyOf] 拼，不用裸会话 id——一个会话可以开多个终端标签，
     * 共用一把键会让所有标签看到同一份记录。
     */
    @Synchronized
    fun of(key: String): List<CmdEntry> =
        logs[key]?.toList() ?: emptyList()

    @Synchronized
    fun clear(key: String) {
        logs.remove(key)
    }

    companion object {
        /** 日志分区键。`#` 不会出现在会话 id 里（都是服务端下发的 id）。 */
        fun keyOf(conversationId: String, tabId: Long): String = "$conversationId#$tabId"
    }

    /**
     * 会话被删除时调用，避免几十个死会话的日志常驻内存。
     *
     * 一个会话可能有多份日志（每个终端标签一份），所以按前缀一起清；
     * 只删会话本身会漏掉标签的日志。
     */
    @Synchronized
    fun forget(conversationId: String) {
        val prefix = "$conversationId#"
        logs.keys.filter { it == conversationId || it.startsWith(prefix) }.forEach { logs.remove(it) }
    }

    @Synchronized
    fun sizeOf(key: String): Int = logs[key]?.size ?: 0

    /**
     * 头尾保留式截断。
     *
     * 只留尾部会丢报错开头，只留头部会丢 `npm install` 的最后结论，
     * 所以两头各留一半，中间插一句省略说明——让读的人知道少看了多少。
     */
    private fun clamp(text: String): Pair<String, Boolean> {
        if (text.length <= maxStreamChars) return text to false
        val keep = maxStreamChars / 2
        val head = text.take(keep)
        val tail = text.takeLast(keep)
        val omitted = text.length - head.length - tail.length
        return (head + "\n…（省略 $omitted 字符）…\n" + tail) to true
    }
}
