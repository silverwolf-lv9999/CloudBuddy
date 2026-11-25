package cn.cloudbuddy.app.data

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.agents.core.tools.ToolRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import java.util.Base64

/**
 * 一次工具调用的进度事件。UI 靠它把「正在跑什么」显示出来，
 * 而不是只能读一句拼好的字符串。
 */
data class SandboxToolEvent(
    /** 工具名，如 run_shell / edit_file。 */
    val tool: String,
    /** 人类可读的详情，如命令前 60 字、被编辑的文件路径。 */
    val detail: String,
    /** true 表示开始执行，false 表示已结束。 */
    val running: Boolean,
)

/**
 * 暴露给模型的工具集，全部落在沙箱里执行。
 *
 * 实现选择：一律走 `sh -c`，而不是再调一套文件 RPC。原因是
 * 文件网关的 write 用 multipart、read 用 query 参数，而工具调用是高频路径，
 * 走 shell 只要保证路径转义正确就只有一种失败模式——转义见 [ShellQuote]。
 *
 * 所有工具都绑定到**某个会话的某个沙箱**（endpoint + token 在构造时确定），
 * 不同会话的 agent 各持一份，天然隔离，不存在串台。
 */
class SandboxTools(
    private val exec: SandboxExec,
    private val endpoint: String,
    initialToken: String,
    /**
     * token 过期时向云端换一张新的（实测有效期 72 小时）。
     * 返回 null 表示续期失败，此时如实把 401 交给模型看。
     */
    private val tokenRefresher: suspend () -> String? = { null },
    /** 工具开始/结束时回调，用于 UI 展示"正在执行 xxx"。 */
    private val onEvent: (SandboxToolEvent) -> Unit = {},
    /**
     * 一条命令跑完后的完整回执（原始命令 + stdout/stderr/退出码）。
     *
     * 与 [onEvent] 分开：那个只够画一行「正在执行」，终端窗口要的是全文。
     * 上层拿它写命令日志，用户才看得到 agent 到底跑了什么、输出了什么。
     */
    private val onExecuted: (String, Long, SandboxExec.Result) -> Unit = { _, _, _ -> },
) {

    /**
     * 当前 token。续期后会就地更新：agent 实例是长活的，
     * 而续期发生在工具调用内部，不更新的话下一次调用又拿旧 token 去撞 401。
     */
    @Volatile
    private var token: String = initialToken

    /** 单次命令的默认超时（秒）。模型跑长任务时需要更久。 */
    private companion object {
        const val DEFAULT_TIMEOUT = 120
        const val MAX_TIMEOUT = 900
        const val MAX_OUTPUT = 60_000
    }

    fun registry(): ToolRegistry = ToolRegistry {
        tool(ShellTool())
        tool(ReadFileTool())
        tool(WriteFileTool())
        tool(EditFileTool())
        tool(ListDirTool())
        tool(GrepTool())
        tool(FindTool())
    }

    /** 执行命令并返回拼好的文本结果（含退出码），交给模型判断成败。 */
    private suspend fun runCommand(
        tool: String,
        detail: String,
        command: String,
        timeoutSec: Int = DEFAULT_TIMEOUT,
    ): String {
        onEvent(SandboxToolEvent(tool, detail, true))
        val startedAt = System.currentTimeMillis()
        val r = exec.run(
            endpoint = endpoint,
            token = token,
            command = command,
            timeoutSec = timeoutSec,
            onUnauthorized = { tokenRefresher()?.also { token = it } },
        )
        // 先落日志再报结束：终端里"跑完了"那一刻要能立刻读到输出。
        // 回调本身不能抛（写日志是纯内存操作），否则会把已经跑完的命令当失败。
        runCatching { onExecuted(command, startedAt, r) }
        onEvent(SandboxToolEvent(tool, detail, false))
        val sb = StringBuilder()
        if (r.stdout.isNotBlank()) sb.append(r.stdout)
        if (r.stderr.isNotBlank()) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("[stderr]\n").append(r.stderr)
        }
        if (r.error != null) {
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append("[error] ").append(r.error)
        }
        r.exitCode?.let { if (it != 0) sb.append("\n[exit ").append(it).append("]") }
        val text = sb.toString().trim()
        return truncate(text.ifEmpty { "（无输出，退出码 ${r.exitCode}）" })
    }

    /**
     * 跑一段 Python。脚本与参数都走 base64 + 单引号转义，
     * 避免正则、引号、换行、中文在 shell 里被二次解释。
     *
     * 沙箱自带 python3（报告里用 `python3 -m http.server` 实测过），
     * 比 sed/awk 更适合做精确文本替换。
     */
    private fun pyCommand(script: String, vararg args: String): String {
        val b64 = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
        val quoted = args.joinToString(" ") { ShellQuote.quote(it) }
        return "cd /workspace && echo ${ShellQuote.quote(b64)} | base64 -d | python3 - $quoted"
    }

    private fun truncate(s: String): String =
        if (s.length <= MAX_OUTPUT) s
        else s.take(MAX_OUTPUT) + "\n…（输出过长已截断，共 ${s.length} 字符）"

    @Serializable
    data class ShellArgs(
        val command: String,
        /** 可选超时（秒）。编译、装依赖这类长任务需要显式放大。 */
        val timeoutSec: Int = DEFAULT_TIMEOUT,
    )

    inner class ShellTool : SimpleTool<ShellArgs>(
        ShellArgs.serializer(),
        "run_shell",
        "在沙箱里执行 shell 命令。工作目录默认 /workspace，拥有 root 权限。" +
            "适合查看文件、运行构建、执行脚本。返回 stdout、stderr 与退出码。" +
            "默认超时 ${DEFAULT_TIMEOUT} 秒，长任务可传 timeoutSec（最大 $MAX_TIMEOUT）。",
    ) {
        override suspend fun execute(args: ShellArgs): String =
            runCommand(
                tool = "run_shell",
                detail = args.command.take(60),
                command = "cd /workspace && ${args.command}",
                timeoutSec = args.timeoutSec.coerceIn(1, MAX_TIMEOUT),
            )
    }

    @Serializable
    data class PathArgs(val path: String)

    inner class ReadFileTool : SimpleTool<PathArgs>(
        PathArgs.serializer(),
        "read_file",
        "读取沙箱内某个文件的文本内容。路径可以是相对（相对 /workspace）或绝对路径。" +
            "内容过长时会被截断，可用 run_shell 配合 sed -n 分段读取。",
    ) {
        override suspend fun execute(args: PathArgs): String {
            val p = ShellQuote.quote(args.path)
            return runCommand(
                "read_file", args.path,
                "cd /workspace && head -c 200000 -- $p",
            )
        }
    }

    @Serializable
    data class WriteArgs(val path: String, val content: String)

    inner class WriteFileTool : SimpleTool<WriteArgs>(
        WriteArgs.serializer(),
        "write_file",
        "把文本内容写入沙箱内的文件（覆盖写，自动创建父目录）。路径相对 /workspace 或绝对路径。" +
            "只做局部修改时优先用 edit_file，不要整篇重写。",
    ) {
        override suspend fun execute(args: WriteArgs): String {
            val p = ShellQuote.quote(args.path)
            val b64 = Base64.getEncoder().encodeToString(args.content.toByteArray(Charsets.UTF_8))
            // 内容走 base64，避免引号/换行/中文在 shell 里被二次解释。
            val cmd = "cd /workspace && mkdir -p -- \"$(dirname -- $p)\" && printf %s ${ShellQuote.quote(b64)} | base64 -d > $p && echo \"wrote $(wc -c < $p) bytes to $p\""
            return runCommand("write_file", args.path, cmd)
        }
    }

    @Serializable
    data class EditArgs(
        val path: String,
        /** 要在文件里定位的原文，必须与文件内容逐字符一致（含缩进）。 */
        val oldText: String,
        val newText: String,
        /** 为 true 时替换所有出现位置，否则要求唯一匹配。 */
        val replaceAll: Boolean = false,
    )

    inner class EditFileTool : SimpleTool<EditArgs>(
        EditArgs.serializer(),
        "edit_file",
        "对沙箱内文件做精确字符串替换，适合修改已有代码。" +
            "oldText 必须与文件中的原文完全一致（包括缩进和空行）；" +
            "默认要求 oldText 唯一出现，出现多次会报错，此时需给出更长的上下文或置 replaceAll=true。",
    ) {
        override suspend fun execute(args: EditArgs): String {
            val script = """
                import sys, json, io
                path, old, new, allflag = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4] == "1"
                try:
                    with io.open(path, encoding="utf-8") as f:
                        src = f.read()
                except FileNotFoundError:
                    print("ERROR: 文件不存在: " + path); sys.exit(1)
                if old == "":
                    print("ERROR: oldText 不能为空"); sys.exit(1)
                n = src.count(old)
                if n == 0:
                    print("ERROR: 在 " + path + " 中找不到 oldText，请先 read_file 确认原文"); sys.exit(2)
                if n > 1 and not allflag:
                    print("ERROR: oldText 在文件中出现 " + str(n) + " 次，请补充上下文使其唯一，或设置 replaceAll=true"); sys.exit(3)
                out = src.replace(old, new) if allflag else src.replace(old, new, 1)
                with io.open(path, "w", encoding="utf-8") as f:
                    f.write(out)
                print("updated " + path + " (" + str(n if allflag else 1) + " 处替换, " + str(len(out)) + " 字节)")
            """.trimIndent()
            val flag = if (args.replaceAll) "1" else "0"
            return runCommand(
                "edit_file", args.path,
                pyCommand(script, args.path, args.oldText, args.newText, flag),
            )
        }
    }

    inner class ListDirTool : SimpleTool<PathArgs>(
        PathArgs.serializer(),
        "list_dir",
        "列出沙箱内某个目录的文件与子目录（含大小）。路径相对 /workspace 或绝对路径。",
    ) {
        override suspend fun execute(args: PathArgs): String {
            val p = ShellQuote.quote(args.path)
            return runCommand("list_dir", args.path, "cd /workspace && ls -la -- $p")
        }
    }

    @Serializable
    data class GrepArgs(
        /** 正则表达式（POSIX 扩展语法）。 */
        val pattern: String,
        /** 搜索路径，默认 /workspace。 */
        val path: String = "/workspace",
        /** 只列出匹配的文件名，不打印匹配行。 */
        val filesOnly: Boolean = false,
    )

    inner class GrepTool : SimpleTool<GrepArgs>(
        GrepArgs.serializer(),
        "grep",
        "在沙箱内按正则搜索文件内容，返回匹配的文件名与行号。" +
            "用于定位某个符号、函数或字符串在项目里的位置，比逐个 read_file 快得多。",
    ) {
        override suspend fun execute(args: GrepArgs): String {
            val p = ShellQuote.quote(args.pattern)
            val dir = ShellQuote.quote(args.path)
            val mode = if (args.filesOnly) "-rl" else "-rnI"
            // --exclude-dir 挡住构建产物与依赖缓存，否则结果会被垃圾淹没。
            val cmd = "cd /workspace && grep $mode --exclude-dir=.git --exclude-dir=build " +
                "--exclude-dir=node_modules --exclude-dir=.gradle -- $p $dir 2>&1 | head -200"
            return runCommand("grep", args.pattern.take(40), cmd)
        }
    }

    @Serializable
    data class FindArgs(
        /** 文件名通配，例如 *.kt、build.gradle.kts。 */
        val glob: String,
        val path: String = "/workspace",
    )

    inner class FindTool : SimpleTool<FindArgs>(
        FindArgs.serializer(),
        "find",
        "按文件名通配在沙箱内查找文件（如 *.kt、*.md）。路径默认 /workspace，自动跳过 .git 与构建产物。",
    ) {
        override suspend fun execute(args: FindArgs): String {
            val g = ShellQuote.quote(args.glob)
            val dir = ShellQuote.quote(args.path)
            val cmd = "cd /workspace && find $dir \\( -name .git -o -name build -o -name node_modules -o -name .gradle \\) " +
                "-prune -o -type f -name $g -print 2>/dev/null | head -200"
            return runCommand("find", args.glob, cmd)
        }
    }
}

/**
 * POSIX shell 单引号转义。
 *
 * 单引号字符串里除 `'` 本身外所有字符都按字面处理，所以只需把 `'` 换成
 * `'\''`（结束引号 + 转义单引号 + 重新开始引号）。这样路径里的空格、`$`、
 * 反引号、换行都不会被解释。
 */
object ShellQuote {

    fun quote(raw: String): String {
        val sb = StringBuilder(raw.length + 2)
        sb.append('\'')
        for (c in raw) {
            if (c == '\'') sb.append("'\\''") else sb.append(c)
        }
        sb.append('\'')
        return sb.toString()
    }
}
