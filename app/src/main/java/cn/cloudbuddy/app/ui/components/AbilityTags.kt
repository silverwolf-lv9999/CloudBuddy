package cn.cloudbuddy.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 模型能力指示（照 RikkaHub 的 ModelModalityTag / ModelAbilityTag）：
 *
 * - 模态：输入图标（文本，读图模型多一个图片图标）→ 箭头 → 输出图标（文本）；
 * - 工具：扳手；思考：灯泡；上下文：目录里查到的长度（200K）。
 *
 * [inline] 模式是一排裸小图标（选择弹窗的行内用）；默认模式是带彩色底的
 * 圆角小 chips（模型卡 / 聊天选择器用，照 RikkaHub 的 Tag + TagType 配色）。
 * 一个都不显示时整个组件不占位。
 */
@Composable
fun ModelAbilityTags(
    vision: Boolean,
    tools: Boolean,
    reasoning: Boolean,
    /** 目录查到的上下文长度（tokens）；null 或 <1000 不显示。 */
    contextTokens: Int?,
    modifier: Modifier = Modifier,
    inline: Boolean = false,
) {
    val iconSize = if (inline) 13.dp else 12.dp
    val modality = listOf(Icons.Filled.Notes) + if (vision) listOf(Icons.Filled.Image) else emptyList()
    val extras = buildList {
        if (tools) add(Icons.Filled.Build to Tint.TOOLS)
        if (reasoning) add(Icons.Filled.Psychology to Tint.REASONING)
    }
    val showCtx = contextTokens != null && contextTokens >= 1000
    if (!inline) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AbilityChip(modality.first(), null, iconSize)
            if (vision) AbilityChip(Icons.Filled.Image, Tint.VISION, iconSize)
            AbilityChip(Icons.AutoMirrored.Filled.ArrowForward, null, iconSize)
            AbilityChip(Icons.Filled.Notes, null, iconSize)
            extras.forEach { (icon, tint) -> AbilityChip(icon, tint, iconSize) }
            if (showCtx) CtxChip(contextTokens / 1000)
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            modality.forEach { BareIcon(it, null, iconSize) }
            BareIcon(Icons.AutoMirrored.Filled.ArrowForward, null, iconSize)
            BareIcon(Icons.Filled.Notes, null, iconSize)
            extras.forEach { (icon, tint) -> BareIcon(icon, tint, iconSize) }
            if (showCtx) {
                Text(
                    "${contextTokens / 1000}K",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AbilityChip(icon: ImageVector, tint: Tint?, iconSize: Dp) {
    val bg = when (tint) {
        Tint.VISION -> MaterialTheme.colorScheme.secondaryContainer
        Tint.TOOLS -> MaterialTheme.colorScheme.tertiaryContainer
        Tint.REASONING -> MaterialTheme.colorScheme.primaryContainer
        null -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when (tint) {
        Tint.VISION -> MaterialTheme.colorScheme.onSecondaryContainer
        Tint.TOOLS -> MaterialTheme.colorScheme.onTertiaryContainer
        Tint.REASONING -> MaterialTheme.colorScheme.onPrimaryContainer
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(6.dp), color = bg) {
        Icon(icon, null, modifier = Modifier.padding(3.dp).size(iconSize), tint = fg)
    }
}

@Composable
private fun CtxChip(kTokens: Int) {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            "${kTokens}K",
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun BareIcon(icon: ImageVector, tint: Tint?, iconSize: Dp) {
    Icon(icon, null, modifier = Modifier.size(iconSize), tint = if (tint == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
}

private enum class Tint { VISION, TOOLS, REASONING }
