package cn.cloudbuddy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 命令输出的转义序列处理。
 *
 * 这块的价值全在"难看但不出错"的细节上：颜色认错只是难看，
 * 把转义序列漏进文本里则会让复制出去的日志带着 `[32m`，
 * 而 `\r` 处理错了会把进度条的几十帧叠成一行天文数字。
 */
class AnsiTextTest {

    @Test
    fun `纯文本原样返回`() {
        val spans = Ansi.spans("hello\nworld")
        assertEquals(1, spans.size)
        assertEquals("hello\nworld", spans[0].text)
        assertEquals(null, spans[0].color)
    }

    @Test
    fun `基本前景色`() {
        val spans = Ansi.spans("\u001B[32mok\u001B[0m done")
        assertEquals(2, spans.size)
        assertEquals("ok", spans[0].text)
        assertEquals(AnsiColor.GREEN, spans[0].color)
        assertEquals(" done", spans[1].text)
        assertEquals(null, spans[1].color)
    }

    @Test
    fun `组合参数是累积的而不是独立指令`() {
        val spans = Ansi.spans("\u001B[1;31merr\u001B[0m")
        assertEquals(1, spans.size)
        assertEquals(AnsiColor.RED, spans[0].color)
        assertTrue(spans[0].bold)
    }

    @Test
    fun `关闭粗体不影响颜色`() {
        val spans = Ansi.spans("\u001B[1;32ma\u001B[22mb")
        assertEquals(2, spans.size)
        assertTrue(spans[0].bold)
        assertFalse(spans[1].bold)
        // 22 只关粗体，绿色要留着——真终端就是这样。
        assertEquals(AnsiColor.GREEN, spans[1].color)
    }

    @Test
    fun `亮色也认`() {
        val spans = Ansi.spans("\u001B[91mbright\u001B[0m")
        assertEquals(AnsiColor.BRIGHT_RED, spans[0].color)
    }

    @Test
    fun `回车覆盖同一行`() {
        // 进度条的典型形态：同一行反复重写。
        assertEquals("100%", Ansi.spans("10%\r50%\r100%").joinToString("") { it.text })
    }

    @Test
    fun `回车后的短内容只覆盖行首`() {
        // "abc" 被 "XY" 覆盖成 "XYc"，跟真终端一致。
        assertEquals("XYc", Ansi.spans("abc\rXY").joinToString("") { it.text })
    }

    @Test
    fun `回车后没写东西则内容保留`() {
        assertEquals("abc", Ansi.spans("abc\r").joinToString("") { it.text })
    }

    @Test
    fun `多行里的回车只影响自己那行`() {
        val out = Ansi.spans("first\n1%\r99%\nlast").joinToString("") { it.text }
        assertEquals("first\n99%\nlast", out)
    }

    @Test
    fun `非 SGR 的控制序列被吃掉`() {
        // \e[2K 清整行、\e[1A 光标上移：静态输出里没意义，不能漏成可见字符。
        val out = Ansi.spans("a\u001B[2Kb\u001B[1Ac").joinToString("") { it.text }
        assertEquals("abc", out)
    }

    @Test
    fun `被截断在半路的转义序列丢弃`() {
        // 字符数上限正好切在 \e[3 中间，剩下的没法解释，只能丢。
        val out = Ansi.spans("ok\u001B[3")
        assertEquals("ok", out.joinToString("") { it.text })
    }

    @Test
    fun `复制用纯文本不带转义`() {
        val raw = "\u001B[32mPASS\u001B[0m 12 tests"
        assertEquals("PASS 12 tests", Ansi.plain(raw))
    }

    @Test
    fun `纯文本也要归一回车`() {
        // 光标回行首写 "done"，盖掉前 4 个字符，剩下的 "ing" 留着——
        // 真终端 `printf "waiting\\rdone"` 显示的就是 "doneing"。
        assertEquals("doneing", Ansi.plain("waiting\rdone"))
    }

    @Test
    fun `空输入不出片段`() {
        assertEquals(0, Ansi.spans("").size)
        assertEquals(0, Ansi.spans(null ?: "").size)
    }
}
