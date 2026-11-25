package cn.cloudbuddy.app.data

/**
 * 终端输出里的 SGR 颜色。只取 16 色，不做 256 / truecolor。
 *
 * 理由：沙箱里的 `ls --color`、`git status`、`npm` 用的基本都是 16 色；
 * 256 色要一整套调色板，而暗底上多出来的那些颜色反而更难读。
 */
enum class AnsiColor(val code: Int) {
    BLACK(30), RED(31), GREEN(32), YELLOW(33), BLUE(34), MAGENTA(35), CYAN(36), WHITE(37),
    GRAY(90), BRIGHT_RED(91), BRIGHT_GREEN(92), BRIGHT_YELLOW(93),
    BRIGHT_BLUE(94), BRIGHT_MAGENTA(95), BRIGHT_CYAN(96), BRIGHT_WHITE(97);

    companion object {
        fun of(code: Int): AnsiColor? = entries.firstOrNull { it.code == code }
    }
}

/** 一段同色的文本。渲染层照着拼 `AnnotatedString`。 */
data class AnsiSpan(
    val text: String,
    val color: AnsiColor? = null,
    val bold: Boolean = false,
)

/**
 * 把命令输出里的转义序列整理成能直接画的东西。
 *
 * 之前这些内容原样显示，`git status` 出来的就是一堆 `[32m`，用户以为终端坏了。
 * 真终端做的事就是：认识颜色、按颜色画、其余控制序列吃掉。
 *
 * 顺手处理 `\r`（回车不换行）：进度条和 `ping` 都用它回到行首重写，
 * 直接显示会把同一行叠成一长串。
 */
object Ansi {

    /** 纯文本版（复制、喂给模型时用）：去掉所有转义，保留内容。 */
    fun plain(raw: String): String = spans(raw).joinToString("") { it.text }

    /** 累计的 SGR 状态。`1;32` 是「粗体 + 绿」，不是两条独立指令。 */
    private class Sgr {
        var color: AnsiColor? = null
        var bold: Boolean = false
    }

    /**
     * 解析成着色片段。
     *
     * 未闭合的转义序列（正好被字符数上限截断在中间）直接丢弃——
     * 显示半截 `\u001B[3` 比丢掉这一段颜色更糟。
     */
    fun spans(raw: String): List<AnsiSpan> {
        val text = normalize(raw)
        if (text.isEmpty()) return emptyList()

        val out = ArrayList<AnsiSpan>()
        val buf = StringBuilder()
        val state = Sgr()

        fun flush() {
            if (buf.isNotEmpty()) {
                out.add(AnsiSpan(buf.toString(), state.color, state.bold))
                buf.setLength(0)
            }
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '\u001B') {
                buf.append(c)
                i++
                continue
            }
            if (i + 1 >= text.length) break
            if (text[i + 1] == '[') {
                var j = i + 2
                while (j < text.length && text[j] !in '@'..'~') j++
                if (j >= text.length) break
                if (text[j] == 'm') {
                    flush()
                    applySgr(text.substring(i + 2, j), state)
                }
                // 其余 CSI（清行、移光标、隐藏光标）对静态输出没有意义，直接吃掉。
                i = j + 1
                continue
            }
            // 非 CSI 的 ESC（改标题、切字符集）：跳到 BEL 或下一个 ESC。
            if (text[i + 1] == ']' || text[i + 1] == 'P') {
                var j = i + 2
                while (j < text.length && text[j] != '\u0007' && text[j] != '\u001B') j++
                i = if (j < text.length && text[j] == '\u0007') j + 1 else j
                continue
            }
            i += 2 // 两字节转义，丢掉
        }
        flush()
        return out
    }

    /** 统一的换行与回车处理。 */
    private fun normalize(raw: String): String {
        if (raw.isEmpty()) return raw
        // Windows 风格换行先归一，否则下面按 '\r' 切会多出空段。
        val unified = raw.replace("\r\n", "\n")
        if (unified.indexOf('\r') < 0) return unified
        return unified.split('\n').joinToString("\n") { overwrite(it) }
    }

    /**
     * 模拟"回车回到行首再覆盖写"。
     *
     * 逐段覆盖而不是只留最后一段：`10%\r50% done` 这种，真终端显示 `50% done`
     * （后面覆盖前面、并溢出到原长度之外），只取最后一段会丢内容。
     */
    private fun overwrite(line: String): String {
        if (line.indexOf('\r') < 0) return line
        var result = ""
        for (seg in line.split('\r')) {
            result = when {
                seg.isEmpty() -> result
                seg.length >= result.length -> seg
                else -> seg + result.substring(seg.length)
            }
        }
        return result
    }

    /** 解析 `1;32` 这种参数串。空参数按 0（重置）处理，跟真终端一致。 */
    private fun applySgr(spec: String, state: Sgr) {
        val params = if (spec.isBlank()) listOf("0") else spec.split(';')
        for (p in params) {
            when (val code = p.toIntOrNull() ?: 0) {
                0 -> {
                    state.color = null
                    state.bold = false
                }
                1 -> state.bold = true
                22 -> state.bold = false
                39 -> state.color = null
                in 30..37, in 90..97 -> AnsiColor.of(code)?.let { state.color = it }
                // 38 / 48 是 256 色与 truecolor 的前导码，后面跟的数字落到 else 被忽略。
                else -> Unit
            }
        }
    }
}
