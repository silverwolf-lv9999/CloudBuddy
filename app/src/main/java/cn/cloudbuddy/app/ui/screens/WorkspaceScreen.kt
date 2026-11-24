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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
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

@OptIn(ExperimentalMaterial3Api::class)
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
                        Text("沙箱工作区", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (conversationId == null) "未打开会话" else breadcrumb(workspace.path),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        conversationId?.let { repo.scopeLoad(it, workspace.path) }
                    }) { Icon(Icons.Filled.Refresh, "刷新") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            if (conversationId != null) {
                ExtendedFloatingActionButton(
                    onClick = { uploader.launch(arrayOf("*/*")) },
                    icon = { Icon(Icons.Filled.Upload, null) },
                    text = { Text("上传") }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (conversationId == null) {
                EmptyWorkspace("还没有打开会话", "云端沙箱是跟着会话走的：先进入一个会话，再回来浏览它的 /workspace。", onOpenSession)
                return@Column
            }

            PathBar(
                path = workspace.path,
                onNavigate = { path -> repo.scopeLoad(conversationId, path) },
                onNewFolder = { newFolder = true }
            )

            notice?.let { text ->
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text,
                                modifier = Modifier.weight(1f).padding(10.dp),
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
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)
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
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.4.dp)
                    Spacer(Modifier.height(10.dp))
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
                        Modifier.size(38.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("无法读取云端工作区", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        workspace.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (workspace.diagnostics.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                workspace.diagnostics,
                                modifier = Modifier.padding(10.dp).heightIn(max = 180.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { repo.scopeLoad(conversationId, workspace.path) }) {
                            Text("重试")
                        }
                        TextButton(onClick = {
                            repo.reloadSessionAndWorkspace(conversationId, workspace.path)
                        }) { Text("重新获取会话") }
                    }
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.FolderOpen,
                            null,
                            Modifier.size(34.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "这个目录还是空的",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(workspace.entries, key = { it.path }) { entry ->
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

@Composable
private fun PathBar(path: String, onNavigate: (String) -> Unit, onNewFolder: () -> Unit) {
    val segments = path.trim('/').split('/').filter { it.isNotBlank() }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.clickable {
                if (path != "/workspace") onNavigate("/workspace")
            }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Folder, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(5.dp))
                Text("/${segments.firstOrNull() ?: "workspace"}", style = MaterialTheme.typography.labelSmall)
            }
        }
        if (segments.size > 1) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.clickable { onNavigate(SandboxPath.parentOf(path)) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.ArrowUpward, null, Modifier.size(13.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("上一级", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onNewFolder, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.CreateNewFolder, "新建文件夹", Modifier.size(18.dp))
        }
    }
}

@Composable
private fun EntryRow(
    entry: SandboxEntry,
    saving: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (entry.isDir) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
                tint = if (entry.isDir) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!entry.isDir) {
                        Text(
                            humanSize(entry.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (entry.modifiedAt > 0) {
                        Text(
                            relativeTime(entry.modifiedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (saving) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.8.dp)
                Spacer(Modifier.width(6.dp))
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.MoreVert, "操作", Modifier.size(17.dp))
                }
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

@Composable
private fun EmptyWorkspace(title: String, subtitle: String, onOpenSession: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
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
