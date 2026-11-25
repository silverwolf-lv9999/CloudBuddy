package cn.cloudbuddy.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.cloudbuddy.app.data.ChatMessage
import cn.cloudbuddy.app.data.ChatRole
import cn.cloudbuddy.app.data.ToolState
import cn.cloudbuddy.app.data.TurnState
import cn.cloudbuddy.app.ui.theme.extendColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive

// ─────────────────────────────────────────────────────────────────────────────
// CloudBuddy 聊天零件：Material 3 Expressive 风格（对齐 RikkaHub）
// 气泡 / 思考链 / 工具卡片 / 状态 Pill / 输入光标
// ─────────────────────────────────────────────────────────────────────────────

private val BubbleCorner = 24.dp
private val BubbleTailCorner = 6.dp
private val BubbleHorizontalPadding = 16.dp
private val BubbleVerticalPadding = 12.dp

// ── 状态 Pill ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StatePill(state: TurnState, modifier: Modifier = Modifier) {
    val ext = MaterialTheme.extendColors
    val (label, containerColor, contentColor) = when (state) {
        TurnState.RUNNING -> Triple("云端执行中", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        TurnState.WAITING -> Triple("等待你的回复", ext.warningContainer, ext.onWarningContainer)
        TurnState.DONE -> Triple("已完成", ext.successContainer, ext.onSuccessContainer)
        TurnState.FAILED -> Triple("执行失败", MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        TurnState.STOPPED -> Triple("已停止", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
        TurnState.IDLE -> Triple("空闲", MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (state == TurnState.RUNNING) {
                ContainedLoadingIndicator(modifier = Modifier.size(12.dp))
            } else {
                Box(Modifier.size(6.dp).background(contentColor, CircleShape))
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = contentColor)
        }
    }
}

// ── 云徽标 ────────────────────────────────────────────────────────────────────

@Composable
fun CloudBadge(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            Icons.Filled.Cloud,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(13.dp)
        )
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── 消息行 ────────────────────────────────────────────────────────────────────

/** 超过这个长度才值得提供「复制」，短消息不显示操作区。 */
private const val COPY_THRESHOLD = 240

@Composable
fun MessageRow(message: ChatMessage, showThoughts: Boolean) {
    val clipboard = LocalClipboardManager.current
    val copy = { clipboard.setText(AnnotatedString(message.text)) }
    val longText = message.text.length > COPY_THRESHOLD || message.text.count { it == '\n' } > 8

    when (message.role) {
        ChatRole.USER -> UserMessageBubble(message = message, copy = copy, longText = longText)
        ChatRole.ASSISTANT -> AssistantMessageContent(message = message, copy = copy, longText = longText)
        ChatRole.THOUGHT -> ThoughtRow(message.text, showThoughts, message.streaming)
        ChatRole.TOOL -> ToolCallCard(message)
    }
}

/**
 * 用户消息气泡：24dp 主圆角 + 6dp 尾巴角，primaryContainer，左侧留白与 AI 回复错开。
 * 形状与 RikkaHub 的 ChatMessageBubble(USER) 一致。
 */
@Composable
private fun UserMessageBubble(
    message: ChatMessage,
    copy: () -> Unit,
    longText: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Spacer(Modifier.width(40.dp))
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(
                    topStart = BubbleCorner,
                    topEnd = BubbleTailCorner,
                    bottomEnd = BubbleCorner,
                    bottomStart = BubbleCorner
                ),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Text(
                    text = message.text,
                    modifier = Modifier.padding(
                        horizontal = BubbleHorizontalPadding,
                        vertical = BubbleVerticalPadding
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        if (longText) ActionText("复制", copy)
    }
}

/**
 * AI 消息：平铺全宽（无气泡、无头像），正文走 MarkdownText，
 * 外层 SelectionContainer 支持长按选中复制。流式时末尾显示 TypingCursor。
 */
@Composable
private fun AssistantMessageContent(
    message: ChatMessage,
    copy: () -> Unit,
    longText: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SelectionContainer(modifier = Modifier.fillMaxWidth()) {
            MarkdownText(
                markdown = message.text,
                baseSize = 15f,
                textColor = MaterialTheme.colorScheme.onSurface
            )
        }
        if (message.streaming) TypingCursor()
        if (longText && !message.streaming) {
            ActionCopyIcon(copy)
        }
    }
}

// ── 中间步骤折叠组 ─────────────────────────────────────────────────────────────

/**
 * 一轮对话的「中间步骤」组：思考、工具调用、过程叙述都收在这里。
 * 折叠时只占一行摘要，展开后按原顺序铺明细；箭头旋转 + 高度动画。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StepsGroup(
    steps: List<ChatMessage>,
    expanded: Boolean,
    state: TurnState,
    showThoughts: Boolean,
    onToggle: () -> Unit
) {
    val running = stepsRunning(steps)
    val failed = stepsFailed(steps)
    val accent = when {
        failed -> MaterialTheme.colorScheme.error
        running || state == TurnState.RUNNING -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val current = steps.lastOrNull { it.role == ChatRole.TOOL && it.toolState == ToolState.RUNNING }
    val label = when {
        current != null -> "正在 " + current.text.lineSequence().firstOrNull().orEmpty().take(28)
        else -> stepsSummary(steps)
    }
    val hasThought = steps.any { it.role == ChatRole.THOUGHT }
    val rotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "steps-arrow")

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.largeIncreased,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .animateContentSize()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onToggle)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = rotation },
                        tint = accent
                    )
                }
                Icon(
                    imageVector = if (hasThought) Icons.Filled.Psychology else Icons.Filled.Build,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = accent
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = accent,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (running) {
                    ContainedLoadingIndicator(modifier = Modifier.size(12.dp))
                }
            }

            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    steps.forEach { step ->
                        when (step.role) {
                            // 组外壳自己有 animateContentSize：内层思考卡不再叠一层
                            // 高度动画，否则展开/收起时双层逐帧重测量，是实测的卡顿源。
                            ChatRole.THOUGHT -> ThoughtRow(step.text, showThoughts, step.streaming, animate = false)
                            ChatRole.TOOL -> ToolCallCard(step)
                            ChatRole.ASSISTANT -> MarkdownText(
                                markdown = step.text,
                                baseSize = 13f,
                                modifier = Modifier.alpha(0.78f)
                            )
                            ChatRole.USER -> Unit
                        }
                    }
                }
            }
        }
    }
}

// ── 复制按钮 ──────────────────────────────────────────────────────────────────

@Composable
private fun ActionCopyIcon(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(4.dp),
        modifier = Modifier.size(28.dp)
    ) {
        Icon(
            Icons.Filled.ContentCopy,
            contentDescription = "复制",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
    }
}

@Composable
private fun ActionText(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// ── 思考链 ────────────────────────────────────────────────────────────────────

/** 思考卡片三态。生成中 Collapsed→Preview（渐隐预览+自动滚底），完成后可收回。 */
private enum class ReasoningCardState(val expanded: Boolean) {
    Collapsed(false), Preview(true), Expanded(true)
}

@Stable
private class ReasoningState(val scroll: ScrollState) {
    var expandState by mutableStateOf(ReasoningCardState.Collapsed)

    fun onExpandedChange(nextExpanded: Boolean, loading: Boolean) {
        expandState = if (loading) {
            if (nextExpanded) ReasoningCardState.Expanded else ReasoningCardState.Preview
        } else {
            if (nextExpanded) ReasoningCardState.Expanded else ReasoningCardState.Collapsed
        }
    }
}

/**
 * 思考过程行——整行可点击展开/收回。
 * - 生成中：默认 Preview，内容渐隐、限高 100dp 并自动滚到底部，点标题切成完全展开/预览；
 * - 生成完成：按设置决定默认展开还是收回（showThoughts），点标题随时切换；
 * - 内容走 MarkdownText，思考里的列表/代码/表格也能正常渲染。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ThoughtRow(
    text: String,
    showThoughts: Boolean,
    streaming: Boolean = false,
    animate: Boolean = true
) {
    val scroll = rememberScrollState()
    val state = remember { ReasoningState(scroll) }

    // 展开态的切换只依赖 streaming/showThoughts，不再把高频变化的 text 当 key——
    // 原来每来一个分片就取消重启这个协程，是流式期的主要卡顿源之一。
    LaunchedEffect(streaming, showThoughts) {
        if (streaming) {
            if (!state.expandState.expanded) {
                state.expandState = if (showThoughts) ReasoningCardState.Preview else ReasoningCardState.Collapsed
            }
        } else {
            state.expandState = when {
                showThoughts && !state.expandState.expanded -> ReasoningCardState.Expanded
                !showThoughts && state.expandState.expanded -> ReasoningCardState.Collapsed
                !showThoughts && state.expandState == ReasoningCardState.Preview -> ReasoningCardState.Collapsed
                else -> state.expandState
            }
        }
    }
    // 自动滚底：采样文本长度（而非文本本身）并节流到 120ms，用 scrollTo 直接跳，
    // 不再每次分片都启动一段 animateScrollTo 动画。
    val latestLen by rememberUpdatedState(text.length)
    LaunchedEffect(streaming, scroll) {
        if (!streaming) return@LaunchedEffect
        snapshotFlow { latestLen }
            .distinctUntilChanged()
            .collect { len ->
                if (len > 0) {
                    delay(120)
                    scroll.scrollTo(scroll.maxValue)
                }
            }
    }

    ChainOfThought(
        steps = listOf(Unit),
        collapsedAdaptiveWidth = false,
        // 双重保险：外层（StepsGroup 或流式期）任一情况要关，这里都不再叠动画。
        animate = animate && !streaming,
    ) {
        ControlledChainOfThoughtStep(
            expanded = state.expandState == ReasoningCardState.Expanded,
            onExpandedChange = { state.onExpandedChange(it, streaming) },
            icon = chainStepIcon(Icons.Filled.Psychology, MaterialTheme.colorScheme.secondary),
            label = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "深度思考",
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    if (streaming) {
                        ContainedLoadingIndicator(modifier = Modifier.size(11.dp))
                    }
                }
            },
            extra = { ThinkingSeconds(streaming) },
            contentVisible = state.expandState != ReasoningCardState.Collapsed,
            content = {
                ReasoningContent(text, state.expandState, scroll)
            },
        )
    }
}

@Composable
private fun ReasoningContent(
    text: String,
    expandState: ReasoningCardState,
    scroll: ScrollState,
) {
    val preview = expandState == ReasoningCardState.Preview
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { base ->
                if (preview) {
                    base
                        .graphicsLayer { alpha = 0.99f }
                        .drawWithCache {
                            val h = size.height.coerceAtLeast(1f)
                            val fade = 64f
                            val brush = Brush.verticalGradient(
                                startY = 0f, endY = size.height,
                                colorStops = arrayOf(
                                    0.0f to Color.Transparent,
                                    (fade / h) to Color.Black,
                                    (1 - fade / h) to Color.Black,
                                    1.0f to Color.Transparent,
                                )
                            )
                            onDrawWithContent {
                                drawContent()
                                drawRect(brush = brush, size = Size(size.width, size.height), blendMode = BlendMode.DstIn)
                            }
                        }
                        .heightIn(max = 100.dp)
                        .verticalScroll(scroll)
                } else base
            }
    ) {
        MarkdownText(markdown = text, baseSize = 13f, textColor = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 思考耗时单独成组件：它每秒更新一次自己的状态，只重组这一行文字，
 * 不再把整个思考卡（含 MarkdownText / animateContentSize）拖着一起重组。
 */
@Composable
private fun ThinkingSeconds(streaming: Boolean) {
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(streaming) {
        if (!streaming) return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (isActive) {
            elapsed = System.currentTimeMillis() - start
            delay(1000)
        }
    }
    val seconds = elapsed / 1000
    if (seconds > 0) {
        Text(
            "${seconds}s",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary
        )
    }
}

// ── 工具调用卡片 ──────────────────────────────────────────────────────────────

/**
 * 工具调用卡片：标题行（图标 + 名称 + 状态）+ 可选的命令/输入（monospace 高亮块）
 * + 可选的格式化输出（默认折叠，点击展开）。
 *
 * 不原始输出 JSON；命令类工具显示命令本身，输出按换行整理后展示。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ToolCallCard(message: ChatMessage) {
    val ext = MaterialTheme.extendColors
    val tint = when (message.toolState) {
        ToolState.RUNNING -> MaterialTheme.colorScheme.primary
        ToolState.DONE -> ext.success
        ToolState.FAILED -> MaterialTheme.colorScheme.error
        ToolState.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val hasInput = !message.toolInput.isNullOrBlank()
    val hasOutput = !message.toolOutput.isNullOrBlank()
    var expanded by remember(message.id) { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.largeIncreased,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 标题行：图标 + 名称 + 状态指示
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Filled.Build,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = tint
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = message.toolName
                            ?: message.text.lineSequence().firstOrNull().orEmpty().ifBlank { "调用工具" },
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (message.text.isNotBlank() && message.toolName != null && message.text != message.toolName) {
                        Text(
                            text = message.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                when (message.toolState) {
                    ToolState.RUNNING -> ContainedLoadingIndicator(modifier = Modifier.size(12.dp))
                    ToolState.DONE -> Icon(Icons.Filled.Check, null, Modifier.size(14.dp), tint = tint)
                    ToolState.FAILED -> Icon(Icons.Filled.Close, null, Modifier.size(14.dp), tint = tint)
                    ToolState.NONE -> Unit
                }
            }

            // 命令/输入：monospace 高亮块
            if (hasInput) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = message.toolInput!!,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }

            // 输出：默认折叠，点击展开
            if (hasOutput) {
                Spacer(Modifier.height(8.dp))
                val output = message.toolOutput!!
                val lines = output.lines()
                val preview = if (expanded) output else lines.take(3).joinToString("\n")
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                if (lines.size > 3) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text(
                            if (expanded) "收起" else "展开剩余 ${lines.size - 3} 行",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

// ── 输入光标 ──────────────────────────────────────────────────────────────────

@Composable
fun TypingCursor() {
    val transition = rememberInfiniteTransition(label = "cursor")
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(620), RepeatMode.Reverse),
        label = "alpha"
    )
    Box(
        modifier = Modifier
            .padding(top = 4.dp)
            .size(width = 8.dp, height = 14.dp)
            .alpha(alpha)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
    )
}
