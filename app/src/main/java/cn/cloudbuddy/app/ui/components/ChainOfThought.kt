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
    content: @Composable ChainOfThoughtScope.(T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val canCollapse = steps.size > collapsedVisibleCount
    val shouldFillCollapseControlWidth = expanded || !collapsedAdaptiveWidth

    Card(modifier = modifier, colors = cardColors, shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .animateContentSize(),
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
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                        if (icon != null) {
                            Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) { icon() }
                        } else {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
                        }
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
}

/** 给步骤用的小图标插槽，便于外部直接传 material 图标。 */
fun chainStepIcon(vector: ImageVector, tint: Color, modifier: Modifier = Modifier): @Composable () -> Unit = {
    Icon(vector, contentDescription = null, modifier = modifier.fillMaxSize(), tint = tint)
}
