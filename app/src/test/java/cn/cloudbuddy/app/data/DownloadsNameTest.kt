package cn.cloudbuddy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载落盘前必须把文件名收干净：云端给的名字可能带路径或控制字符，
 * 直接用会把文件写出 Download 目录。
 */
class DownloadsNameTest {

    @Test
    fun `path separators are stripped down to the last segment`() {
        assertEquals("passwd", Downloads.safeName("../../etc/passwd"))
        assertEquals("a.txt", Downloads.safeName("dir/sub/a.txt"))
        assertEquals("a.txt", Downloads.safeName("dir\\sub\\a.txt"))
    }

    @Test
    fun `control characters are removed`() {
        assertEquals("report.pdf", Downloads.safeName("re\u0000port\n.pdf"))
    }

    @Test
    fun `leading dots are dropped`() {
        assertEquals("nomedia", Downloads.safeName(".nomedia"))
        assertEquals("txt", Downloads.safeName("..txt"))
    }

    @Test
    fun `blank names fall back to a generated one`() {
        val fallback = Downloads.safeName("   ")
        assertTrue(fallback.startsWith("cloudbuddy-"))
        assertFalse(fallback.isBlank())
    }

    @Test
    fun `over-long names are capped`() {
        val long = "a".repeat(500) + ".txt"
        assertTrue(Downloads.safeName(long).length <= 120)
    }

    @Test
    fun `chinese and spaces are preserved`() {
        assertEquals("季度报告 2026.pdf", Downloads.safeName("季度报告 2026.pdf"))
    }
}
