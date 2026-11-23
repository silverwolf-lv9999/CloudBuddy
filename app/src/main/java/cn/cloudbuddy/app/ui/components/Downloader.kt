package cn.cloudbuddy.app.ui.components

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import cn.cloudbuddy.app.data.Downloads
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 下载到系统 Download 文件夹的统一入口。
 *
 * Android 10 起走 MediaStore，无需权限；8/9 需要 `WRITE_EXTERNAL_STORAGE`，
 * 这里会在真正写入前弹一次授权，避免在界面层重复写这套流程。
 */
class Downloader internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val requestPermission: (onGranted: () -> Unit, onDenied: () -> Unit) -> Unit
) {

    fun save(
        name: String,
        mime: String?,
        bytes: ByteArray,
        onResult: (Result<Downloads.Saved>) -> Unit
    ) {
        val write: () -> Unit = {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching { Downloads.save(context, name, mime, bytes) }
                }
                onResult(result)
            }
            Unit
        }
        if (Downloads.needsLegacyPermission(context)) {
            requestPermission(write) {
                onResult(Result.failure(IOException("没有存储权限，无法写入 Download 文件夹")))
            }
        } else {
            write()
        }
    }
}

@Composable
fun rememberDownloader(): Downloader {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val callback = pending
        pending = null
        callback?.invoke(granted)
    }
    val request: (onGranted: () -> Unit, onDenied: () -> Unit) -> Unit = { granted, denied ->
        pending = { ok -> if (ok) granted() else denied() }
        launcher.launch(Downloads.permissionName())
    }
    return remember(context, scope) {
        Downloader(context.applicationContext, scope, request)
    }
}
