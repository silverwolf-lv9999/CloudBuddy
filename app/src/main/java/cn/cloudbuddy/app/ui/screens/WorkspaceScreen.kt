package cn.cloudbuddy.app.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cn.cloudbuddy.app.data.AppUiState
import cn.cloudbuddy.app.data.BuddyRepository
import cn.cloudbuddy.app.data.Downloads
import cn.cloudbuddy.app.data.SandboxEntry
import cn.cloudbuddy.app.data.SandboxPath
import cn.cloudbuddy.app.data.WorkspaceState
import cn.cloudbuddy.app.ui.components.rememberDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WorkspaceScreen(
    ui: AppUiState,
    workspace: WorkspaceState,
    repo: BuddyRepository,
    onPreview: (conversationId: String, path: String, name: String) -> Unit,
    onOpenSession: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloader = rememberDownloader()
    var deleteTarget by remember { mutableStateOf<SandboxEntry?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<String?>(null) }
    // 下载进度：-1 表示服务端没给长度（不确定进度），0..1 为真实字节进度。
    var progress by remember { mutableStateOf(-1f) }
    var notice by remember { mutableStateOf<String?>(null) }
    // 下载完成后的提示要带「打开」动作，光靠 notice 字符串不够。
    var lastSaved by remember { mutableStateOf<Pair<Downloads.Saved, String?>?>(null) }
    // 本次会话里已下载过的云端文件：再点它直接打开，不重复下载。
    var downloaded by remember { mutableStateOf(mapOf<String, Downloads.Saved>()) }
    // 正在进行的下载：气泡里的「取消」要把读文件的过程真正停掉。
    var downloadJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val cancelFlag = remember { java.util.concurrent.atomic.AtomicBoolean(false) }

    // 从对话页返回列表后仍能浏览「最近一次」会话的工作区。
    val conversationId = ui.activeId ?: repo.settings.lastConversation.takeIf { it.isNotBlank() }

    /**
     * 下载一个云端文件到本机：读取阶段显示真实字节进度，完成后提示可「打开」。
     *
     * [openWhenDone] 用于点击了本应用无法预览的文件：下载完直接交给系统应用打开，
     * 不再让用户先进预览页看一句「该类型不支持预览」。
     */
    fun downloadFile(entry: SandboxEntry, openWhenDone: Boolean) {
        val target = conversationId ?: return
        saving = entry.path
        progress = -1f
        notice = "正在下载 ${entry.name}…"
        lastSaved = null
        cancelFlag.set(false)
        downloadJob = scope.launch {
            repo.readWorkspaceFile(
                target,
                entry.path,
                onProgress = { progress = it },
                isCancelled = { cancelFlag.get() }
            )
                .onSuccess { bytes ->
                    // 存到系统公共 Download 文件夹，文件管理器里直接能找到。
                    downloader.save(entry.name, entry.mimeType, bytes) { result ->
                        result.fold(
                            onSuccess = { saved ->
                                val mime = entry.mimeType ?: SandboxPath.mimeOf(entry.name)
                                downloaded = downloaded + (entry.path to saved)
                                lastSaved = saved to mime
                                notice = "已保存到 ${saved.displayPath}"
                                if (openWhenDone) Downloads.open(context, saved, mime)
                            },
                            onFailure = {
                                notice = "保存失败：" + (it.message ?: "")
                                lastSaved = null
                            }
                        )
                        saving = null
                    }
                }
                .onFailure {
                    if (cancelFlag.get() || it.message?.contains("已取消") == true) {
                        notice = "已取消下载"
                    } else {
                        notice = "下载失败：" + (it.message ?: "")
                    }
                    lastSaved = null
                    saving = null
                }
        }
    }

    LaunchedEffect(conversationId) {
        if (conversationId != null) repo.loadWorkspace(conversationId, "/workspace")
    }

    val uploader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null || conversationId == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
                    } ?: "upload.bin"
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw IllegalStateException("无法读取该文件")
                    name to bytes
                }
            }
            result.onSuccess { (name, bytes) ->
                repo.uploadToWorkspace(conversationId, workspace.path, name, bytes)
                    .onSuccess {
                        notice = "已上传到 ${workspace.path}"
                        repo.loadWorkspace(conversationId, workspace.path)
                    }
                    .onFailure { notice = "上传失败：" + (it.message ?: "") }
            }.onFailure { notice = it.message }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "工作区",
                            style = MaterialTheme.typography.titleLargeEmphasized
                        )
                        Text(
                            if (conversationId == null) "未打开会话" else breadcrumb(workspace.path),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = conversationId != null && workspace.path != "/workspace") {
                                repo.scopeLoad(conversationId!!, SandboxPath.parentOf(workspace.path))
                            }
                        )
                    }
                },
                actions = {
                    if (conversationId != null) {
                        IconButton(onClick = { newFolder = true }) {
                            Icon(Icons.Filled.CreateNewFolder, "新建文件夹")
                        }
                        IconButton(onClick = {
                            repo.scopeLoad(conversationId, workspace.path)
                        }) { Icon(Icons.Filled.Refresh, "刷新") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        floatingActionButton = {
            if (conversationId != null) {
                ExtendedFloatingActionButton(
                    onClick = { uploader.launch(arrayOf("*/*")) },
                    icon = { Icon(Icons.Filled.Upload, null) },
                    text = { Text("上传") },
                    shape = MaterialTheme.shapes.largeIncreased
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (conversationId == null) {
                EmptyWorkspace(
                    title = "工作区为空",
                    subtitle = "云端沙箱是跟着会话走的：先进入一个会话，再回来浏览它的 /workspace。",
                    onOpenSession = onOpenSession
                )
                return@Column
            }

            notice?.let { text ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.largeIncreased,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text,
                                modifier = Modifier.weight(1f).padding(12.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                            val saved = lastSaved
                            TextButton(onClick = {
                                when {
                                    // 正在下载：按钮是「取消」，立刻中止读取。
                                    saving != null -> {
                                        cancelFlag.set(true)
                                    }
                                    saved != null -> {
                                        Downloads.open(context, saved.first, saved.second)
                                        notice = null
                                        lastSaved = null
                                    }
                                    else -> notice = null
                                }
                            }) {
                                Text(
                                    when {
                                        saving != null -> "取消"
                                        saved != null -> "打开"
                                        else -> "知道了"
                                    }
                                )
                            }
                        }
                        if (saving != null) {
                            LinearProgressIndicator(
                                progress = { if (progress >= 0f) progress else 0f },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            when {
                workspace.loading -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.6.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "正在读取沙箱目录…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                workspace.error != null -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Filled.CloudOff,
                        null,
                        Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("无法读取云端工作区", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        workspace.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (workspace.diagnostics.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = MaterialTheme.shapes.largeIncreased
                        ) {
                            Text(
                                workspace.diagnostics,
                                modifier = Modifier.padding(12.dp).heightIn(max = 180.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { repo.scopeLoad(conversationId, workspace.path) }) {
                            Text("重试")
                        }
                        TextButton(onClick = {
                            repo.reloadSessionAndWorkspace(conversationId, workspace.path)
                        }) { Text("重新获取会话") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "若沙箱已过期，官方云端会在重新进入会话时重建；重新获取会话可拿到新的文件网关地址。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                workspace.entries.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(72.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.FolderOpen,
                                    null,
                                    Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Text("工作区为空", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "这个目录还没有任何文件；点右下角「上传」把文件传进来。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(workspace.entries, key = { it.path }, contentType = { entry ->
                        if (entry.isDir) "dir" else "file"
                    }) { entry ->
                        EntryRow(
                            entry = entry,
                            saving = saving == entry.path,
                            onClick = {
                                if (entry.isDir) {
                                    repo.scopeLoad(conversationId, entry.path)
                                } else if (SandboxPath.canPreviewInApp(entry.name)) {
                                    // 图片 / 文本 / HTML / Markdown / JSON 才进应用内预览。
                                    onPreview(conversationId, entry.path, entry.name)
                                } else {
                                    // 打开优先级：本次会话下载过的 → 本机 Download 里已有同名文件
                                    // （重启后依然命中）→ 都没有才真正下载。
                                    val cached = downloaded[entry.path]
                                        ?: Downloads.findExisting(entry.name)
                                    if (cached != null) {
                                        if (downloaded[entry.path] == null) {
                                            downloaded = downloaded + (entry.path to cached)
                                        }
                                        Downloads.open(
                                            context,
                                            cached,
                                            entry.mimeType ?: SandboxPath.mimeOf(entry.name)
                                        )
                                    } else {
                                        downloadFile(entry, openWhenDone = true)
                                    }
                                }
                            },
                            onDownload = { downloadFile(entry, openWhenDone = false) },
                            onDelete = { deleteTarget = entry }
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            shape = MaterialTheme.shapes.extraLargeIncreased,
            title = { Text("删除云端文件") },
            text = { Text("将从沙箱中删除「${entry.path}」，操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    scope.launch {
                        repo.deleteWorkspaceEntry(conversationId!!, entry.path)
                            .onSuccess {
                                notice = "已删除 ${entry.name}"
                                repo.loadWorkspace(conversationId, workspace.path)
                            }
                            .onFailure { notice = "删除失败：" + (it.message ?: "") }
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }

    if (newFolder) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newFolder = false },
            shape = MaterialTheme.shapes.extraLargeIncreased,
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("文件夹名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = conversationId
                    if (target != null && name.isNotBlank()) {
                        scope.launch {
                            repo.createWorkspaceFolder(target, workspace.path, name.trim())
                                .onSuccess {
                                    notice = "已创建 ${name.trim()}"
                                    repo.loadWorkspace(target, workspace.path)
                                }
                                .onFailure { notice = "新建失败：" + (it.message ?: "") }
                        }
                    }
                    newFolder = false
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { newFolder = false }) { Text("取消") } }
        )
    }
}

/** 文件列表行：大圆角（largeIncreased）+ surfaceContainerLow，整行可点。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EntryRow(
    entry: SandboxEntry,
    saving: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    val mime = entry.mimeType ?: SandboxPath.mimeOf(entry.name)
    val icon = when {
        entry.isDir -> Icons.Filled.Folder
        mime.startsWith("image/") -> Icons.Filled.Image
        mime.startsWith("text/") || mime == "application/json" -> Icons.Filled.Description
        else -> Icons.Filled.InsertDriveFile
    }
    val iconTint = when {
        entry.isDir -> MaterialTheme.colorScheme.primary
        mime.startsWith("image/") -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = MaterialTheme.shapes.largeIncreased,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = iconTint
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!entry.isDir) {
                        Text(
                            humanSize(entry.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (entry.modifiedAt > 0) {
                        Text(
                            relativeTime(entry.modifiedAt),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (saving) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.MoreVert, "操作", Modifier.size(18.dp))
                }
                // 只在展开时才组合菜单内容：每行常驻一个 DropdownMenu 会让长列表滚动变沉。
                if (menu) {
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (!entry.isDir) {
                            DropdownMenuItem(
                                text = { Text("下载到本机") },
                                leadingIcon = { Icon(Icons.Filled.Download, null, Modifier.size(18.dp)) },
                                onClick = { menu = false; onDownload() }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("删除") },
                            leadingIcon = { Icon(Icons.Filled.Delete, null, Modifier.size(18.dp)) },
                            onClick = { menu = false; onDelete() }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyWorkspace(title: String, subtitle: String, onOpenSession: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(72.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.FolderOpen,
                        null,
                        Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onOpenSession) {
                Icon(Icons.Filled.Add, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("去选择会话")
            }
        }
    }
}

fun breadcrumb(path: String): String = if (path.length <= 34) path else "…" + path.takeLast(33)

fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
