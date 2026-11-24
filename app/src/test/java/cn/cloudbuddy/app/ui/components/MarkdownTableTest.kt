package cn.cloudbuddy.app.ui.components

import androidx.compose.ui.Alignment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 表格解析：抄 rikkahub 后，Markdown 里的 `|...|` 不再是拍平文本，而是真正的表格块。 */
class MarkdownTableTest {

    @Test
    fun `parses header rows and body`() {
        val md = """
            | 语言 | 用途 | Star |
            |---|:---:|---:|
            | Kotlin | Android | 100 |
            | Python | 脚本 | 200 |
        """.trimIndent()
        val blocks = parseMarkdown(md)
        assertEquals(1, blocks.size)
        val table = blocks[0] as MdBlock.Table
        assertEquals(listOf("语言", "用途", "Star"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("Kotlin", "Android", "100"), table.rows[0])
        assertEquals(listOf("Python", "脚本", "200"), table.rows[1])
    }

    @Test
    fun `parses column alignments`() {
        val md = """
            | left | center | right |
            |:---|:---:|---:|
            | a | b | c |
        """.trimIndent()
        val table = parseMarkdown(md)[0] as MdBlock.Table
        assertEquals(Alignment.CenterStart, table.alignments[0])
        assertEquals(Alignment.Center, table.alignments[1])
        assertEquals(Alignment.CenterEnd, table.alignments[2])
    }

    @Test
    fun `handles escaped pipe in cell`() {
        val md = """
            | 表达式 | 含义 |
            |---|---|
            | a \| b | 或 |
        """.trimIndent()
        val table = parseMarkdown(md)[0] as MdBlock.Table
        assertEquals("a | b", table.rows[0][0])
    }

    @Test
    fun `table separated from paragraph`() {
        val md = """
            前面一段说明。

            | A | B |
            |---|---|
            | 1 | 2 |

            后面一段。
        """.trimIndent()
        val blocks = parseMarkdown(md)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MdBlock.Paragraph)
        assertTrue(blocks[1] is MdBlock.Table)
        assertTrue(blocks[2] is MdBlock.Paragraph)
    }

    @Test
    fun `stray pipe line does not throw`() {
        val blocks = parseMarkdown("这是一行 | 带竖线但不成表")
        assertTrue(blocks.isNotEmpty())
        assertTrue(blocks[0] is MdBlock.Paragraph)
    }

    @Test
    fun `inline markdown still works in cells`() {
        val md = """
            | 名称 | 说明 |
            |---|---|
            | **粗体** | `代码` |
        """.trimIndent()
        val table = parseMarkdown(md)[0] as MdBlock.Table
        assertEquals("**粗体**", table.rows[0][0])
        assertEquals("`代码`", table.rows[0][1])
    }
}
