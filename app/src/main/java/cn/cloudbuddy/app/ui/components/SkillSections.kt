package cn.cloudbuddy.app.ui.components

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.cloudbuddy.app.data.AppUiState
import cn.cloudbuddy.app.data.BuddyRepository
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
 * MCP 服务器卡片。
 *
 * 顶部那段说明不是客套话：用户在别处（桌面端 / 沙箱里）配好的 MCP 不会出现在
 * 这里，这里改的也不会同步回账号。把边界写在界面上，比写在文档里有用。
 */
@Composable
fun McpCard(ui: AppUiState, repo: BuddyRepository) {
    var editing by remember { mutableStateOf<McpServer?>(null) }
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    SettingSurface {
        Text(
            "MCP 服务器只在建立会话时下发给云端沙箱，改动对下一次进入会话生效。" +
                "云端没有提供 MCP 管理接口，所以这里的列表仅存在于本机。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
        if (ui.mcpServers.isNotEmpty()) SectionDivider()

        ui.mcpServers.forEach { server ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                Switch(
                    checked = server.enabled,
                    onCheckedChange = { repo.setMcpEnabled(server.id, it) }
                )
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
