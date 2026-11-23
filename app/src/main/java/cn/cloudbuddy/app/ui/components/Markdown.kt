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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
}

// 原来这两个正则在逐行循环里现编译（五百行正文就是上千次编译），提到文件级只编译一次。
private val ORDERED_ITEM = Regex("""^\d{1,3}[.)]\s""")
private val TABLE_SEPARATOR = Regex("""^\|[\s:|-]+\|$""")

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
                if (!trimmed.matches(TABLE_SEPARATOR)) {
                    val cells = trimmed.trim('|').split('|').map { it.trim() }
                    val text = buildAnnotatedString {
                        cells.forEachIndexed { i, cell ->
                            if (i > 0) withStyle(SpanStyle(color = Color(0xFF8C97A8))) { append("   ") }
                            appendInlineMarkdown(cell, baseSize - 1)
                        }
                    }
                    blocks += MdBlock.Paragraph(text, indent = 1)
                }
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

                MdBlock.Divider -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
        }
    }
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
