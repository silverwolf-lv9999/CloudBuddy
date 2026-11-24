package cn.cloudbuddy.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Markdown 被切成「行内段落」与「代码块」两类，足够覆盖模型输出的常见结构。 */
sealed interface MdBlock {
    data class Paragraph(val text: AnnotatedString, val indent: Int = 0) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data object Divider : MdBlock
    /** 抄自 rikkahub 的表格块：表头 + 数据行 + 每列对齐方式。 */
    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
        val alignments: List<Alignment> = emptyList()
    ) : MdBlock
}

// 原来这两个正则在逐行循环里现编译（五百行正文就是上千次编译），提到文件级只编译一次。
private val ORDERED_ITEM = Regex("""^\d{1,3}[.)]\s""")
private val TABLE_SEPARATOR = Regex("""^\s*\|?[\s:|-]+\|?\s*$""")

/** 把 `| a | b |` 切成单元格；抄 rikkahub，支持转义的 `\|`。 */
private fun splitTableRow(line: String): List<String> {
    val trimmed = line.trim().trim('|')
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    while (i < trimmed.length) {
        when {
            trimmed[i] == '\\' && i + 1 < trimmed.length && trimmed[i + 1] == '|' -> {
                current.append('|'); i += 2
            }
            trimmed[i] == '|' -> {
                cells += current.toString().trim(); current.setLength(0); i++
            }
            else -> { current.append(trimmed[i]); i++ }
        }
    }
    cells += current.toString().trim()
    return cells
}

/** 解析 `|:---|---:|:---:|` → 左/右/中对齐。 */
private fun parseAlignments(separator: String): List<Alignment> = splitTableRow(separator).map { cell ->
    val left = cell.startsWith(":")
    val right = cell.endsWith(":")
    when {
        left && right -> Alignment.Center
        right -> Alignment.CenterEnd
        else -> Alignment.CenterStart
    }
}

/** 相邻多行表格是否构成一张表：表头行 + 分隔行。 */
private fun looksLikeTable(lines: List<String>, index: Int): Boolean =
    index + 1 < lines.size &&
        lines[index].trim().startsWith("|") &&
        TABLE_SEPARATOR.matches(lines[index + 1])

fun parseMarkdown(raw: String, baseSize: Float = 15f): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = raw.replace("\r\n", "\n").split('\n')
    val paragraph = StringBuilder()
    var index = 0

    fun flushParagraph() {
        if (paragraph.isEmpty()) return
        val text = paragraph.toString().trim('\n')
        paragraph.setLength(0)
        if (text.isBlank()) return
        blocks += MdBlock.Paragraph(inlineMarkdown(text, baseSize))
    }

    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trimStart()
        if (trimmed.startsWith("```")) {
            flushParagraph()
            val lang = trimmed.removePrefix("```").trim()
            val code = StringBuilder()
            index++
            while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                code.appendLine(lines[index])
                index++
            }
            index++ // 跳过结束围栏
            blocks += MdBlock.Code(lang, code.toString().trimEnd('\n'))
            continue
        }
        when {
            trimmed.isBlank() -> {
                flushParagraph()
                index++
            }

            trimmed == "---" || trimmed == "***" || trimmed == "___" -> {
                flushParagraph()
                blocks += MdBlock.Divider
                index++
            }

            trimmed.startsWith("#") -> {
                flushParagraph()
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 6)
                val title = trimmed.drop(level).trim()
                val size = when (level) {
                    1 -> baseSize + 7
                    2 -> baseSize + 4
                    3 -> baseSize + 2
                    else -> baseSize + 1
                }
                val text = buildAnnotatedString {
                    withStyle(SpanStyle(fontSize = size.sp, fontWeight = FontWeight.SemiBold)) {
                        appendInlineMarkdown(title, baseSize)
                    }
                }
                blocks += MdBlock.Paragraph(text)
                index++
            }

            trimmed.startsWith(">") -> {
                flushParagraph()
                val quote = trimmed.removePrefix(">").trim()
                val text = buildAnnotatedString {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        appendInlineMarkdown(quote, baseSize)
                    }
                }
                blocks += MdBlock.Paragraph(text, indent = 1)
                index++
            }

            trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ") -> {
                flushParagraph()
                val item = trimmed.substring(2)
                val text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("•  ") }
                    appendInlineMarkdown(item, baseSize)
                }
                blocks += MdBlock.Paragraph(text, indent = 1)
                index++
            }

            ORDERED_ITEM.containsMatchIn(trimmed) -> {
                flushParagraph()
                val marker = trimmed.takeWhile { it.isDigit() || it == '.' || it == ')' }
                val item = trimmed.removePrefix(marker).trimStart()
                val text = buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("$marker  ") }
                    appendInlineMarkdown(item, baseSize)
                }
                blocks += MdBlock.Paragraph(text, indent = 1)
                index++
            }

            trimmed.startsWith("|") && trimmed.endsWith("|") -> {
                flushParagraph()
                if (looksLikeTable(lines, index)) {
                    // 表头 + 分隔行 → 一张真正的表格，后续连续的 | 行都是数据行。
                    val header = splitTableRow(lines[index])
                    val alignments = parseAlignments(lines[index + 1])
                    index += 2
                    val rows = mutableListOf<List<String>>()
                    while (index < lines.size) {
                        val row = lines[index].trim()
                        if (!row.startsWith("|") || !row.endsWith("|")) break
                        if (TABLE_SEPARATOR.matches(row)) { index++; continue }
                        rows += splitTableRow(row)
                        index++
                    }
                    blocks += MdBlock.Table(header, rows, alignments)
                    continue
                }
                // 不成表的孤立 pipe 行：退回原来的行内渲染，别丢字。
                val cells = splitTableRow(trimmed)
                val text = buildAnnotatedString {
                    cells.forEachIndexed { i, cell ->
                        if (i > 0) withStyle(SpanStyle(color = Color(0xFF8C97A8))) { append("   ") }
                        appendInlineMarkdown(cell, baseSize - 1)
                    }
                }
                blocks += MdBlock.Paragraph(text, indent = 1)
                index++
            }

            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(trimmed)
                index++
            }
        }
    }
    flushParagraph()
    return blocks
}

