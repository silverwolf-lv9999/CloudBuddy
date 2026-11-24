package cn.cloudbuddy.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * DataTable（抄自 rikkahub）：自定义布局 + 横向滚动 + 行内等高。
 * - SubcomposeLayout 两阶段测量，避免 Lookahead 下的重复测量异常
 * - 高度自适应内容（不提供纵向滚动）；宽度可超出视口，外层内置 horizontalScroll
 * - 列宽按内容决定，内容较窄时按比例拉伸铺满视口；按列/按单元格覆盖对齐
 */
@Composable
fun DataTable(
    headers: List<@Composable () -> Unit>,
    rows: List<List<@Composable () -> Unit>>,
    modifier: Modifier = Modifier,
    cellPadding: Dp = 4.dp,
    cellBorder: BorderStroke? = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
    headerBackground: Color = MaterialTheme.colorScheme.surfaceVariant,
    zebraStriping: Boolean = false,
    columnMinWidths: List<Dp> = emptyList(),
    columnMaxWidths: List<Dp> = emptyList(),
    cellAlignment: Alignment = Alignment.CenterStart,
    columnAlignments: List<Alignment> = emptyList(),
    bodyCellAlignment: ((row: Int, column: Int) -> Alignment?)? = null,
    outerBorder: BorderStroke? = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    shape: Shape = MaterialTheme.shapes.small,
    stretchToFillWidth: Boolean = true,
) {
    val hScroll = rememberScrollState()
    val surfaceContainer = MaterialTheme.colorScheme.surfaceContainer

    BoxWithConstraints(
        modifier = modifier
            .clip(shape)
            .then(if (outerBorder != null) Modifier.border(outerBorder, shape) else Modifier)
    ) {
        val viewportMaxWidth = constraints.maxWidth

        Box(modifier = Modifier.horizontalScroll(hScroll)) {
            SubcomposeLayout { constraints ->
                val columnCount = max(headers.size, rows.maxOfOrNull { it.size } ?: 0)
                val rowCount = rows.size
                if (columnCount == 0) return@SubcomposeLayout layout(0, 0) {}

                val infinity = Constraints.Infinity
                val unbounded = Constraints(0, infinity, 0, infinity)
                val minWidthsPx = IntArray(columnCount) { i -> columnMinWidths.getOrNull(i)?.roundToPx() ?: 0 }
                val maxWidthsPx = IntArray(columnCount) { i -> columnMaxWidths.getOrNull(i)?.roundToPx() ?: Int.MAX_VALUE }
                val colWidths = IntArray(columnCount) { 0 }
                val headerP1 = arrayOfNulls<androidx.compose.ui.layout.Placeable>(columnCount)
                val bodyP1 = arrayOfNulls<androidx.compose.ui.layout.Placeable>(rowCount * columnCount)

                fun subcomposeHeaderOnce(c: Int): androidx.compose.ui.layout.Placeable {
                    val measurables = subcompose("h1_$c") {
                        CellBox(
                            padding = cellPadding,
                            border = cellBorder,
                            background = headerBackground,
                            alignment = columnAlignments.getOrNull(c) ?: cellAlignment
                        ) { headers.getOrNull(c)?.invoke() }
                    }
                    val headerConstraints = if (maxWidthsPx[c] != Int.MAX_VALUE) {
                        Constraints(0, maxWidthsPx[c], 0, infinity)
                    } else {
                        unbounded
                    }
                    val p = measurables.first().measure(headerConstraints)
                    colWidths[c] = max(colWidths[c], max(p.width, minWidthsPx[c])).coerceAtMost(maxWidthsPx[c])
                    return p
                }

                fun subcomposeBodyOnce(r: Int, c: Int): androidx.compose.ui.layout.Placeable {
                    val bg = if (zebraStriping && r % 2 == 1) surfaceContainer else Color.Transparent
                    val measurables = subcompose("b1_${r}_$c") {
                        CellBox(
                            padding = cellPadding,
                            border = cellBorder,
                            background = bg,
                            alignment = bodyCellAlignment?.invoke(r, c) ?: columnAlignments.getOrNull(c) ?: cellAlignment
                        ) { rows[r].getOrNull(c)?.invoke() }
                    }
                    val constraints = if (maxWidthsPx[c] != Int.MAX_VALUE) {
                        Constraints(0, maxWidthsPx[c], 0, infinity)
                    } else {
                        unbounded
                    }
                    val p = measurables.first().measure(constraints)
                    colWidths[c] = max(colWidths[c], max(p.width, minWidthsPx[c])).coerceAtMost(maxWidthsPx[c])
                    return p
                }

                for (c in 0 until columnCount) headerP1[c] = subcomposeHeaderOnce(c)
                for (r in 0 until rowCount) for (c in 0 until columnCount) {
                    bodyP1[r * columnCount + c] = subcomposeBodyOnce(r, c)
                }

                val rowHeights = IntArray(rowCount) { r ->
                    var h = 0
                    for (c in 0 until columnCount) h = max(h, bodyP1[r * columnCount + c]!!.height)
                    h
                }
                val headerHeight = headerP1.maxOf { it?.height ?: 0 }

                val naturalWidth = colWidths.sum()
                if (stretchToFillWidth &&
                    viewportMaxWidth != Constraints.Infinity &&
                    viewportMaxWidth > naturalWidth &&
                    naturalWidth > 0
                ) {
                    val extra = viewportMaxWidth - naturalWidth
                    var distributed = 0
                    for (c in 0 until columnCount) {
                        val add = if (c == columnCount - 1) {
                            extra - distributed
                        } else {
                            (extra.toLong() * colWidths[c] / naturalWidth).toInt()
                        }
                        colWidths[c] += add
                        distributed += add
                    }
                }

                fun constraintsFor(colWidth: Int, minH: Int): Constraints = Constraints(
                    minWidth = colWidth.coerceAtLeast(0),
                    maxWidth = colWidth.coerceAtLeast(0),
                    minHeight = minH.coerceAtLeast(0),
                    maxHeight = infinity,
                )

                val headerPlaceables = Array(columnCount) { c ->
                    val measurables = subcompose("h2_$c") {
                        CellBox(
                            padding = cellPadding,
                            border = cellBorder,
                            background = headerBackground,
                            alignment = columnAlignments.getOrNull(c) ?: cellAlignment
                        ) { headers.getOrNull(c)?.invoke() }
                    }
                    measurables.first().measure(constraintsFor(colWidths[c], headerHeight))
                }

                val bodyPlaceables = Array(rowCount * columnCount) { i ->
                    val r = i / columnCount
                    val c = i % columnCount
                    val bg = if (zebraStriping && r % 2 == 1) surfaceContainer else Color.Transparent
                    val measurables = subcompose("b2_${r}_$c") {
                        CellBox(
                            padding = cellPadding,
                            border = cellBorder,
                            background = bg,
                            alignment = bodyCellAlignment?.invoke(r, c) ?: columnAlignments.getOrNull(c) ?: cellAlignment
                        ) { rows[r].getOrNull(c)?.invoke() }
                    }
                    measurables.first().measure(constraintsFor(colWidths[c], rowHeights[r]))
                }

                val tableWidth = colWidths.sum()
                val tableHeight = headerHeight + rowHeights.sum()
                val finalWidth = tableWidth.coerceIn(constraints.minWidth, constraints.maxWidth)
                val finalHeight = tableHeight.coerceIn(constraints.minHeight, constraints.maxHeight)

                layout(finalWidth, finalHeight) {
                    var x = 0
                    for (c in 0 until columnCount) {
                        headerPlaceables[c].placeRelative(x, 0)
                        x += colWidths[c]
                    }
                    var y = headerHeight
                    for (r in 0 until rowCount) {
                        x = 0
                        for (c in 0 until columnCount) {
                            bodyPlaceables[r * columnCount + c].placeRelative(x, y)
                            x += colWidths[c]
                        }
                        y += rowHeights[r]
                    }
                }
            }
        }
    }
}

@Composable
private fun CellBox(
    padding: Dp,
    border: BorderStroke?,
    background: Color,
    alignment: Alignment,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .then(if (background != Color.Transparent) Modifier.background(background) else Modifier)
            .then(if (border != null) Modifier.border(border) else Modifier)
            .padding(padding),
        contentAlignment = alignment,
    ) { content() }
}
