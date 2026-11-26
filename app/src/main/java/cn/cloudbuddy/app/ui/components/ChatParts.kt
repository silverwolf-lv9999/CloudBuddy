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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.unit.sp
import cn.cloudbuddy.app.data.ChatMessage
import cn.cloudbuddy.app.data.ChatRole
import cn.cloudbuddy.app.data.ToolState
import cn.cloudbuddy.app.data.TurnState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
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

@Composable
fun MessageRow(message: ChatMessage, showThoughts: Boolean, finalAnswer: Boolean = false) {
    when (message.role) {
        ChatRole.USER -> Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End
        ) {
            // 附件先于文本（照 RikkaHub：图片缩略图在气泡上方、其他文件成片）。
            val images = message.attachments.filter { it.path != null }
            val files = message.attachments.filter { it.path == null }
            if (images.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
                    images.forEach { att -> LocalImageThumb(att.path!!) }
                }
            }
            if (files.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
                    files.forEach { att -> FileChip(att) }
                }
            }
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
                        text = message.shownText,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        ChatRole.ASSISTANT -> Column(modifier = Modifier.fillMaxWidth()) {
            if (message.failed) {
                // 失败 / 中断的留痕：用错误色的一小块提示，而不是当正文渲染。
                // 它长得像模型回答的话，用户重进会话会以为模型当时说了这句话。
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = message.text,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            } else {
                // 正文本身可长按选中（跨段落、跨表格一起选）；
                // 代码块自带一个内层 SelectionContainer 和「复制」按钮，各管一段。
                SelectionContainer {
                    Column {
                        MarkdownText(markdown = message.text, baseSize = 15f)
                        if (message.streaming) TypingCursor()
                    }
                }
                // 底栏只给这一轮的最终回答：过程中陆续输出的段落也是独立消息，
                // 每段都挂一条分隔线 + 「复制全部」会把回答切成一截一截，复制入口
                // 也跟着重复。流式期间同样不铺——内容还在长，分隔线会一路往下跳。
                if (!message.streaming && finalAnswer) MessageFooter(message.shownText)
            }
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

/**
 * 回答底部的操作区：一条贯穿的分隔线，线下靠左放「复制全部」。
 *
 * 分隔线把「这条回答到这儿结束」画清楚——一轮回答常常紧跟下一轮的用户提问，
 * 没有边界时读起来是连成一片的。复制入口固定在这一行，不再按长度阈值出现
 * （短回答想复制时反而找不到）。左对齐是为了让它与正文左边缘同一条竖线：
 * 靠右时会落在回答末尾的空白里，看着像另一条消息的控件。
 *
 * 只铺在**最终回答**下：过程中的段落、以及用户自己刚发的话都不铺——前者会把一条
 * 回答切成几截，后者本来也没有回头复制的场景。
 */
@Composable
private fun MessageFooter(text: String) {
    val clipboard = LocalClipboardManager.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(text)) },
                // 左侧不留白：图标要贴着正文左边缘，与上面的段落对齐。
                contentPadding = PaddingValues(start = 0.dp, end = 8.dp, top = 0.dp, bottom = 0.dp)
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "复制全部",
                    modifier = Modifier.padding(start = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** 抄自 rikkahub：思考卡片三态。生成中 Collapsed→Preview（渐隐预览+自动滚底），完成后可收回。 */
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
 * 思考过程行——整行可点击展开/收回（抄 rikkahub 的 ChainOfThought 交互）。
 * - 生成中：默认 Preview，内容渐隐、限高 100dp 并自动滚到底部，点标题切成完全展开/预览；
 * - 生成完成：按设置决定默认展开还是收回（showThoughts），点标题随时切换；
 * - 内容走 MarkdownText，思考里的列表/代码/表格也能正常渲染。
 */
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
    //
    // 1.4.5：两种状态都要跟着设置走。以前 else 分支只处理 Preview，历史/已完成的
    // 思考卡初始就是 Collapsed，设置里的「展开思考过程」对它们毫无效果。
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
        // 底色不自己刷：思考卡多数时候嵌在 StepsGroup 的 surfaceVariant 底上，
        // 再叠一层同色 0.5 会显得比外层深一块（同一块区域被涂了两遍）。
        // 透明即「跟下层一致」——在分组里露出分组色，单独出现时露出聊天背景。
        cardColors = CardDefaults.cardColors(containerColor = Color.Transparent),
        // 双重保险：外层（StepsGroup 或流式期）任一情况要关，这里都不再叠动画。
        animate = animate && !streaming,
    ) {
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
            extra = { ThinkingSeconds(streaming) },
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

/**
 * 工具调用行：默认只占一行（类别图标 + 标题 + 状态 + 箭头），点开才铺输出。
 *
 * 直接复用思考卡的 [ChainOfThought] + [ControlledChainOfThoughtStep]，而不是照着
 * 参数再抄一遍：图标槽 24dp、图标 20dp、间距 8dp、行高 8dp、内容缩进 32dp、
 * 卡片 12dp 内边距——全部由那套组件给出，两边的图标与文字天然落在同一条竖线上。
 * 手抄参数那一版差出半个图标宽，正是截图里"左侧没对齐"的原因。
 *
 * 图标用 MD3 的类别图标、一律默认前景色；状态只靠尾部一枚小标记表达
 * （转圈 = 在跑、勾 = 完成、叉 = 失败），整行不再随状态染色。
 */
@Composable
private fun ToolRow(message: ChatMessage) {
    val title = message.text.lineSequence().firstOrNull().orEmpty()
    val kind = remember(title) { toolKindOf(title) }
    val detail = message.detail.trim()
    val hasDetail = detail.isNotBlank()
    var expanded by rememberSaveable(message.id) { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    ChainOfThought(
        steps = listOf(Unit),
        collapsedAdaptiveWidth = false,
        // 透明底：在分组里露出分组色，单独出现时露出聊天背景，与思考卡同一处理。
        cardColors = CardDefaults.cardColors(containerColor = Color.Transparent),
        // 高度动画交给外层 StepsGroup 的 animateContentSize，这里不叠第二层。
        animate = false,
    ) {
        ControlledChainOfThoughtStep(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            icon = chainStepIcon(kind.icon, muted),
            label = {
                Text(
                    text = title.ifBlank { "调用工具" },
                    style = MaterialTheme.typography.labelSmall,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            extra = {
                when (message.toolState) {
                    // 转圈保留主色：整行只有这一处还在"动"，一眼能看出没卡住。
                    ToolState.RUNNING -> CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 1.4.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    ToolState.DONE -> Icon(Icons.Filled.Check, null, Modifier.size(12.dp), tint = muted)
                    // 失败是唯一保留彩色的终态：一条叉和一条勾在灰底上太难分辨。
                    ToolState.FAILED -> Icon(
                        Icons.Filled.Close, null, Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    ToolState.NONE -> Unit
                }
            },
            // 没有输出就不给内容槽：StepContent 据此不画箭头、也不接点击。
            content = if (hasDetail) {
                {
                    // 与思考正文同一铺法：完整铺开、不限高、不内滚。内容在归约时已截到
                    // 2000 字符，不会真的无限长；等宽小字更适合命令输出。
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        ),
                        color = muted
                    )
                }
            } else null,
        )
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

/** 用户消息里的图片缩略图（本机副本，filesDir/attachments/<会话>/）。 */
@Composable
fun LocalImageThumb(path: String, modifier: Modifier = Modifier) {
    var bitmap by remember(path) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(path) {
        bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            android.graphics.BitmapFactory.decodeFile(path)
        }
    }
    bitmap?.let { bmp ->
        androidx.compose.foundation.Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "消息图片",
            // Fit：按比例缩进 140dp 方框内，既不会横向铺满聊天气泡，
            // 竖图也不会把一屏占掉；看清内容够了，大图点开系统相册看。
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = modifier
                .widthIn(max = 140.dp)
                .heightIn(max = 140.dp)
                .clip(RoundedCornerShape(12.dp))
        )
    }
}

/** 用户消息里的文件片：图标 + 文件名（内容在沙箱，模型按路径读）。 */
@Composable
fun FileChip(att: cn.cloudbuddy.app.data.MsgAttachment, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                Icons.Filled.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                att.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}
