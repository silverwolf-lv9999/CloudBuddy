package cn.cloudbuddy.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** 底部导航的一项。 */
data class NavBarItem(val label: String, val icon: ImageVector)

/**
 * 底部导航栏（胶囊风格，几何照 RikkaHub 社区 PR #1 的 FloatingTabBar）。
 *
 * 两种形态共用一套视觉语言——64dp 高、primaryContainer 胶囊选中态、图标 22dp +
 * 小字标签：
 * - [floating] = true：悬浮居中胶囊，左右各留 28dp，底部按导航栏 inset 只让 8dp；
 * - [floating] = false：贴底通栏，顶部大圆角，通栏铺满宽度。
 *
 * **选中高亮带弹簧动画**：指示胶囊是独立的一层（画在图标底下），切页时位置与宽度
 * 一起用 spring 滑过去，比 Material3 自带的淡入淡出更有"实体感"；首帧直接落位，
 * 不做「从 0 长出来」的怪动画。
 */
@Composable
fun AppNavBar(
    items: List<NavBarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    floating: Boolean,
    modifier: Modifier = Modifier
) {
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    if (floating) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.padding(
                    start = 28.dp,
                    end = 28.dp,
                    // 有 inset 时只额外留 8dp，否则 12dp；再大胶囊下方会出现一截同色空白。
                    bottom = if (navInset != 0.dp) 8.dp + navInset else 12.dp
                ),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                tonalElevation = 3.dp,
                shadowElevation = 8.dp
            ) {
                NavBarRow(items, selectedIndex, onSelect, stretch = false)
            }
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp
        ) {
            Box(Modifier.padding(bottom = navInset)) {
                NavBarRow(items, selectedIndex, onSelect, stretch = true)
            }
        }
    }
}

/**
 * 一行导航项 + 会滑动的选中胶囊。
 *
 * 每一项用 onGloballyPositioned 回报自己在行内的矩形（px），指示层据此定位——
 * 不能假设等宽：悬浮形态下项宽由文字撑开（「会话」比「工作区」窄）。
 */
@Composable
private fun NavBarRow(
    items: List<NavBarItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    stretch: Boolean
) {
    val density = LocalDensity.current
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    val target = bounds[selectedIndex]

    val xAnim = remember { Animatable(0.dp, Dp.VectorConverter) }
    val wAnim = remember { Animatable(0.dp, Dp.VectorConverter) }
    LaunchedEffect(target, density) {
        val rect = target ?: return@LaunchedEffect
        val x = with(density) { rect.left.toDp() }
        val w = with(density) { rect.width.toDp() }
        if (wAnim.value == 0.dp) {
            // 首帧（或从不可见回来）直接落位，避免指示器从左上角飞过来。
            xAnim.snapTo(x)
            wAnim.snapTo(w)
        } else {
            coroutineScope {
                launch { xAnim.animateTo(x, INDICATOR_SPRING) }
                launch { wAnim.animateTo(w, INDICATOR_SPRING) }
            }
        }
    }

    Box {
        // 指示层：画在图标底下（先绘制者在下），只平移不参与布局。
        if (wAnim.value > 0.dp) {
            Box(
                modifier = Modifier
                    .offset(x = xAnim.value)
                    .width(wAnim.value)
                    .height(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
            )
        }
        Row(
            modifier = Modifier
                .then(if (stretch) Modifier.fillMaxWidth() else Modifier)
                .height(64.dp)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == selectedIndex
                val contentColor by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(180),
                    label = "navTint"
                )
                Box(
                    modifier = Modifier
                        .then(if (stretch) Modifier.weight(1f) else Modifier.defaultMinSize(minWidth = 76.dp))
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .onGloballyPositioned { coords ->
                            bounds[index] = coords.boundsInParent()
                        }
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(1.dp, Alignment.CenterVertically),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = item.label,
                            tint = contentColor,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            item.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/** 弹簧参数：起手快、收尾有一点点回弹，接近 Material Expressive 的手感。 */
private val INDICATOR_SPRING = spring<Dp>(
    dampingRatio = 0.72f,
    stiffness = Spring.StiffnessMediumLow
)
