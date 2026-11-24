package cn.cloudbuddy.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 云端沙箱文件网关（E2B envd）。
 *
 * 协议以官方 Web 客户端 `main-*.js` 中的 `CloudFileManager` / `CollabCloudE2BFilesystem`
 * 为准，不再使用猜测的 REST 路径：
 *
 * ```
 * 列目录  POST {endpoint}/filesystem.Filesystem/ListDir  {"path":"/workspace","depth":1}
 * 建目录  POST {endpoint}/filesystem.Filesystem/MakeDir  {"path":"/workspace/x"}
 * 删除    POST {endpoint}/filesystem.Filesystem/Remove   {"path":"/workspace/x"}
 * 读文件  GET  {endpoint}/files?path=...&username=user   ( /root/ 开头用 root )
 * 写文件  POST {endpoint}/files?path=...&username=user   multipart 字段 file
 * ```
 *
 * Connect RPC 统一带 `Connect-Protocol-Version: 1` 与 `X-Access-Token`；
 * `/files` 的 `username` 由路径推导，缺省会导致网关 404。
 *
 * 端点来自会话的 `e2bDataEndpoint` / `sandboxDataLink`，没有下发时按官方 `w2r()`
 * 规则由 `link` 与 `sandboxId` 推导 `https://49983-{sandboxId}.{domain}`。
 */
class SandboxFs(private val http: CloudHttp, private val settings: AppSettings) {

    /** 列目录。主路径 Connect RPC；兼容旧网关时再试少量候选，并把诊断信息带进异常。 */
    suspend fun list(endpoint: String, token: String, path: String): List<SandboxEntry> {
        return withContext(Dispatchers.IO) {
            val dir = normalizeDir(path)
            val attempts = mutableListOf<String>()
            val codes = mutableListOf<Int>()
            val strategies = buildList {
                if (cachedStyle != -1) add(cachedStyle)
                addAll(0 until STRATEGIES)
            }.distinct()

            for (index in strategies) {
                val outcome = runCatching { probeList(index, endpoint, token, dir) }
                val entries = outcome.getOrNull()
                if (entries != null) {
                    cachedStyle = index
                    return@withContext entries.sortedWith(
                        compareByDescending<SandboxEntry> { it.isDir }.thenBy { it.name.lowercase() }
                    )
                }
                val e = outcome.exceptionOrNull()
                if (e is CloudException && e.code > 0) codes += e.code
                val detail = when (e) {
                    is CloudException -> if (e.code > 0) "HTTP ${e.code}" else (e.message ?: "失败")
                    else -> e?.message ?: "失败"
                }
                attempts += "${strategyName(index)}：$detail"
            }
            // 全部是鉴权/不存在类错误时把状态码透出去，供上层判断是否该刷新会话。
            val code = codes.takeIf { it.isNotEmpty() && it.all { c -> c in setOf(401, 403, 404) } }
                ?.last() ?: -1
            throw CloudException("列目录失败 → " + attempts.joinToString("；"), code)
        }
    }

    /** 读取沙箱文件（原始字节）。 */
    /**
     * 读取沙箱文件。
     *
     * [onProgress] 为下载到本机的场景报告 0..1 的真实字节进度；普通预览传 null，
     * 走原来的整块读取（少一次 ByteArrayOutputStream 拷贝）。
     */
    suspend fun read(
        endpoint: String,
        token: String,
        path: String,
        onProgress: ((Float) -> Unit)? = null
    ): ByteArray {
        return withContext(Dispatchers.IO) {
            val base = endpoint.trimEnd('/')
            val encoded = SandboxPath.encodePath(path)
            val user = userFor(path)
            val candidates = listOf(
                "$base/files?path=$encoded&username=$user&cb=${System.currentTimeMillis()}",
                "$base/files?path=$encoded&username=$user",
                "$base/files?path=$encoded"
            )
            var last = "未知错误"
            for (url in candidates) {
                val builder = http.headers(Request.Builder().url(url).get())
                builder.header("X-Access-Token", token)
                builder.header("Accept", "*/*")
                builder.header("Cache-Control", "no-cache")
                builder.header("Origin", settings.baseUrl.trimEnd('/'))
                val response = http.api.newCall(builder.build()).execute()
                val code = response.code
                val bytes = if (code in 200..299) {
                    runCatching {
                        val body = response.body ?: return@runCatching ByteArray(0)
                        if (onProgress == null) {
                            body.bytes()
                        } else {
                            // 流式读，按 Content-Length 报进度；服务端没给长度就报 -1（不确定进度）。
                            val total = body.contentLength()
                            val source = body.source()
                            val buffer = Buffer()
                            val out = ByteArrayOutputStream()
                            var read = 0L
                            var lastReport = 0L
                            while (true) {
                                val n = source.read(buffer, 32 * 1024)
                                if (n < 0) break
                                out.write(buffer.readByteArray())
                                read += n
                                // 限频：进度回调会触发 UI 重组，几十毫秒报一次足够。
                                if (total > 0 && System.currentTimeMillis() - lastReport >= 60) {
                                    onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                                    lastReport = System.currentTimeMillis()
                                }
                            }
                            if (total > 0) onProgress(1f) else onProgress(-1f)
                            out.toByteArray()
                        }
                    }.getOrDefault(ByteArray(0))
                } else null
                response.close()
                if (bytes != null) return@withContext bytes
                last = "HTTP $code"
                if (code !in setOf(401, 403, 404)) {
                    throw CloudException("读取沙箱文件失败：$last", code)
                }
            }
            throw CloudException("读取沙箱文件失败：$last（$user@$path）", 404)
        }
    }

