package cn.cloudbuddy.app.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException

/**
 * 把从云端沙箱取回的文件存到**系统公共的 Download 文件夹**。
 *
 * 之前的实现写在 `filesDir/downloads`（应用私有目录），用户在任何文件管理器里都找不到，
 * 而且卸载应用就一起没了。这里改成：
 * - Android 10（API 29）及以上：走 MediaStore.Downloads，`RELATIVE_PATH = Download`，
 *   不需要任何存储权限，文件管理器立刻可见；
 * - Android 8/9（API 26–28）：直接写 `Environment.getExternalStoragePublicDirectory(DOWNLOAD)`，
 *   需要 `WRITE_EXTERNAL_STORAGE`，写完后通知媒体库扫描，否则部分文件管理器看不到。
 */
object Downloads {

    /** 保存结果。[displayPath] 形如 `Download/report.pdf`，可直接出示给用户。 */
    data class Saved(val displayPath: String, val fileName: String, val uri: Uri?)

    /** API 26–28 需要运行时存储权限；29 起走 MediaStore 不需要。 */
    fun needsLegacyPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED

    fun permissionName(): String = android.Manifest.permission.WRITE_EXTERNAL_STORAGE

    /**
     * 用系统应用打开已下载的文件。
     *
     * Android 7+ 直接把 `file://` 塞进 ACTION_VIEW 会抛 FileUriExposedException，
     * 所以统一走 FileProvider；APK 这类本应用打不开的文件也由此交给系统安装器/文件管理器。
     */
    fun open(context: Context, saved: Saved, mime: String?): Boolean {
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            saved.fileName
        )
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (_: Throwable) {
            saved.uri
        } ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            Toast.makeText(context, "没有可以打开该文件的应用", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun save(context: Context, name: String, mime: String?, bytes: ByteArray): Saved {
        val safe = safeName(name)
        val type = mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, safe, type, bytes)
        } else {
            saveToPublicDir(context, safe, type, bytes)
        }
    }

    /**
     * 公共 Download 目录里是否已经有同名文件。
     *
     * 点击已下载过的文件时直接打开它，不必重新下载——这个判定落在本机文件系统上，
     * App 重启后依然生效（会话内存里的「已下载记录」只覆盖当次）。
     * 空文件（0 字节，多半是上次写坏了）不算，照常重新下载。
     */
    fun findExisting(name: String): Saved? {
        val safe = safeName(name)
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            safe
        )
        if (!file.exists() || file.length() <= 0L) return null
        return Saved("${Environment.DIRECTORY_DOWNLOADS}/$safe", safe, null)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(context: Context, name: String, mime: String, bytes: ByteArray): Saved {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            // 直接落在 Download 根目录，不再套一层应用子目录。
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("系统 Download 目录不可写")
        try {
            val stream = resolver.openOutputStream(uri)
                ?: throw IOException("无法写入系统 Download 目录")
            stream.use { it.write(bytes) }
        } catch (failure: Throwable) {
            // 写失败要删掉这条 pending 记录，否则文件管理器里会留下一个空条目。
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        // 重名时 MediaStore 会自动改名（name (1).ext），回读一次拿到真实文件名。
        val actual = runCatching {
            resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull().orEmpty().ifBlank { name }
        return Saved("${Environment.DIRECTORY_DOWNLOADS}/$actual", actual, uri)
    }

    private fun saveToPublicDir(context: Context, name: String, mime: String, bytes: ByteArray): Saved {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) {
            throw IOException("外部存储不可用")
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建 Download 目录")
        val file = uniqueFile(dir, name)
        file.writeBytes(bytes)
        // 不扫描的话，部分文件管理器和相册要重启才能看到新文件。
        runCatching {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mime), null)
        }
        return Saved("${Environment.DIRECTORY_DOWNLOADS}/${file.name}", file.name, Uri.fromFile(file))
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, if (ext.isBlank()) "$base ($index)" else "$base ($index).$ext")
            index++
        }
        return candidate
    }

    /**
     * 去掉路径分隔符与控制字符，避免用户或云端给的名字把文件写到别处。
     *
     * 只取最后一段（`../../etc/passwd` → `passwd`），并去掉开头的点，
     * 防止在 Download 根目录放出 `.nomedia` 这类隐藏/特殊文件。
     */
    internal fun safeName(raw: String): String {
        val cleaned = raw
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .filter { !it.isISOControl() }
            .trim()
            .trimStart('.')
        return cleaned.ifBlank { "cloudbuddy-${System.currentTimeMillis()}" }.take(120)
    }
}
