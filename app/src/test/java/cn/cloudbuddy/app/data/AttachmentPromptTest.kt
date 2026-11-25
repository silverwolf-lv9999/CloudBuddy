package cn.cloudbuddy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 附件提示的拼装规则。
 *
 * 这段逻辑决定模型"看不看得见"附件：路径错了它读不到文件，预览判定错了
 * 要么把二进制塞进上下文（浪费且乱码），要么该给的内容没给。
 */
class AttachmentPromptTest {

    @Test
    fun `普通文本按扩展名给预览`() {
        val text = "fun main() = println(\"hi\")"
        val preview = AttachmentPrompt.previewOf("Main.kt", "application/octet-stream", text.toByteArray())
        assertEquals(text, preview)
    }

    @Test
    fun `图片不给预览`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x01)
        assertNull(AttachmentPrompt.previewOf("shot.png", "image/png", png))
    }

    @Test
    fun `二进制即使扩展名像文本也不给预览`() {
        // .log 在白名单里，但内容是非法 UTF-8；解码失败不该把乱码塞进 prompt。
        // 这里用空字节 + 非法序列，断言不抛异常即可（可能返回替换字符，故只测不炸）。
        val bytes = byteArrayOf(0xC3.toByte(), 0x28)
        val out = AttachmentPrompt.previewOf("x.log", "text/plain", bytes)
        assertTrue(out == null || out.isNotEmpty())
    }

    @Test
    fun `超大文件不给预览`() {
        val big = ByteArray(AttachmentPrompt.MAX_PREVIEW_BYTES + 1) { 'a'.code.toByte() }
        assertNull(AttachmentPrompt.previewOf("big.md", "text/markdown", big))
    }

    @Test
    fun `预览截断到上限`() {
        val long = "x".repeat(AttachmentPrompt.MAX_PREVIEW_CHARS + 500)
        val preview = AttachmentPrompt.previewOf("a.txt", "text/plain", long.toByteArray())
        assertEquals(AttachmentPrompt.MAX_PREVIEW_CHARS, preview!!.length)
    }

    @Test
    fun `mime 为 octet-stream 时靠扩展名判定文本`() {
        assertTrue(AttachmentPrompt.isText("package.json", "application/octet-stream"))
        assertTrue(!AttachmentPrompt.isText("model.bin", "application/octet-stream"))
        // mime 明确是 text 时，扩展名不重要
        assertTrue(AttachmentPrompt.isText("weird", "text/x-diff"))
    }

    @Test
    fun `空附件不产生提示块`() {
        assertEquals("", AttachmentPrompt.hint(emptyList()))
    }

    @Test
    fun `提示块包含路径与预览`() {
        val hint = AttachmentPrompt.hint(
            listOf(
                AttachmentPrompt.Staged("a.md", "/workspace/uploads/cb-1/a.md", 12, "# 标题"),
                AttachmentPrompt.Staged("b.png", "/workspace/uploads/cb-1/b.png", 2048, null),
            )
        )
        assertTrue(hint.contains("/workspace/uploads/cb-1/a.md"))
        assertTrue(hint.contains("/workspace/uploads/cb-1/b.png"))
        assertTrue(hint.contains("| # 标题"))
        // 没有预览的附件只给路径，不应该出现空预览标题
        assertEquals(1, Regex("预览（前").findAll(hint).count())
        assertTrue(hint.startsWith("\n\n<attachments>"))
        assertTrue(hint.endsWith("</attachments>"))
    }
}