    /** 上传到沙箱。返回服务端确认的落盘路径（拿不到时返回请求路径）。 */
    suspend fun write(endpoint: String, token: String, path: String, bytes: ByteArray): String {
        return withContext(Dispatchers.IO) {
            val safe = SandboxPath.fileName(path)
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", safe,
                    bytes.toRequestBody("application/octet-stream".toMediaType())
                )
                .build()
            val url = endpoint.trimEnd('/') + "/files?path=" + SandboxPath.encodePath(path) +
                "&username=" + userFor(path)
            val builder = http.headers(Request.Builder().url(url).post(body))
            builder.header("X-Access-Token", token)
            builder.header("Accept", "*/*")
            builder.header("Origin", settings.baseUrl.trimEnd('/'))
            val response = http.api.newCall(builder.build()).execute()
            val code = response.code
            val text = runCatching { response.body?.string() }.getOrNull().orEmpty()
            response.close()
            if (code !in 200..299) {
                throw CloudException("上传到沙箱失败：HTTP $code", code)
            }
            val fromArray = runCatching { JSONArray(text).optJSONObject(0)?.optString("path") }
                .getOrNull()?.takeIf { it.isNotBlank() }
            val fromObject = runCatching { JSONObject(text).optString("path") }
                .getOrNull()?.takeIf { it.isNotBlank() }
            fromArray ?: fromObject ?: path
        }
    }

    /** 新建文本文件（走同一套上传通道）。 */
    suspend fun createText(endpoint: String, token: String, path: String, text: String): String =
        write(endpoint, token, path, text.toByteArray(Charsets.UTF_8))

    suspend fun mkdir(endpoint: String, token: String, path: String): Boolean {
        return withContext(Dispatchers.IO) { mutate(endpoint, token, "MakeDir", path) }
    }

    suspend fun remove(endpoint: String, token: String, path: String): Boolean {
        return withContext(Dispatchers.IO) { mutate(endpoint, token, "Remove", path) }
    }

    private fun mutate(endpoint: String, token: String, method: String, path: String): Boolean {
        val body = JSONObject().put("path", path)
        val rpcResult = runCatching { rpc(endpoint, token, method, body) }
        val rpcError = rpcResult.exceptionOrNull()
        val rpcCode = rpcResult.getOrNull()?.first
        if (rpcCode != null && rpcCode in 200..299) return true

        // 极少数旧网关仍暴露 REST 形态，作为兼容分支保留。
        val legacy = when (method) {
            "MakeDir" -> listOf("/filesystem/make_dir", "/filesystem/mkdir", "/files/mkdir")
            else -> listOf("/filesystem/remove", "/files/delete", "/filesystem/delete")
        }
        var last = (rpcError as? CloudException)?.message ?: "云端未开放该操作"
        for (candidate in legacy) {
            val result = runCatching { legacyPost(endpoint, token, candidate, body) }
            val code = result.getOrNull()
            if (code != null && code in 200..299) return true
            last = code?.let { "HTTP $it" } ?: last
        }
        throw CloudException("$method 失败：$last")
    }

    private fun legacyPost(endpoint: String, token: String, path: String, body: JSONObject): Int {
        val builder = http.headers(
            Request.Builder()
                .url(endpoint.trimEnd('/') + path)
                .post(body.toString().toRequestBody(CloudHttp.JSON))
        )
        builder.header("X-Access-Token", token)
        builder.header("Origin", settings.baseUrl.trimEnd('/'))
        val response = http.api.newCall(builder.build()).execute()
        val code = response.code
        response.close()
        return code
    }

    /** Connect RPC unary 调用。 */
    private fun rpc(endpoint: String, token: String, method: String, body: JSONObject): Pair<Int, String> {
        val url = endpoint.trimEnd('/') + "/filesystem.Filesystem/" + method
        val builder = http.headers(
            Request.Builder().url(url).post(body.toString().toRequestBody(CloudHttp.JSON))
        )
        builder.header("X-Access-Token", token)
        builder.header("Content-Type", "application/json")
        builder.header("Connect-Protocol-Version", "1")
        builder.header("Accept", "application/json")
        builder.header("Origin", settings.baseUrl.trimEnd('/'))
        return executeText(http.api.newCall(builder.build()).execute(), method)
    }

    private fun executeText(response: Response, method: String): Pair<Int, String> {
        val code = response.code
        val text = runCatching { response.body?.string() }.getOrNull().orEmpty()
        response.close()
        if (code !in 200..299) throw CloudException("$method HTTP $code", code)
        return code to text
    }

    private fun probeList(index: Int, endpoint: String, token: String, dir: String): List<SandboxEntry> {
        val base = endpoint.trimEnd('/')
        val encoded = SandboxPath.encodePath(dir)
        val text = when (index) {
            // 官方：Connect RPC ListDir
            0 -> rpc(base, token, "ListDir", JSONObject().put("path", dir).put("depth", 1)).second
            // 兼容：部分网关对目录直接返回 JSON 数组
            1 -> get(base + "/files?path=$encoded&username=" + userFor(dir), token)
            2 -> post(base + "/filesystem/list", token, JSONObject().put("path", dir))
            3 -> post(base + "/filesystem.list", token, JSONObject().put("path", dir))
            else -> get(base + "/files/list?path=$encoded", token)
        }
        return SandboxEntryParser.parse(text, dir) ?: throw CloudException("该接口未返回目录结构")
    }

    private fun post(url: String, token: String, body: JSONObject): String {
        val builder = http.headers(
            Request.Builder().url(url).post(body.toString().toRequestBody(CloudHttp.JSON))
        )
        builder.header("X-Access-Token", token)
        builder.header("Connect-Protocol-Version", "1")
        builder.header("Accept", "application/json, */*")
        builder.header("Origin", settings.baseUrl.trimEnd('/'))
        return executeText(http.api.newCall(builder.build()).execute(), "POST").second
    }

    private fun get(url: String, token: String): String {
        val builder = http.headers(Request.Builder().url(url).get())
        builder.header("X-Access-Token", token)
        builder.header("Accept", "application/json, */*")
        builder.header("Cache-Control", "no-cache")
        builder.header("Origin", settings.baseUrl.trimEnd('/'))
        return executeText(http.api.newCall(builder.build()).execute(), "GET").second
    }

    private fun normalizeDir(path: String): String {
        val p = path.trim().ifBlank { "/workspace" }
        return if (p.length > 1) p.trimEnd('/') else p
    }

    private fun strategyName(index: Int): String = when (index) {
        0 -> "ListDir"
        1 -> "/files"
        2 -> "/filesystem/list"
        3 -> "/filesystem.list"
        else -> "/files/list"
    }

    companion object {
        private const val STRATEGIES = 5

        @Volatile
        private var cachedStyle = -1

        private fun field(o: JSONObject, key: String): String? =
            (o.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() }

        private fun normalize(url: String): String =
            url.replace(Regex("^http://"), "https://").trimEnd('/')

        /** `/root` 及其子路径用 root，其余（含 /workspace）用 user，与官方 r2r() 一致。 */
        fun userFor(path: String): String =
            if (path == "/root" || path.startsWith("/root/")) "root" else "user"

        /** 会话下发的显式数据端点优先；否则按官方 w2r/b2r/v2r 规则推导。 */
        fun dataEndpoint(session: JSONObject): String? {
            val explicit = field(session, "e2bDataEndpoint")
                ?: field(session, "sandboxDataLink")
                ?: field(session, "dataEndpoint")
            if (explicit != null) return normalize(explicit)
            return deriveDataEndpoint(session)
        }

        fun deriveDataEndpoint(session: JSONObject): String? {
            val link = field(session, "link") ?: field(session, "e2bEndpoint")
                ?: field(session, "sandboxLink") ?: return null
            val host = runCatching { URI(normalize(link)).host }.getOrNull() ?: return null
            val first = host.substringBefore('.')
            val rest = host.substringAfter('.', "")
            if (rest.isBlank() || first.isBlank()) return null
            val sandboxId = field(session, "sandboxId") ?: first.replaceFirst(Regex("^\\d+-"), "")
            if (sandboxId.isBlank()) return null
            return "https://49983-$sandboxId.$rest"
        }

        /** 兼容只拿到 link 字符串的调用方。 */
        fun deriveDataEndpoint(link: String): String? =
            deriveDataEndpoint(JSONObject().put("link", link))
    }
}

