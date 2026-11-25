package cn.cloudbuddy.app.data

/**
 * 把附件信息拼成给本机模型的提示。
 *
 * 自定义引擎不经云端，附件没有 `resource_link` 那种结构化块可用，
 * 只能落进沙箱再把路径写进文本。这里刻意做成纯函数：
 * 截断长度、文本判定、多文件拼接都很容易写错，而它们又不依赖网络。
 */
internal object AttachmentPrompt {

    /** 已写入沙箱的附件。 */
    data class Staged(
        val name: String,
        val path: String,
        val size: Int,
        val preview: String?,
    )

    /** 超过这个体积不做预览：读了也只是噪音，直接给路径让模型自己挑。 */
    const val MAX_PREVIEW_BYTES = 512 * 1024

    /** 单文件预览的字符上限，多文件叠加也不会把 prompt 撑爆。 */
    const val MAX_PREVIEW_CHARS = 1_500

    /**
     * 认作文本、值得给预览的扩展名。
     *
     * 需要这一步是因为很多来源给的 mime 是 `application/octet-stream`，
     * 光看 mime 会把 .md / .py 这类纯文本误判成二进制。
     */
    val TEXT_EXT = setOf(
        "md", "txt", "kt", "java", "py", "js", "ts", "tsx", "jsx", "json",
        "xml", "yaml", "yml", "toml", "ini", "csv", "tsv", "log", "sh",
        "html", "css", "sql", "gradle", "kts", "rs", "go", "c", "h", "cpp",
    )

    /**
     * 文本类附件的前若干字符。
     *
     * 有预览，模型不必先 `read_file` 就能判断"这是不是我需要的文件"，
     * 小文件甚至不用再读一次。二进制与图片返回 null。
     */
    fun previewOf(name: String, mimeType: String, bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > MAX_PREVIEW_BYTES) return null
        if (!isText(name, mimeType)) return null
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
        return text.take(MAX_PREVIEW_CHARS)
    }

    fun isText(name: String, mimeType: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return mimeType.startsWith("text/") ||
            mimeType.contains("json") || mimeType.contains("xml") ||
            mimeType.contains("yaml") || mimeType.contains("csv") ||
            ext in TEXT_EXT
    }

    /** 附件的说明块，拼在用户这句话后面。 */
    fun hint(staged: List<Staged>): String {
        if (staged.isEmpty()) return ""
        return buildString {
            append("\n\n<attachments>\n")
            append("用户上传了以下文件，已放在沙箱本地，请直接用 read_file / run_shell 处理，")
            append("不要让用户重新粘贴内容：\n")
            for (s in staged) {
                append("- ").append(s.name).append("（").append(s.size).append(" 字节）→ ")
                append(s.path).append("\n")
                s.preview?.takeIf { it.isNotBlank() }?.let { preview ->
                    append("  预览（前 ").append(preview.length).append(" 字符）：\n")
                    preview.lines().forEach { line -> append("  | ").append(line).append("\n") }
                }
            }
            append("</attachments>")
        }
    }
}
