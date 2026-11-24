package cn.cloudbuddy.app.ui.screens

import android.graphics.BitmapFactory
import android.webkit.WebView
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import cn.cloudbuddy.app.data.BuddyRepository
import cn.cloudbuddy.app.data.Downloads
import cn.cloudbuddy.app.data.SandboxPath
import cn.cloudbuddy.app.ui.components.MarkdownText
import cn.cloudbuddy.app.ui.components.rememberDownloader

/**
 * 沙箱文件预览：图片、HTML、Markdown、纯文本就地渲染，二进制文件提供下载。
 * 内容来自云端文件网关，不落盘（下载除外）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilePreviewScreen(
    repo: BuddyRepository,
    conversationId: String,
    path: String,
    name: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val downloader = rememberDownloader()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var bytes by remember { mutableStateOf<ByteArray?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var lastSaved by remember { mutableStateOf<Pair<Downloads.Saved, String?>?>(null) }

    val mime = SandboxPath.mimeOf(name)

    LaunchedEffect(path) {
        loading = true
        repo.readWorkspaceFile(conversationId, path)
            .onSuccess { bytes = it; error = null }
            .onFailure { error = it.message ?: "读取失败" }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "返回") } },
                title = {
                    Column {
                        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            path,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    IconButton(
                        enabled = !saving,
                        onClick = {
                            val data = bytes ?: return@IconButton
                            saving = true
                            notice = "正在下载 $name…"
                            lastSaved = null
                            // 存到系统公共 Download 文件夹，文件管理器里直接能找到。
                            downloader.save(name, mime, data) { result ->
                                result.fold(
                                    onSuccess = {
                                        lastSaved = it to mime
                                        notice = "已保存到 ${it.displayPath}"
                                    },
                                    onFailure = {
                                        notice = "保存失败：${it.message ?: ""}"
                                        lastSaved = null
                                    }
                                )
                                saving = false
                            }
                        }
                    ) {
                        if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Download, "下载")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            notice?.let {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            it,
                            modifier = Modifier.weight(1f).padding(10.dp),
                            style = MaterialTheme.typography.labelSmall
                        )
                        val saved = lastSaved
                        TextButton(onClick = {
                            if (saved != null) Downloads.open(context, saved.first, saved.second)
                            notice = null
                            lastSaved = null
                        }) { Text(if (saved != null) "打开" else "知道了") }
                    }
                }
            }
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.4.dp)
                }

                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.InsertDriveFile, null, Modifier.size(30.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(error!!, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                bytes != null -> PreviewBody(mime, name, bytes!!)
            }
        }
    }
}

@Composable
private fun PreviewBody(mime: String, name: String, data: ByteArray) {
    when {
        mime.startsWith("image/") -> {
            val bitmap = remember(data) { runCatching { decodeSampled(data, 1600) }.getOrNull() }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(12.dp)
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("图片解码失败", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        mime == "text/html" -> AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    setBackgroundColor(0x00000000)
                }
            },
            update = { web ->
                web.loadDataWithBaseURL(null, String(data, Charsets.UTF_8), "text/html", "utf-8", null)
            },
            modifier = Modifier.fillMaxSize()
        )

        mime == "text/markdown" -> MarkdownText(
            markdown = String(data, Charsets.UTF_8),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            baseSize = 14.5f
        )

        mime.startsWith("text/") || mime == "application/json" || mime == "text/xml" -> {
            val text = remember(data) { String(data, Charsets.UTF_8) }
            SelectionContainer {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(14.dp)
                ) {
                    Text(
                        text = text,
                        style = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.5.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
        }

        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.InsertDriveFile, null, Modifier.size(34.dp))
                Text("该类型不支持预览", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${mime} · ${humanSize(data.size.toLong())}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "点右上角下载到本机后打开",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 沙箱里的图可能很大，按目标宽度采样，避免直接 OOM。 */
private fun decodeSampled(data: ByteArray, maxWidth: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
    var sample = 1
    while (bounds.outWidth > 0 && bounds.outWidth / sample > maxWidth) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(data, 0, data.size, options)
}
