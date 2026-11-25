package cn.cloudbuddy.app.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cn.cloudbuddy.app.BuildConfig
import cn.cloudbuddy.app.data.AppUiState
import cn.cloudbuddy.app.data.BuddyRepository
import cn.cloudbuddy.app.data.EndpointResolver

/**
 * 设置二级页的通用骨架。
 *
 * 设置项多到一屏放不下之后，按主题拆成二级页比继续往下堆更符合「找得到」这个目标：
 * 用户打开设置时想的是「改主题」或「换模型」，不是一个超长列表里的第 17 个开关。
 * 每页只保留同一主题的项，标题栏带返回。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding -> content(padding) }
}

/** 二级页统一用的列表容器：内边距与间隔跟首页保持一致，切页时视觉不跳。 */
@Composable
internal fun SettingsList(
    padding: PaddingValues,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    LazyColumn(
        modifier = Modifier.padding(padding).fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
internal fun SectionTitle(text: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp)
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * 列表里的一个分组标题。
 *
 * 单独给 contentType 是为了把「标题槽位」和「卡片槽位」分开：两者高度差十倍，
 * 混用同一批槽位时，滚动经过交界处每个槽位都要丢掉旧的组合再建新的，
 * 视觉上就是一跳一跳的。
 */
internal fun LazyListScope.title(text: String, icon: ImageVector) {
    item(key = "title:$text", contentType = "title") { SectionTitle(text, icon) }
}

@Composable
internal fun SettingCard(content: @Composable () -> Unit) {
    // 用 Surface 而不是 Card：Card 默认带 1dp 阴影，阴影在滚动时每帧都要重绘
    // （模糊半径扩大脏区），一屏十来张卡就是设置页卡顿的主因。颜色差已经够分层。
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column { content() }
    }
}

@Composable
internal fun SettingRow(
    title: String,
    subtitle: String,
    trailing: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}

/**
 * 首页的一个入口行：点进二级页。
 *
 * 右侧固定给一个箭头，别再放「当前值」——有的项值很长（接口地址），
 * 塞在箭头左边会被挤成两行；当前值统一放副标题，扫一眼就够。
 */
@Composable
internal fun SettingsEntryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 一行标题 + 副标题 + 一排可选项（用于「渲染多少轮」「超时多少秒」）。 */
@Composable
internal fun ChipSetting(
    title: String,
    subtitle: String,
    options: List<Pair<Int, String>>,
    selected: Int,
    onSelect: (Int) -> Unit
) = Chips(title, subtitle, options, selected, onSelect)

/** 字符串取值的同款选项行（折叠方式、主题这种非数值设置用）。 */
@Composable
internal fun ChipSetting(
    title: String,
    subtitle: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) = Chips(title, subtitle, options, selected, onSelect)

@Composable
internal fun <T> Chips(
    title: String,
    subtitle: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                val active = selected == value
                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.clickable { onSelect(value) }
                ) {
                    Text(
                        label,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (active) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/** 用系统浏览器打开链接；没有可用应用时提示，而不是静默失败。 */
internal fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure {
            Toast.makeText(context, "没有可以打开链接的应用", Toast.LENGTH_SHORT).show()
        }
}

/**
 * 是否跟随上游 GitHub release 更新。
 *
 * 只有 applicationId 与原版一致的构建才成立。共存版换了包名（也换了签名），
 * 上游发的是原版 APK，下载下来只会安装失败——所以直接关掉这条路，如实说明。
 */
internal val FOLLOWS_UPSTREAM = BuildConfig.APPLICATION_ID == "cn.cloudbuddy.app"

/** 余额文案：有等级时一起展示，避免再多一行。 */
internal fun balanceText(ui: AppUiState): String {
    val growth = ui.growth ?: return "正在获取…"
    val level = if (growth.level > 0) {
        " · Lv.${growth.level}" + if (growth.levelName.isNotBlank()) " ${growth.levelName}" else ""
    } else {
        ""
    }
    return "${growth.balance} 积分$level"
}

/** 自定义端点的一句话摘要：首页入口与推理引擎页共用，避免两处说法不一致。 */
internal fun customApiSummary(repo: BuddyRepository, ui: AppUiState): String {
    val base = repo.settings.customBaseUrl
    if (base.isBlank()) return "未配置接口地址"
    val host = EndpointResolver.resolve(base).baseUrl
        .removePrefix("https://").removePrefix("http://").trimEnd('/')
    val model = repo.settings.customModelId.ifBlank { "未选模型" }
    val count = ui.customModels.size
    return host + " · " + model + if (count > 0) " · $count 个模型" else ""
}
