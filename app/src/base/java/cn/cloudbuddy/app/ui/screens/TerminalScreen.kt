package cn.cloudbuddy.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.cloudbuddy.app.data.TerminalState

/**
 * 基础版的终端占位。
 *
 * 完整界面（标签、键盘栏、ANSI 着色、复制）在 `src/full` 里，基础版的 dex 中不会有那些类——
 * 用户要的是「这个包不含终端命令行」，不是「藏起入口但把实现一起装进去」。
 *
 * 入口在聊天页菜单与工作区标题栏都已隐藏，这个函数正常走不到；留着是为了让
 * MainActivity 这层壳在两个变体之间不用写 `if`，也防止旧状态把用户送进一个空白页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    state: TerminalState,
    title: String,
    available: Boolean,
    fontSize: Int,
    onFontSize: (Int) -> Unit,
    onRun: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
    onNewTab: () -> Unit,
    onSelectTab: (Long) -> Unit,
    onCloseTab: (Long) -> Unit,
    onRenameTab: (Long, String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("命令行窗口") }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("这个版本不含命令行窗口", style = MaterialTheme.typography.titleSmall)
            Text(
                "基础版只保留云端对话与工作区。需要终端与第三方 API 时，装上同版本的完整版即可覆盖升级。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
