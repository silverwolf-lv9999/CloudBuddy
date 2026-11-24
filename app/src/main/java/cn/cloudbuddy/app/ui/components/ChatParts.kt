package cn.cloudbuddy.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.cloudbuddy.app.data.ChatMessage
import cn.cloudbuddy.app.data.ChatRole
import cn.cloudbuddy.app.data.ToolState
import cn.cloudbuddy.app.data.TurnState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun StatePill(state: TurnState, modifier: Modifier = Modifier) {
    val (label, color) = when (state) {
        TurnState.RUNNING -> "云端执行中" to MaterialTheme.colorScheme.primary
        TurnState.WAITING -> "等待你的回复" to Color(0xFFA9761A)
        TurnState.DONE -> "已完成" to Color(0xFF17805F)
        TurnState.FAILED -> "执行失败" to MaterialTheme.colorScheme.error
        TurnState.STOPPED -> "已停止" to MaterialTheme.colorScheme.onSurfaceVariant
        TurnState.IDLE -> "空闲" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (state == TurnState.RUNNING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.6.dp,
                    color = color
                )
            } else {
                Box(Modifier.size(6.dp).background(color, CircleShape))
            }
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

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

/** 超过这个长度才值得提供「复制」，短消息不显示操作区。 */
private const val COPY_THRESHOLD = 240

@Composable
fun MessageRow(message: ChatMessage, showThoughts: Boolean) {
    val clipboard = LocalClipboardManager.current
    val copy = { clipboard.setText(AnnotatedString(message.text)) }
    val longText = message.text.length > COPY_THRESHOLD || message.text.count { it == '\n' } > 8

    when (message.role) {
        ChatRole.USER -> Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
                    modifier = Modifier.widthIn(max = 320.dp)
                ) {
                    Text(
                        text = message.text,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            if (longText) ActionText("复制", copy)
        }

        ChatRole.ASSISTANT -> Column(modifier = Modifier.fillMaxWidth()) {
            MarkdownText(markdown = message.text, baseSize = 15f)
            if (message.streaming) TypingCursor()
            if (longText && !message.streaming) ActionText("复制", copy)
        }

        ChatRole.THOUGHT -> ThoughtRow(message.text, showThoughts, message.streaming)

        ChatRole.TOOL -> ToolRow(message)
    }
}

/**
 * 一轮对话的「中间步骤」组：思考、工具调用、过程叙述都收在这里。
 *
 * 折叠时只占一行摘要（参考 AgentsAnywhere 的 `ToolRunGroup`），展开后按原顺序铺明细。
 * 默认折叠由调用方决定，正在执行的那一轮通常展开，方便看到进度。
 */
/**
 * 一轮对话的「中间步骤」组：思考、工具调用、过程叙述都收在这里。
 *
 * 抄 rikkahub 的 ChainOfThought：整行可点击展开/收回，箭头翻转，卡片高度
 * `animateContentSize` 平滑过渡——和思考卡片是同一套交互，不再是硬切换。
 * 展开状态由外部（会话级）控制，手动点过只影响当前会话的这一条。
 */
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
    // 箭头旋转 + 卡片高度动画：抄 rikkahub 的 Details / ChainOfThought 过渡，展开收回都有动画。
    val rotation by animateFloatAsState(targetValue = if (expanded) 180f else 0f, label = "steps-arrow")

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(20.dp),
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
                    style = MaterialTheme.typography.titleSmall,
                    color = accent,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp, color = accent
                    )
                } else {
                    Text(
                        text = if (expanded) "收起" else "展开",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    steps.forEach { step ->
                        when (step.role) {
                            ChatRole.THOUGHT -> ThoughtRow(step.text, showThoughts, step.streaming)
                            ChatRole.TOOL -> ToolRow(step)
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

/** 抄自 rikkahub：思考卡片三态。生成中 Collapsed→Preview（渐隐预览+自动滚底），完成后可收回。 */
private enum class ReasoningCardState(val expanded: Boolean) {
    Collapsed(false), Preview(true), Expanded(true)
}

@Stable
private class ReasoningState(val scroll: ScrollState) {
    var expandState by mutableStateOf(ReasoningCardState.Collapsed)
    var durationMs by mutableStateOf(0L)

    fun onExpandedChange(nextExpanded: Boolean, loading: Boolean) {
        expandState = if (loading) {
            if (nextExpanded) ReasoningCardState.Expanded else ReasoningCardState.Preview
        } else {
            if (nextExpanded) ReasoningCardState.Expanded else ReasoningCardState.Collapsed
        }
    }
}

/**
 * 思考过程行——整行可点击展开/收回（抄 rikkahub 的 ChainOfThought 交互）。
 * - 生成中：默认 Preview，内容渐隐、限高 100dp 并自动滚到底部，点标题切成完全展开/预览；
 * - 生成完成：默认收回，点标题展开全文；
 * - 内容走 MarkdownText，思考里的列表/代码/表格也能正常渲染。
 */
@Composable
private fun ThoughtRow(text: String, showThoughts: Boolean, streaming: Boolean = false) {
    val scroll = rememberScrollState()
    val state = remember { ReasoningState(scroll) }

    LaunchedEffect(streaming, text) {
        if (streaming) {
            if (!state.expandState.expanded && showThoughts) {
                state.expandState = ReasoningCardState.Preview
            }
            scroll.animateScrollTo(scroll.maxValue)
        } else {
            if (state.expandState == ReasoningCardState.Preview) {
                state.expandState = if (showThoughts) ReasoningCardState.Expanded else ReasoningCardState.Collapsed
            }
        }
    }
    // 生成中每秒推进耗时；完成后停在累计值。
    LaunchedEffect(streaming) {
        if (streaming) {
            val start = System.currentTimeMillis()
            while (isActive) {
                state.durationMs = System.currentTimeMillis() - start
                delay(1000)
            }
        }
    }

    val seconds = state.durationMs / 1000
    ChainOfThought(steps = listOf(Unit), collapsedAdaptiveWidth = false) {
        ControlledChainOfThoughtStep(
            expanded = state.expandState == ReasoningCardState.Expanded,
            onExpandedChange = { state.onExpandedChange(it, streaming) },
            icon = chainStepIcon(Icons.Filled.Psychology, MaterialTheme.colorScheme.secondary),
            label = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "深度思考",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    if (streaming) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(11.dp), strokeWidth = 1.4.dp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            },
            extra = {
                if (seconds > 0) {
                    Text(
                        "${seconds}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            },
            contentVisible = state.expandState != ReasoningCardState.Collapsed,
            content = {
                ReasoningContent(text, state.expandState, scroll, streaming)
            },
        )
    }
}

@Composable
private fun ReasoningContent(
    text: String,
    expandState: ReasoningCardState,
    scroll: ScrollState,
    streaming: Boolean,
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

@Composable
private fun ToolRow(message: ChatMessage) {
    val tint = when (message.toolState) {
        ToolState.RUNNING -> MaterialTheme.colorScheme.primary
        ToolState.DONE -> Color(0xFF17805F)
        ToolState.FAILED -> MaterialTheme.colorScheme.error
        ToolState.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.Filled.Build, contentDescription = null, modifier = Modifier.size(13.dp), tint = tint)
        Text(
            text = message.text.lineSequence().firstOrNull().orEmpty().ifBlank { "调用工具" },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        when (message.toolState) {
            ToolState.RUNNING -> CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.4.dp,
                color = tint
            )
            ToolState.DONE -> Icon(Icons.Filled.Check, null, Modifier.size(12.dp), tint = tint)
            ToolState.FAILED -> Icon(Icons.Filled.Close, null, Modifier.size(12.dp), tint = tint)
            ToolState.NONE -> Unit
        }
    }
}

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