/** 行内样式：粗体、斜体、行内代码、删除线、链接。直接写入当前 builder。 */
fun AnnotatedString.Builder.appendInlineMarkdown(source: String, baseSize: Float) {
    var i = 0
    while (i < source.length) {
        when {
            source.startsWith("**", i) || source.startsWith("__", i) -> {
                val marker = source.substring(i, i + 2)
                val end = source.indexOf(marker, i + 2)
                if (end > i) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(source.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(source[i]); i++
                }
            }

            source[i] == '`' -> {
                val end = source.indexOf('`', i + 1)
                if (end > i) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = (baseSize - 1.5f).sp,
                            background = Color(0x1A5B7BB5)
                        )
                    ) { append(source.substring(i + 1, end)) }
                    i = end + 1
                } else {
                    append(source[i]); i++
                }
            }

            source.startsWith("~~", i) -> {
                val end = source.indexOf("~~", i + 2)
                if (end > i) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(source.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(source[i]); i++
                }
            }

            source[i] == '*' || source[i] == '_' -> {
                val ch = source[i]
                val end = source.indexOf(ch, i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(source.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(source[i]); i++
                }
            }

            source[i] == '[' -> {
                val close = source.indexOf(']', i)
                if (close > i && close + 1 < source.length && source[close + 1] == '(') {
                    val end = source.indexOf(')', close + 2)
                    if (end > close) {
                        val label = source.substring(i + 1, close)
                        withStyle(
                            SpanStyle(
                                color = Color(0xFF2F6BFF),
                                textDecoration = TextDecoration.Underline
                            )
                        ) { append(label) }
                        i = end + 1
                        continue
                    }
                }
                append(source[i]); i++
            }

            else -> {
                append(source[i]); i++
            }
        }
    }
}

fun inlineMarkdown(source: String, baseSize: Float): AnnotatedString = buildAnnotatedString {
    appendInlineMarkdown(source, baseSize)
}

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    baseSize: Float = 15f,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    /** 折叠态只渲染前 N 块，保持排版样式但把长回答压短。 */
    maxBlocks: Int? = null
) {
    val blocks = remember(markdown, baseSize) { parseMarkdown(markdown, baseSize) }
    val shown = remember(blocks, maxBlocks) { if (maxBlocks != null) blocks.take(maxBlocks) else blocks }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> Text(
                    text = block.text,
                    style = TextStyle(
                        fontSize = baseSize.sp,
                        lineHeight = (baseSize * 1.55f).sp,
                        color = textColor
                    ),
                    modifier = if (block.indent > 0) Modifier.padding(start = 10.dp) else Modifier
                )

                is MdBlock.Code -> CodeBlock(block.lang, block.code, baseSize)

                is MdBlock.Table -> MarkdownTable(block, baseSize, textColor)

                MdBlock.Divider -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
}

/** 用 rikkahub 的 DataTable 渲染表格，单元格内容仍走行内 Markdown（粗体/代码/链接）。 */
@Composable
private fun MarkdownTable(table: MdBlock.Table, baseSize: Float, textColor: Color) {
    val columnCount = maxOf(table.header.size, table.rows.maxOfOrNull { it.size } ?: 0)
    if (columnCount == 0) return
    val headers: List<@Composable () -> Unit> = (0 until columnCount).map { c ->
        @Composable {
            val cell = table.header.getOrNull(c).orEmpty()
            Text(
                text = inlineMarkdown(cell, baseSize - 1),
                style = TextStyle(
                    fontSize = (baseSize - 1).sp,
                    lineHeight = (baseSize * 1.4f).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = textColor
                )
            )
        }
    }
    val rows: List<List<@Composable () -> Unit>> = table.rows.map { row ->
        (0 until columnCount).map { c ->
            @Composable {
                val cell = row.getOrNull(c).orEmpty()
                Text(
                    text = inlineMarkdown(cell, baseSize - 1),
                    style = TextStyle(
                        fontSize = (baseSize - 1).sp,
                        lineHeight = (baseSize * 1.4f).sp,
                        color = textColor
                    )
                )
            }
        }
    }
    DataTable(
        headers = headers,
        rows = rows,
        modifier = Modifier.padding(vertical = 4.dp),
        columnMinWidths = List(columnCount) { 56.dp },
        columnMaxWidths = List(columnCount) { 240.dp },
        columnAlignments = List(columnCount) { c -> table.alignments.getOrNull(c) ?: Alignment.CenterStart },
    )
}

@Composable
private fun CodeBlock(lang: String, code: String, baseSize: Float) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0F1720), RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = lang.ifBlank { "code" },
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF8FA3BC),
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                Text("复制", style = MaterialTheme.typography.labelSmall, color = Color(0xFF8FA3BC))
            }
        }
        SelectionContainer {
            Text(
                text = code,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = (baseSize - 2).sp,
                    lineHeight = (baseSize * 1.45f).sp,
                    color = Color(0xFFE6EDF3)
                ),
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
            )
        }
    }
}
