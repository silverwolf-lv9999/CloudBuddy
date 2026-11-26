package cn.cloudbuddy.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardElevation
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed

/**
 * 以时间线/步骤卡片的形式展示一组思考过程（抄自 rikkahub 的 ChainOfThought）。
 * - 步骤较多时自动折叠，只显示最后 [collapsedVisibleCount] 步
 * - 点顶部控制条展开/收起全部
 * - 每个步骤的 label 行整行可点击展开/收回，箭头翻转，内容带动画
 */
@Composable
fun <T> ChainOfThought(
    modifier: Modifier = Modifier,
    cardColors: CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ),
    steps: List<T>,
    collapsedVisibleCount: Int = 2,
    collapsedAdaptiveWidth: Boolean = false,
    /**
     * 卡片抬起高度，默认贴地（0dp）。
     *
     * 这套卡片几乎总是垫在分组底色之上，抬 1dp 的阴影在视觉上看不出来，却要给每张卡
     * 多画一层阴影——长会话里卡片数量按百算，滑动和展开都会因此发沉。
     */
    cardElevation: CardElevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    /** 生成中的流式内容关闭高度动画，避免每个分片都触发一轮测量；展开/收回仍保留动画。 */
    animate: Boolean = true,
    content: @Composable ChainOfThoughtScope.(T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val canCollapse = steps.size > collapsedVisibleCount
    val shouldFillCollapseControlWidth = expanded || !collapsedAdaptiveWidth

    Card(
        modifier = modifier,
        colors = cardColors,
        shape = RoundedCornerShape(20.dp),
        elevation = cardElevation
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .then(if (animate) Modifier.animateContentSize() else Modifier),
        ) {
            val visibleSteps = if (expanded || !canCollapse) steps else steps.takeLast(collapsedVisibleCount)

            if (canCollapse) {
                Row(
                    modifier = Modifier
                        .then(if (shouldFillCollapseControlWidth) Modifier.fillMaxWidth() else Modifier)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { expanded = !expanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        modifier = Modifier.padding(start = 8.dp),
                        text = if (expanded) "收起" else "还有 ${steps.size - collapsedVisibleCount} 个步骤",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            val lineColor = MaterialTheme.colorScheme.outlineVariant
            Column {
                visibleSteps.fastForEachIndexed { index, step ->
                    val isFirst = index == 0
                    val isLast = index == visibleSteps.lastIndex
                    val scope = remember(isFirst, isLast, lineColor) {
                        ChainOfThoughtScopeImpl(isFirst, isLast, lineColor)
                    }
                    scope.content(step)
                }
            }
        }
    }
}

/** 单个思考步骤的渲染作用域，对齐 rikkahub 的交互：整行点击展开/收回。 */
interface ChainOfThoughtScope {
    @Composable
    fun ChainOfThoughtStep(
        icon: (@Composable () -> Unit)? = null,
        label: (@Composable () -> Unit),
        extra: (@Composable () -> Unit)? = null,
        onClick: (() -> Unit)? = null,
        collapsedAdaptiveWidth: Boolean = false,
        content: (@Composable () -> Unit)? = null,
    )

    @Composable
    fun ControlledChainOfThoughtStep(
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        icon: (@Composable () -> Unit)? = null,
        label: (@Composable () -> Unit),
        extra: (@Composable () -> Unit)? = null,
        onClick: (() -> Unit)? = null,
        collapsedAdaptiveWidth: Boolean = false,
        contentVisible: Boolean = expanded,
        content: (@Composable () -> Unit)? = null,
    )
}

private class ChainOfThoughtScopeImpl(
    private val isFirst: Boolean,
    private val isLast: Boolean,
    private val lineColor: Color,
) : ChainOfThoughtScope {
    @Composable
    override fun ChainOfThoughtStep(
        icon: @Composable (() -> Unit)?,
        label: @Composable () -> Unit,
        extra: @Composable (() -> Unit)?,
        onClick: (() -> Unit)?,
        collapsedAdaptiveWidth: Boolean,
        content: @Composable (() -> Unit)?
    ) {
        var expanded by remember { mutableStateOf(false) }
        StepContent(icon, label, extra, onClick, collapsedAdaptiveWidth, expanded, { expanded = it }, expanded, content)
    }

    @Composable
    override fun ControlledChainOfThoughtStep(
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        icon: @Composable (() -> Unit)?,
        label: @Composable () -> Unit,
        extra: @Composable (() -> Unit)?,
        onClick: (() -> Unit)?,
        collapsedAdaptiveWidth: Boolean,
        contentVisible: Boolean,
        content: @Composable (() -> Unit)?
    ) = StepContent(icon, label, extra, onClick, collapsedAdaptiveWidth, expanded, onExpandedChange, contentVisible, content)

    @Composable
    private fun StepContent(
        icon: @Composable (() -> Unit)?,
        label: @Composable () -> Unit,
        extra: @Composable (() -> Unit)?,
        onClick: (() -> Unit)?,
        collapsedAdaptiveWidth: Boolean,
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        contentVisible: Boolean,
        content: @Composable (() -> Unit)?
    ) = ChainStep(
        icon = icon,
        label = label,
        extra = extra,
        onClick = onClick,
        collapsedAdaptiveWidth = collapsedAdaptiveWidth,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        contentVisible = contentVisible,
        isFirst = isFirst,
        isLast = isLast,
        lineColor = lineColor,
        content = content
    )
}

/** 给步骤用的小图标插槽，便于外部直接传 material 图标。 */
fun chainStepIcon(vector: ImageVector, tint: Color, modifier: Modifier = Modifier): @Composable () -> Unit = {
    Icon(vector, contentDescription = null, modifier = modifier.fillMaxSize(), tint = tint)
}

/**
 * 单步布局本体：图标槽 → 标签 → 尾部控件 →（可选）内容，左侧竖线由 drawBehind 画。
 *
 * 单独提出来，是为了让「一步」不必先有卡片外壳。工具行一轮里可能有上百条，
 * 每条都套一层 Card（阴影 + 圆角裁剪）等于给每行白加两层绘制层，展开和滑动都发沉；
 * 抽成裸布局后，分组内直接铺行，只有真正需要的场合（思考卡）才由
 * [ChainOfThought] 套上卡片。
 *
 * 参数只有这一份：图标槽 24dp、图标 14dp、行内留白 [rowPadding]、内容缩进 32dp。
 * 各处不用照着再抄一遍——手抄差出半个图标宽，正是之前「左边没对齐」的原因。
 */
@Composable
internal fun ChainStep(
    icon: (@Composable () -> Unit)?,
    label: @Composable () -> Unit,
    extra: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    collapsedAdaptiveWidth: Boolean = false,
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    contentVisible: Boolean = expanded,
    /**
     * 行内上下留白，也就是点击热区比文字多出来的部分。
     *
     * 分组里连着几十行时用它收窄行距：行距是「留白 + 组间距 + 留白」三段相加，
     * 每段各 8dp 就会拉出 24dp 的空档，一行工具调用看着比正文还占地方。
     */
    rowPadding: Dp = 8.dp,
    isFirst: Boolean = true,
    isLast: Boolean = true,
    lineColor: Color = MaterialTheme.colorScheme.outlineVariant,
    content: (@Composable () -> Unit)? = null,
) {
    val hasContent = content != null
    val shouldFillMaxWidth = !collapsedAdaptiveWidth || contentVisible

    Column(modifier = Modifier.then(if (shouldFillMaxWidth) Modifier.fillMaxWidth() else Modifier)) {
        Row(
            modifier = Modifier
                .drawBehind {
                    val x = 12.dp.toPx()
                    val centerY = size.height / 2
                    val gap = 10.dp.toPx()
                    val strokeWidth = 1.dp.toPx()
                    if (!isFirst) drawLine(lineColor, Offset(x, 0f), Offset(x, centerY - gap), strokeWidth)
                    if (!isLast) drawLine(lineColor, Offset(x, centerY + gap), Offset(x, size.height), strokeWidth)
                }
                .then(if (shouldFillMaxWidth) Modifier.fillMaxWidth() else Modifier)
                .then(
                    when {
                        onClick != null -> Modifier.clip(MaterialTheme.shapes.small).clickable { onClick() }
                        hasContent -> Modifier.clip(MaterialTheme.shapes.small).clickable { onExpandedChange(!expanded) }
                        else -> Modifier
                    }
                )
                .padding(vertical = rowPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 图标槽只留两层：24dp 的槽装 14dp 的图标。中间那个 20dp 空盒不多画任何
            // 东西，只是一个布局节点——而这一行在一轮任务里要出现上百次。
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                if (icon != null) {
                    Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) { icon() }
                } else {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
                }
            }
            Box(Modifier.then(if (shouldFillMaxWidth) Modifier.weight(1f) else Modifier)) { label() }
            if (extra != null) extra()
            when {
                onClick != null -> Icon(
                    Icons.Filled.ChevronRight, null, Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                hasContent -> Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (contentVisible && hasContent) {
            Box(
                modifier = Modifier
                    .then(if (shouldFillMaxWidth) Modifier.fillMaxWidth() else Modifier)
                    .drawBehind {
                        if (!isLast) {
                            drawLine(
                                lineColor,
                                Offset(12.dp.toPx(), 0f),
                                Offset(12.dp.toPx(), size.height),
                                1.dp.toPx(),
                            )
                        }
                    }
                    .padding(start = 32.dp, top = 4.dp, bottom = 8.dp)
            ) { content() }
        }
    }
}
