package cn.cloudbuddy.app.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.cloudbuddy.app.data.AppUiState
import cn.cloudbuddy.app.data.BuddyRepository
import cn.cloudbuddy.app.data.Connector
import cn.cloudbuddy.app.data.McpServer
import cn.cloudbuddy.app.data.SkillInfo

/**
 * 技能与 MCP 两张卡片。
 *
 * 放在组件包里而不是塞进设置页：这两块各带一个对话框和一次网络请求，
 * 混进那个已经六百多行的文件里会彻底没法读。
 */
@Composable
fun SkillsCard(ui: AppUiState, repo: BuddyRepository) {
    var market by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }

    // 进到设置页就拉一次；已经有数据就先用着，避免每次进设置都闪一下加载态。
    LaunchedEffect(Unit) {
        if (ui.skills.isEmpty() && !ui.skillsLoading) repo.refreshSkills()
    }

    SettingSurface {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("账号技能", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (ui.skills.isEmpty() && ui.skillsError == null) "正在读取…"
                    else "${ui.skills.count { it.enabled }} / ${ui.skills.size} 个已启用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (ui.skillsLoading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { repo.refreshSkills() }) {
                    Icon(Icons.Filled.Refresh, "刷新技能", Modifier.size(18.dp))
                }
            }
        }

        ui.skillsError?.let { error ->
            Text(
                "读取失败：$error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }

        ui.skills.forEach { skill ->
            SkillRow(skill) { enabled -> repo.setSkillEnabled(skill, enabled) }
            SectionDivider()
        }

        if (ui.skills.isEmpty() && !ui.skillsLoading && ui.skillsError == null) {
            Text(
                "这个账号还没有技能。可以从技能市场装一个试试。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    market = true
                    if (ui.marketSkills.isEmpty()) repo.searchMarket("")
                }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("浏览技能市场", style = MaterialTheme.typography.titleMedium)
        }
    }

    if (market) {
        AlertDialog(
            onDismissRequest = { market = false },
            title = { Text("技能市场") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        label = { Text("搜索") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { repo.searchMarket(keyword) }) {
                                Icon(Icons.Filled.Search, "搜索", Modifier.size(18.dp))
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (ui.marketLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在搜索…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Column(
                        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (ui.marketSkills.isEmpty() && !ui.marketLoading) {
                            Text(
                                "没有结果。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        ui.marketSkills.forEach { skill ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(skill.title, style = MaterialTheme.typography.titleSmall)
                                    if (skill.description.isNotBlank()) {
                                        Text(
                                            skill.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                TextButton(onClick = { repo.installSkill(skill) }) { Text("安装") }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { market = false }) { Text("关闭") } }
        )
    }
}

@Composable
private fun SkillRow(skill: SkillInfo, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(skill.title, style = MaterialTheme.typography.titleMedium)
            val subtitle = buildList {
                if (skill.description.isNotBlank()) add(skill.description)
                if (skill.source.isNotBlank()) add(skill.source)
                skill.version?.let { add("v$it") }
            }.joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = skill.enabled, onCheckedChange = onToggle)
    }
}

/**
 * 账号里的 MCP 连接器。
 *
 * 和下面那张 [McpCard] 的区别一定要在界面上说清：这里的记录存在云端账号里，
 * 官方 App / 网页端改了，这里跟着变；下面是本机配置，只在这台设备的会话里生效。
 */
@Composable
fun ConnectorCard(ui: AppUiState, repo: BuddyRepository) {
    val context = LocalContext.current
    var catalogOpen by remember { mutableStateOf(false) }
    var keyword by remember { mutableStateOf("") }
    var unbind by remember { mutableStateOf<Connector?>(null) }
    var token by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (ui.connectors.isEmpty() && !ui.connectorsLoading) repo.refreshConnectors()
    }

    SettingSurface {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("账号 MCP 连接器", style = MaterialTheme.typography.titleMedium)
                Text(
                    "保存在账号里，与官方 App / 网页端同步",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (ui.connectorsLoading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = { repo.refreshConnectors() }) {
                    Icon(Icons.Filled.Refresh, "刷新连接器", Modifier.size(18.dp))
                }
            }
        }

        ui.connectorsError?.let { error ->
            Text(
                "读取失败：$error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }

        ui.connectors.forEach { connector ->
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(connector.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            connector.subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (connector.needsAuth) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = connector.active,
                        enabled = connector.bound && connector.authOk,
                        onCheckedChange = { repo.setConnectorActive(connector, it) }
                    )
                    IconButton(onClick = { unbind = connector }) {
                        Icon(Icons.Filled.Delete, "解绑", Modifier.size(16.dp))
                    }
                }
                if (connector.needsAuth) {
                    TextButton(onClick = { repo.connectConnector(connector) }) {
                        Text("重新授权")
                    }
                }
            }
            SectionDivider()
        }

        if (ui.connectors.isEmpty() && !ui.connectorsLoading && ui.connectorsError == null) {
            Text(
                "这个账号还没有连接 MCP。官方 App 里连过的会显示在这里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    catalogOpen = true
                    if (ui.connectorCatalog.isEmpty()) repo.loadConnectorCatalog()
                }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("添加 MCP 连接器", style = MaterialTheme.typography.titleMedium)
        }
    }

    unbind?.let { target ->
        AlertDialog(
            onDismissRequest = { unbind = null },
            title = { Text("解绑 ${target.title}？") },
            text = { Text("授权会一并失效，官方 App 和网页端也会同步。") },
            confirmButton = {
                TextButton(onClick = {
                    repo.removeConnector(target)
                    unbind = null
                }) { Text("解绑") }
            },
            dismissButton = { TextButton(onClick = { unbind = null }) { Text("取消") } }
        )
    }

    if (catalogOpen) {
        AlertDialog(
            onDismissRequest = { catalogOpen = false },
            title = { Text("MCP 连接器市场") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = { keyword = it },
                        label = { Text("搜索") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (ui.connectorCatalogLoading) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                    Column(
                        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val boundNames = ui.connectors.map { it.name }.toSet()
                        val results = ui.connectorCatalog.filter {
                            it.title.contains(keyword, true) || it.name.contains(keyword, true)
                        }
                        if (results.isEmpty() && !ui.connectorCatalogLoading) {
                            Text(
                                "没有结果。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        results.forEach { item ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.title, style = MaterialTheme.typography.titleSmall)
                                    if (item.description.isNotBlank()) {
                                        Text(
                                            item.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                if (item.name in boundNames) {
                                    Text(
                                        "已添加",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    TextButton(onClick = { repo.connectConnector(item) }) {
                                        Text("连接")
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { catalogOpen = false }) { Text("关闭") } }
        )
    }

    ui.connectorAuth?.let { pending ->
        AlertDialog(
            onDismissRequest = { repo.dismissConnectorAuth() },
            title = { Text("连接 ${pending.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (pending.flow.action) {
                        "redirect" -> {
                            Text("需要在浏览器里完成授权，回来后点「已完成」。")
                            pending.flow.url?.let { url ->
                                TextButton(onClick = {
                                    runCatching {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }.onFailure {
                                        Toast.makeText(context, "没有可以打开链接的应用", Toast.LENGTH_SHORT).show()
                                    }
                                }) { Text("打开授权页") }
                            }
                            TextButton(onClick = { repo.checkConnectorAuth() }) { Text("已完成授权") }
                        }
                        "show_form" -> {
                            Text("输入这个连接器的 Token：")
                            OutlinedTextField(
                                value = token,
                                onValueChange = { token = it },
                                label = { Text("Token") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            TextButton(onClick = { repo.submitConnectorToken(token) }) { Text("提交") }
                        }
                        "poll" -> {
                            Text("在浏览器打开 ${pending.flow.verificationUri.orEmpty()}，输入验证码：")
                            Text(
                                pending.flow.userCode.orEmpty(),
                                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace)
                            )
                            TextButton(onClick = { repo.checkConnectorAuth() }) { Text("已完成授权") }
                        }
                        else -> Text("这个连接器已经连上了，关掉窗口即可刷新。")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { repo.dismissConnectorAuth() }) { Text("关闭") }
            }
        )
    }
}

/**
 * 本机 MCP 服务器卡片。
 *
 * 和上面的账号连接器不是一回事：这里只往 ACP 的 `session/new` 下发配置，
 * 不会同步到账号。把边界写在界面上，比写在文档里有用。
 */
@Composable
fun McpCard(ui: AppUiState, repo: BuddyRepository) {
    var editing by remember { mutableStateOf<McpServer?>(null) }
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    SettingSurface {
        Text(
            "这里放自定义 MCP。同一条配置有两个去处，各管各的：\n" +
                "· 云端会话：建立会话时下发给云端沙箱，对下一次进入会话生效，不同步到账号；\n" +
                "· 本机会话：由手机自己连（只支持 url 型；command 型要在沙箱里跑进程，手机上做不到）。\n" +
                "市场里的 MCP 请用上面的「账号 MCP 连接器」。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
        if (ui.mcpServers.isNotEmpty()) SectionDivider()

        ui.mcpServers.forEach { server ->
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(server.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            server.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = {
                        draft = server.raw
                        error = null
                        editing = server
                    }) {
                        Icon(Icons.Filled.Refresh, "编辑", Modifier.size(16.dp))
                    }
                    IconButton(onClick = { repo.removeMcp(server.id) }) {
                        Icon(Icons.Filled.Delete, "删除", Modifier.size(16.dp))
                    }
                }
                // 两个开关并排，而不是塞进一个「全部启用」：云端和本机是两条完全不同的
                // 通道（服务端连 vs 手机自己连），能力也不同，合起来必然是误导。
                val isStdio = server.toJson()?.has("command") == true
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ScopeSwitch(
                        label = "云端会话",
                        hint = "随会话下发给沙箱",
                        checked = server.enabled,
                        enabled = true,
                        onChange = { repo.setMcpEnabled(server.id, it) }
                    )
                    ScopeSwitch(
                        label = "本机会话",
                        hint = if (isStdio) "command 型不支持" else "手机直连 url",
                        checked = server.local && !isStdio,
                        enabled = !isStdio,
                        onChange = { repo.setMcpLocal(server.id, it) }
                    )
                }
                if (server.local && isStdio) {
                    Text(
                        "这条是 command 型，本机会话用不了（已按不支持处理）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            SectionDivider()
        }

        if (ui.mcpServers.isEmpty()) {
            Text(
                "还没有配置。点下面添加一条，例如：\n" +
                    """{"name":"filesystem","command":"npx","args":["-y","@modelcontextprotocol/server-filesystem","/workspace"]}""",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    draft = ""
                    error = null
                    adding = true
                }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("＋", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(10.dp))
            Text("添加 MCP 服务器", style = MaterialTheme.typography.titleMedium)
        }
    }

    val open = adding || editing != null
    if (open) {
        AlertDialog(
            onDismissRequest = { adding = false; editing = null },
            title = { Text(if (editing != null) "编辑 MCP 服务器" else "添加 MCP 服务器") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it; error = null },
                        label = { Text("配置 JSON") },
                        minLines = 5,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth()
                    )
                    error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val failure = repo.saveMcp(draft, editing?.id)
                    if (failure == null) {
                        adding = false
                        editing = null
                    } else {
                        error = failure
                    }
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { adding = false; editing = null }) { Text("取消") }
            }
        )
    }
}

/** 设置页那种圆角卡片，本地复用一份，省得把 SettingsScreen 的私有组件暴露出去。 */
@Composable
private fun SettingSurface(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column { content() }
    }
}

@Composable
private fun SectionDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/**
 * 一个带说明的小开关（用于「云端会话 / 本机会话」这种作用域选择）。
 *
 * 标签在上、开关在下会让行高翻倍，所以横着排：左标签右开关，说明用极小的字跟着标签。
 */
@Composable
private fun ScopeSwitch(
    label: String,
    hint: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(6.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
