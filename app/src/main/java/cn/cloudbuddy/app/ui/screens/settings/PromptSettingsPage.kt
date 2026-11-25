package cn.cloudbuddy.app.ui.screens.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.cloudbuddy.app.data.AppUiState
import cn.cloudbuddy.app.data.BuddyRepository
import cn.cloudbuddy.app.data.PromptPosition
import cn.cloudbuddy.app.data.PromptPreset

/**
 * 提示词片段管理。
 *
 * 结构参考 rikkahub 的 PromptInjection：一条片段有名字、内容、注入位置、开关和顺序，
 * 拼系统提示词时按位置插进去。区别是只保留「手动开关」这一种触发方式——
 * 它的另一类是正则匹配触发（Lorebook / 世界书），那是角色扮演的机制；
 * 编程场景里「提交前跑测试」「别动数据库」这类内容应该是常驻的，
 * 靠关键词猜该不该注入，猜错的代价比多注入一段大得多。
 *
 * 全局系统提示词仍然在「本机模型」页：那是这次任务的总体要求，片段是可复用的补充。
 *
 * 注意作用域：片段拼进的是**本机会话**的系统提示词（`AppSettings.customModel()`
 * 装配时注入）。云端会话的提示词由云端 agent 决定，客户端改不了，所以这一页
 * 归在设置首页的「本机模型」栏下面。
 */
@Composable
internal fun PromptSettingsPage(ui: AppUiState, repo: BuddyRepository, onBack: () -> Unit) {
    var editing by remember { mutableStateOf<PromptPreset?>(null) }

    SettingsPageScaffold("提示词片段", onBack) { padding ->
        SettingsList(padding) {
            title("片段", Icons.Filled.Tune)

            item(key = "intro", contentType = "prompt-intro") {
                SettingCard {
                    Text(
                        "这里放可复用的补充要求，比如编码规范、项目约定、回答风格。" +
                            "本机会话每次对话都会按下面选的位置拼进系统提示词，对你所有自定义模型生效；" +
                            "云端会话的提示词由云端 agent 决定，这里的片段不影响它。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                    )
                }
            }

            items(ui.promptPresets, key = { it.id }, contentType = { "prompt" }) { preset ->
                SettingCard {
                    PromptRow(
                        preset = preset,
                        onClick = { editing = preset },
                        onToggle = { repo.setPromptEnabled(preset.id, it) }
                    )
                }
            }

            if (ui.promptPresets.isEmpty()) {
                item(key = "empty", contentType = "prompt-intro") {
                    SettingCard {
                        Text(
                            "还没有片段。例如「改完代码先跑一遍相关测试再回答」——" +
                                "写一次，之后每条对话都带着它。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                        )
                    }
                }
            }

            item(key = "add", contentType = "prompt") {
                SettingCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editing = PromptPreset.blank() }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("添加片段", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    editing?.let { preset ->
        PromptEditorDialog(
            initial = preset,
            // 只有已经存在的片段才能挪位置和删除。
            saved = ui.promptPresets.any { it.id == preset.id },
            onDismiss = { editing = null },
            onSave = { repo.savePrompt(it); editing = null },
            onMove = { up -> repo.movePrompt(preset.id, up) },
            onDelete = { repo.removePrompt(preset.id); editing = null },
        )
    }
}

@Composable
private fun PromptRow(preset: PromptPreset, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    preset.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(8.dp))
                PositionTag(preset.placed)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                preset.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = preset.enabled, onCheckedChange = onToggle)
    }
}

/** 位置标签：一眼看出这条会落在系统提示词的哪一侧。 */
@Composable
private fun PositionTag(position: PromptPosition) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            position.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/**
 * 编辑一条片段。
 *
 * 位置用三个横排的选项而不是下拉菜单：只有三档，全部摆出来比让用户点开一次
 * 下拉再看三行更省事，也能顺带看到每档的用途。
 */
@Composable
private fun PromptEditorDialog(
    initial: PromptPreset,
    saved: Boolean,
    onDismiss: () -> Unit,
    onSave: (PromptPreset) -> Unit,
    onMove: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var content by remember(initial.id) { mutableStateOf(initial.content) }
    var position by remember(initial.id) { mutableStateOf(initial.placed) }
    var confirmDelete by remember(initial.id) { mutableStateOf(false) }

    val canSave = content.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (saved) "编辑片段" else "新片段") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("插入位置", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PromptPosition.entries.forEach { option ->
                            val active = position == option
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.clickable { position = option }
                            ) {
                                Text(
                                    option.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (active) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    Text(
                        position.hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("内容") },
                    minLines = 5,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
                )

                if (saved) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { onMove(true) }) { Text("上移") }
                        TextButton(onClick = { onMove(false) }) { Text("下移") }
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim().ifBlank { "未命名片段" },
                            content = content.trim(),
                            position = position.key,
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这条片段？") },
            text = { Text("删除后每次对话都不再带上它，已发送的消息不受影响。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}