/**
 * ListDir 响应解析（纯函数，便于 JVM 单测直接验证，不需要 Android Context）。
 *
 * 兼容裸数组、`{entries:[...]}`、`{result:{entries:[...]}}`、
 * `{files:[...]}` / `{data:{entries:[...]}}`；条目字段对齐官方 `hXe()`：
 * name / path / type / size / mode / modifiedTime。
 */
internal object SandboxEntryParser {

    private val TYPE_DIR = setOf("FILE_TYPE_DIR", "FILE_TYPE_DIRECTORY", "2")

    fun parse(text: String, dir: String): List<SandboxEntry>? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) return null
        val array: JSONArray = runCatching {
            if (trimmed.startsWith("[")) JSONArray(trimmed) else {
                val root = JSONObject(trimmed)
                root.optJSONArray("entries")
                    ?: root.optJSONObject("result")?.optJSONArray("entries")
                    ?: root.optJSONObject("data")?.let {
                        it.optJSONArray("entries") ?: it.optJSONArray("files")
                    }
                    ?: root.optJSONArray("files")
                    ?: root.optJSONArray("items")
                    ?: return null
            }
        }.getOrNull() ?: return null

        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").takeIf { it.isNotBlank() }
                ?: o.optString("path").takeIf { it.isNotBlank() }?.let { SandboxPath.fileName(it) }
                ?: return@mapNotNull null
            if (name == "." || name == ".." || name.contains('\\')) return@mapNotNull null
            val isDir = isDirectory(o)
            val rawPath = o.optString("path")
            val path = when {
                rawPath.startsWith("/") && !rawPath.contains('\\') &&
                    rawPath.split('/').none { it == ".." } -> rawPath
                else -> SandboxPath.join(dir, name)
            }
            SandboxEntry(
                name = name,
                path = path,
                isDir = isDir,
                size = longOf(o, "size", "bytes", "length"),
                modifiedAt = timeOf(o),
                mimeType = if (isDir) null else SandboxPath.mimeOf(name)
            )
        }
    }

    /** 官方 fds()：type=1/FILE_TYPE_FILE 为文件，2/FILE_TYPE_DIR/DIRECTORY 为目录。 */
    private fun isDirectory(o: JSONObject): Boolean {
        when (val type = o.opt("type")) {
            is Number -> if (type.toInt() == 2) return true
            is String -> if (type.uppercase(Locale.US) in TYPE_DIR) return true
        }
        val text = o.optString("type").ifBlank { o.optString("kind") }.lowercase(Locale.US)
        if (text in setOf("dir", "directory", "folder")) return true
        if (o.optBoolean("isDir", false) || o.optBoolean("is_dir", false) ||
            o.optBoolean("directory", false)
        ) return true
        return (o.optInt("mode", 0) and 0xF000) == 0x4000
    }

    private fun longOf(o: JSONObject, vararg keys: String): Long {
        for (key in keys) {
            when (val value = o.opt(key)) {
                is Number -> return value.toLong()
                is String -> value.toLongOrNull()?.let { return it }
            }
        }
        return 0L
    }

    /** modifiedTime 可以是 ISO 字符串、{seconds,nanos}、毫秒/秒时间戳。 */
    private fun timeOf(o: JSONObject): Long {
        when (val value = o.opt("modifiedTime")) {
            is String -> parseIso(value)?.let { return it }
            is JSONObject -> {
                val seconds = value.optLong("seconds", 0L)
                val nanos = value.optLong("nanos", 0L)
                if (seconds > 0) return seconds * 1000 + nanos / 1_000_000
            }
            is Number -> {
                val n = value.toLong()
                return if (n < 100_000_000_000L) n * 1000 else n
            }
        }
        val legacy = longOf(o, "mtime", "modified", "modifiedAt", "mtimeMs")
        return if (legacy <= 0L) 0L else if (legacy < 100_000_000_000L) legacy * 1000 else legacy
    }

    private fun parseIso(raw: String): Long? {
        if (raw.isBlank()) return null
        runCatching { return java.time.Instant.parse(raw).toEpochMilli() }
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .parse(raw.take(19))?.time ?: 0L
        }.getOrDefault(0L).takeIf { it > 0L }
    }
}
