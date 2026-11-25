package cn.cloudbuddy.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.cloudbuddy.app.data.EndpointResolver
import cn.cloudbuddy.app.data.LlmProvider

/**
 * 提供商管理：列表 + 新增/编辑 + 连通性测试 + 删除。
 *
 * 照 RikkaHub 的 Provider 详情页拆出来的最小实现：一个提供商一张卡，
 * 展开就是名称 / 地址 / Key 的表单；「测试连接」直接拉一次 /models，
 * 与模型拉取同通道，报错口径一致。
 *
 * Key 的处理与旧端点卡相同：只落本机 SharedPreferences，不上传、不写日志。
 */
@Composable
fun ProviderManager(
    providers: List<LlmProvider>,
    /** 正在测试的提供商 id；null 表示没有在测试。 */
    testingId: String?,
    /** 测试结果文本（ok/error 都走这里，前缀区分颜色）。 */
    testResult: Pair<String, String>?,
    onAdd: (LlmProvider) -> Unit,
    onUpdate: (LlmProvider) -> Unit,
    onRemove: (String) -> Unit,
    onTest: (LlmProvider) -> Unit,
) {
    var editing by remember { mutableStateOf<LlmProvider?>(null) }
    var removing by remember { mutableStateOf<LlmProvider?>(null) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "提供商",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { editing = LlmProvider(id = LlmProvider.newId(), name = "", baseUrl = "", apiKey = "") }) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("添加")
        }
    }

    if (providers.isEmpty()) {
        Text(
            "还没有提供商。一个提供商就是一个 OpenAI 兼容端点（地址 + Key），" +
                "可以同时配 DeepSeek 官方、中转站、自建 vLLM 等多个，每个下面挂各自的模型。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            providers.forEach { p ->
                val test = testResult?.takeIf { it.first == p.id }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(
                                modifier = Modifier.weight(1f).clickable { editing = p },
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        p.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (!p.enabled) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "已停用",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Text(
                                    "请求将发往：" + EndpointResolver.previewPath(p.baseUrl),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (testingId == p.id) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                            }
                            IconButton(onClick = { editing = p }, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = "编辑",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(onClick = { removing = p }, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "删除",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        test?.let { (id, text) ->
                            if (id == p.id) {
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (text.startsWith("连接成功")) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { target ->
        ProviderEditDialog(
            provider = target,
            isNew = providers.none { it.id == target.id },
            onDismiss = { editing = null },
            onSave = { saved ->
                if (providers.any { it.id == saved.id }) onUpdate(saved) else onAdd(saved)
                editing = null
            },
            onTest = {
                onTest(target)
            },
            testResult = testResult?.takeIf { it.first == target.id }?.second,
            testing = testingId == target.id,
        )
    }

    removing?.let { target ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("删除提供商「${target.label}」？") },
            text = { Text("它名下的模型会一起删除，使用这些模型的会话将退回云端模型。此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(target.id)
                    removing = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun ProviderEditDialog(
    provider: LlmProvider,
    isNew: Boolean,
    testResult: String?,
    testing: Boolean,
    onDismiss: () -> Unit,
    onSave: (LlmProvider) -> Unit,
    onTest: () -> Unit,
) {
    var name by remember(provider.id) { mutableStateOf(provider.name) }
    var baseUrl by remember(provider.id) { mutableStateOf(provider.baseUrl) }
    var apiKey by remember(provider.id) { mutableStateOf(provider.apiKey) }
    var enabled by remember(provider.id) { mutableStateOf(provider.enabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "添加提供商" else "编辑提供商") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    placeholder = { Text("DeepSeek 官方 / 某某中转") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("接口地址") },
                    placeholder = { Text("https://api.deepseek.com 或 https://xxx/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(
                            "填不填 /v1 都可以，客户端会按统一规则拼路径",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("启用", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "停用后它的模型不再出现在选择器里",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onTest, enabled = !testing && baseUrl.isNotBlank() && apiKey.isNotBlank()) {
                        Text(if (testing) "测试中…" else "测试连接")
                    }
                    testResult?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (it.startsWith("连接成功")) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(provider.copy(name = name.trim(), baseUrl = baseUrl.trim(), apiKey = apiKey.trim(), enabled = enabled)) },
                enabled = name.isNotBlank() && baseUrl.isNotBlank() && apiKey.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
