package cn.cloudbuddy.app.ui.screens.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.cloudbuddy.app.data.AppUiState
import cn.cloudbuddy.app.data.BuddyRepository
import cn.cloudbuddy.app.data.LocalSkill
import cn.cloudbuddy.app.data.LocalSkillStore

/**
 * 本机技能。
 *
 * 与「云端能力」里的技能是两套：那批是账号资产、由云端 agent 执行；这一批存在手机上，
 * 只喂给本机会话（自定义 API）。所以页首第一句就得说清楚，否则用户装完发现
 * 云端会话里没生效，只会认为功能坏了。
 *
 * 存储形态是 `SKILL.md`（frontmatter + 正文），系统提示里只放名字与描述，
 * 正文等模型调用 `use_skill` 时再读——一条技能动辄上万字，全塞进提示词每轮都白付 token。
 */
@Composable
internal fun LocalSkillsPage(ui: AppUiState, repo: BuddyRepository, onBack: () -> Unit) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<LocalSkill?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // 选一个 .md 文件导入。读的是 SAF 的流，不需要存储权限。
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            error = "这个文件读不出内容（可能不是文本文件）"
            return@rememberLauncherForActivityResult
        }
        val name = displayName(context, uri) ?: "skill.md"
        error = repo.importLocalSkill(name, text)
    }

    SettingsPageScaffold("本机技能", onBack) { padding ->
        SettingsList(padding) {
            item(key = "intro", contentType = "skill-intro") {
                SettingCard {
                    Text(
                        "这里的技能只对**本机会话**（自定义 API）生效，正文存在手机上；" +
                            "云端会话用的技能在上一级的「云端会话 → 技能」里。\n" +
                            "系统提示里只带名字和描述，模型需要时才读全文，所以装几十条也不占上下文。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                    )
                }
            }

            if (ui.localSkills.isNotEmpty()) {
                title("已安装", Icons.Filled.Extension)
            }

            items(ui.localSkills, key = { it.id }, contentType = { "skill" }) { skill ->
                SettingCard {
                    SkillRow(
                        skill = skill,
                        enabled = skill.enabled,
                        onClick = { viewing = skill },
                        onToggle = { repo.setLocalSkillEnabled(skill.id, it) },
                    )
                }
            }

            if (ui.localSkills.isEmpty()) {
                item(key = "empty", contentType = "skill-intro") {
                    SettingCard {
                        Text(
                            "还没有装技能。技能就是一份 `SKILL.md`：开头写名字和一句话描述，" +
                                "后面写这类任务该怎么做；写清楚了模型才会在你提到相关事情时想起来用它。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                        )
                    }
                }
            }

            title("添加", Icons.Filled.Add)

            item(key = "import", contentType = "skill") {
                SettingCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { picker.launch(arrayOf("text/*", "application/octet-stream")) }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Upload, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("从文件导入", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "选一个 SKILL.md；没有 frontmatter 也能导入，名字取文件名",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item(key = "new", contentType = "skill") {
                SettingCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editing = true }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("手写一条", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            error?.let { message ->
                item(key = "error", contentType = "skill-intro") {
                    SettingCard {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (editing) {
        SkillEditorDialog(
            onDismiss = { editing = false },
            onSave = { id, content ->
                val result = repo.saveLocalSkill(id, content)
                if (result == null) {
                    editing = false
                    error = null
                } else {
                    error = result
                }
            },
        )
    }

    viewing?.let { skill ->
        SkillViewerDialog(
            skill = skill,
            body = repo.localSkillBody(skill.id).orEmpty(),
            onDismiss = { viewing = null },
            onDelete = {
                repo.deleteLocalSkill(skill.id)
                viewing = null
            },
        )
    }
}

@Composable
private fun SkillRow(skill: LocalSkill, enabled: Boolean, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                skill.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                skill.description.ifBlank { "（没有描述，模型不会知道它什么时候有用）" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun SkillEditorDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var id by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手写一条技能") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = id,
                    onValueChange = { id = it },
                    label = { Text("名称（目录名，模型调 use_skill 时用它）") },
                    placeholder = { Text("code-review") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("SKILL.md 内容") },
                    placeholder = {
                        Text(
                            "---\nname: code-review\ndescription: 按团队规范审阅改动\n---\n\n" +
                                "1. 先跑测试…\n2. 只看 diff 涉及的文件…"
                        )
                    },
                    minLines = 8,
                    maxLines = 16,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "description 一定要写：系统提示里就是靠这一句让模型判断「现在该不该用这个技能」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                localError?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (LocalSkillStore.validateId(id.trim()) == null) onSave(id.trim(), content)
                else localError = LocalSkillStore.validateId(id.trim())
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SkillViewerDialog(
    skill: LocalSkill,
    body: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(skill.displayName) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "目录名：${skill.id}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    body.ifBlank { "（正文为空）" },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (confirm) onDelete() else confirm = true }) {
                Text(if (confirm) "确认删除" else "删除")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** SAF 的 display name；拿不到就返回 null，由调用方兜底。 */
private fun displayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()
