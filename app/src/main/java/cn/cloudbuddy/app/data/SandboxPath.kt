package cn.cloudbuddy.app.data

import java.io.ByteArrayOutputStream

/**
 * 沙箱产物 URI 与路径互转。
 *
 * 云端会同时给出 `agent://files/...`、`file:///workspace/...`、`file:///root/...`
 * 三种写法，且路径段可能被多次百分号编码。这里统一收敛成 `/workspace/...`
 * 或 `/root/...`，并拒绝任何越权路径。
 */
object SandboxPath {

    private val TAIL = charArrayOf(' ', '.', ',', ';', ':', '!', '?', '。', '，', '；', '：', '！', '？', '、')

    fun of(uri: String): String? {
        val raw = when {
            uri.startsWith("file:///workspace/") || uri.startsWith("file:///root/") -> uri.removePrefix("file://")
            uri.startsWith("/workspace/") || uri.startsWith("/root/") -> uri
            uri.startsWith("agent://files/") -> "/" + uri.removePrefix("agent://files/").trimStart('/')
            uri.startsWith("agent:///") -> uri.removePrefix("agent://")
            uri.startsWith("agent://") -> "/" + uri.removePrefix("agent://")
            else -> return null
        }
        val decoded = raw.split('/').joinToString("/") { decodeSegment(it) }
        val once = decoded.split('/').joinToString("/") { decodeSegment(it) }
        val safe = listOf(raw, decoded, once).all { path ->
            (path.startsWith("/workspace/") || path.startsWith("/root/")) &&
                !path.contains('\\') && !path.contains('\u0000') &&
                path.split('/').none { it == "." || it == ".." }
        }
        if (!safe) return null
        return "/" + decoded.split('/').filter { it.isNotEmpty() }.joinToString("/")
    }

    /** COS 对象键的几种写法（原样 / 解码 / 重新编码），下载时逐个尝试。 */
    fun objectKeyVariants(uri: String): List<String> {
        val raw = trimTail(
            when {
                uri.startsWith("agent://files/") -> uri.removePrefix("agent://files/")
                uri.startsWith("agent:///") -> uri.removePrefix("agent:///")
                uri.startsWith("agent://") -> uri.removePrefix("agent://")
                else -> uri
            }.trimStart('/').substringBefore('#').substringBefore('?')
        )
        if (raw.isBlank() || raw.startsWith("http://") || raw.startsWith("https://")) return emptyList()
        val decoded = raw.split('/').joinToString("/") { decodeSegment(it) }
        val encoded = decoded.split('/').joinToString("/") { encodeSegment(it) }
        return listOf(raw, decoded, encoded).filter { it.isNotBlank() }.distinct()
    }

    fun trimTail(value: String): String = value.trimEnd(*TAIL)

    fun decodeSegment(segment: String): String {
        if ('%' !in segment) return segment
        val out = StringBuilder(segment.length)
        val pending = ByteArrayOutputStream(segment.length)
        fun flush() {
            if (pending.size() > 0) {
                out.append(String(pending.toByteArray(), Charsets.UTF_8))
                pending.reset()
            }
        }
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            if (c == '%' && i + 2 < segment.length) {
                val code = segment.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) {
                    pending.write(code)
                    i += 3
                    continue
                }
            }
            flush()
            val codePoint = segment.codePointAt(i)
            out.appendCodePoint(codePoint)
            i += Character.charCount(codePoint)
        }
        flush()
        return out.toString()
    }

    /** 等价于 JS 的 encodeURIComponent。 */
    fun encodeSegment(segment: String): String {
        val out = StringBuilder()
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (value < 0x80 && (char.isLetterOrDigit() || char in "-_.!~*'()")) out.append(char)
            else out.append('%').append(HEX[value shr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    /** 逐段编码整条路径，保留 `/`。 */
    fun encodePath(path: String): String = path.split('/').joinToString("/") { encodeSegment(it) }

    fun parentOf(path: String): String {
        val trimmed = path.trimEnd('/')
        val index = trimmed.lastIndexOf('/')
        return if (index <= 0) "/" else trimmed.substring(0, index)
    }

    fun join(dir: String, name: String): String = dir.trimEnd('/') + "/" + name

    fun fileName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifBlank { path }

    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "pdf" -> "application/pdf"
            "html", "htm" -> "text/html"
            "json" -> "application/json"
            "md", "markdown" -> "text/markdown"
            "csv" -> "text/csv"
            "xml" -> "text/xml"
            "txt", "log", "ini", "conf", "yaml", "yml", "toml" -> "text/plain"
            "js", "mjs", "ts", "tsx", "jsx", "kt", "java", "py", "go", "rs", "c", "h", "cpp",
            "cs", "rb", "php", "sh", "sql", "css", "scss" -> "text/plain"
            "zip" -> "application/zip"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "application/octet-stream"
        }
    }

    private const val HEX = "0123456789ABCDEF"
}
