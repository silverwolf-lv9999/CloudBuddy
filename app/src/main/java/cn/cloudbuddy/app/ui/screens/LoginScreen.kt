package cn.cloudbuddy.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cn.cloudbuddy.app.data.BuddyRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Step { PHONE, CODE }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LoginScreen(
    repo: BuddyRepository,
    onLoggedIn: () -> Unit,
    /**
     * 「添加账号」进来的场景才给返回入口：此时本机还有别的已登录账号，
     * 放弃这次登录应当能回到那个账号，而不是被困在登录页。
     */
    onCancel: (() -> Unit)? = null
) {
    var step by remember { mutableStateOf(Step.PHONE) }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cooldown by remember { mutableStateOf(0) }
    var showServer by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf(repo.settings.baseUrl) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1_000)
            cooldown -= 1
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            onCancel?.let {
                Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = it, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Filled.ArrowBack, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("返回原账号")
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Surface(
                shape = MaterialTheme.shapes.extraLargeIncreased,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(96.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Cloud,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "云伴",
                style = MaterialTheme.typography.headlineMediumEmphasized
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "WorkBuddy 云端客户端",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(28.dp))

            Surface(
                shape = MaterialTheme.shapes.extraLargeIncreased,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (step == Step.PHONE) {
                        OutlinedTextField(
                            value = phone,
                            onValueChange = { input -> phone = input.filter { it.isDigit() }.take(11) },
                            label = { Text("手机号") },
                            leadingIcon = { Icon(Icons.Filled.PhoneAndroid, null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            shape = MaterialTheme.shapes.largeIncreased,
                            modifier = Modifier.fillMaxWidth()
                        )
                        error?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(
                            onClick = {
                                busy = true
                                error = null
                                scope.launch {
                                    runCatching { repo.sendCode(phone) }
                                        .onSuccess { seconds ->
                                            if (seconds == 0) onLoggedIn() else {
                                                cooldown = seconds
                                                step = Step.CODE
                                            }
                                        }
                                        .onFailure { error = it.message ?: "验证码发送失败" }
                                    busy = false
                                }
                            },
                            enabled = !busy && phone.length >= 11,
                            shape = MaterialTheme.shapes.largeIncreased,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("获取验证码", style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        Text(
                            "验证码已发送至 +86 $phone",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.filter { c -> c.isDigit() }.take(8) },
                            label = { Text("短信验证码") },
                            leadingIcon = { Icon(Icons.Filled.Lock, null) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            shape = MaterialTheme.shapes.largeIncreased,
                            modifier = Modifier.fillMaxWidth()
                        )
                        error?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(
                            onClick = {
                                busy = true
                                error = null
                                scope.launch {
                                    repo.submitCode(phone, code)
                                        .onSuccess { onLoggedIn() }
                                        .onFailure { error = it.message ?: "登录失败" }
                                    busy = false
                                }
                            },
                            enabled = !busy && code.length >= 4,
                            shape = MaterialTheme.shapes.largeIncreased,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text("登录", style = MaterialTheme.typography.labelLarge)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    if (cooldown > 0) return@TextButton
                                    busy = true
                                    scope.launch {
                                        runCatching { repo.sendCode(phone) }
                                            .onSuccess { cooldown = it }
                                            .onFailure { error = it.message }
                                        busy = false
                                    }
                                },
                                enabled = cooldown == 0 && !busy
                            ) { Text(if (cooldown > 0) "${cooldown}s 后重发" else "重新发送") }
                            TextButton(onClick = { step = Step.PHONE; error = null }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.ArrowBack, null, Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("换号")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                "登录凭据（网关 session 与 SSO cookie）只保存在本机，用于直接调用官方云端接口；" +
                    "本客户端不会把模型能力反向代理成本地 API。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            TextButton(
                onClick = { showServer = !showServer },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Dns, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("服务站点：${repo.settings.baseUrl}")
            }
            if (showServer) {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("站点地址") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.largeIncreased,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        repo.settings.baseUrl = server
                        showServer = false
                    },
                    shape = MaterialTheme.shapes.largeIncreased,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("保存站点") }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
