package cn.cloudbuddy.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** 启动探测会话的结果，见 [WorkBuddyApi.probeState]。 */
enum class SessionProbe {
    /** 凭据有效。 */
    VALID,

    /** 凭据失效（401/403），该回登录页。 */
    INVALID,

    /** 连不上服务器，凭据是否有效未知——按「先离线用着」处理。 */
    UNREACHABLE
}

/**
 * 把探测到的异常翻译成会话状态。
 *
 * 抽成纯函数是为了让单测盯住它：这里一旦退化成 Boolean，网络一差就会把已登录用户
 * 踢回登录页（重新登录还要等短信验证码）。这个 bug 修过一次，不想再来第二次。
 */
internal fun sessionProbeOf(error: Throwable?): SessionProbe = when {
    error == null -> SessionProbe.VALID
    // 网关对 401/重定向续期失败统一抛这个，是明确的「凭据不可用」。
    error is SessionExpiredException -> SessionProbe.INVALID
    error is CloudException && (error.code == 401 || error.code == 403) -> SessionProbe.INVALID
    // 超时、DNS、断网、5xx 都归到这里：服务器说不清，但用户不该被登出。
    else -> SessionProbe.UNREACHABLE
}

/**
 * WorkBuddy「云上模式」REST 接口。
 *
 * 链路：
 * 1. 下达任务 `POST /console/as/conversations/`
 * 2. 取接入点 `GET /console/as/conversations/{id}/session`
 * 3. 查详情   `GET /console/as/conversations/{id}`（状态兜底）
 * 4. 对话     ACP 长连接（见 [AcpConnection]）
 *
 * 注意：create 里带的 prompt 只用于生成标题，真正的首轮指令必须走 ACP，
 * 否则任务会停在云端队列里等不到执行。
 */
class WorkBuddyApi(private val settings: AppSettings, private val http: CloudHttp) {

    /**
     * 启动时探测会话状态。
     *
     * 必须区分 [SessionProbe.INVALID] 与 [SessionProbe.UNREACHABLE]：
     * 前者是凭据真失效（该回登录页），后者只是连不上网（本地凭据还有效，应该直接进主页）。
     * 以前这里用 `Boolean`，两种失败都变成 false，网络一差就把已登录用户踢回登录页。
     *
     * 探测本身不触发自动续期：续期要连发几次请求，网络差时会把启动拖到超时。
     */
    suspend fun probeState(): SessionProbe = try {
        http.call("GET", "/console/as/conversations/?type=cloud&page=1&size=1", allowRenew = false)
        SessionProbe.VALID
    } catch (e: Throwable) {
        sessionProbeOf(e)
    }

    suspend fun account(): AccountInfo? {
        val data = runCatching { http.data(http.call("GET", "/console/accounts")) }.getOrNull() ?: return null
        val accounts = data.optJSONArray("accounts") ?: return null
        val list = (0 until accounts.length()).mapNotNull { accounts.optJSONObject(it) }
        val account = list.firstOrNull { it.optString("type") == "personal" } ?: list.firstOrNull() ?: return null
        fun field(name: String) = account.optString(name).trim().takeUnless { it == "null" }.orEmpty()
        val avatar = field("avatarUrl").ifBlank { field("avatar_url") }
        // 账号身份要回填到偏好里：官方 AccountService 用它们给每个请求注入
        // X-User-Id / X-Enterprise-Id / X-Tenant-Id，企业账号的模型列表依赖这些头。
        val uid = field("uid").ifBlank { field("userId") }
        val ent = field("enterpriseId").ifBlank { field("enterprise_id") }
        if (uid.isNotBlank()) settings.userId = uid
        if (ent.isNotBlank()) settings.enterpriseId = ent
        return AccountInfo(
            nickname = field("nickname"),
            avatarUrl = avatar,
            phone = settings.accountHint
        )
    }

    suspend fun listConversations(size: Int = 30, title: String = ""): List<Conversation> {
        val sort = enc("""{"orderBy":"updatedAt","order":"desc"}""")
        val origins = enc(ORIGINS)
        val titleFilter = if (title.isBlank()) "" else "&title=${enc(title)}"
        val path = "/console/as/conversations/?type=cloud&page=1&size=$size" +
            "&conversationOrigin=$origins&sort=$sort$titleFilter"
        val data = http.data(http.call("GET", path))
        val array = data.optJSONArray("conversations") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> parseConversation(array.optJSONObject(i)) }
    }

    suspend fun getConversation(id: String): Conversation? =
        runCatching { parseConversation(http.data(http.call("GET", "/console/as/conversations/${enc(id)}"))) }.getOrNull()

    /**
     * 批量取会话详情（官方 `batchGet`：`POST /console/as/conversations/batch-get {ids}`）。
     *
     * 对账只用这一条：请求体只有一个 id、响应只有一条记录，比每次拉 30 条列表便宜一个数量级，
     * 也避免列表接口顺带返回的大字段反复占用带宽与解析开销。
     */
    suspend fun batchGet(ids: List<String>): List<Conversation> {
        val unique = ids.filter { it.isNotBlank() }.distinct()
        if (unique.isEmpty()) return emptyList()
        val body = JSONObject().put("ids", JSONArray(unique))
        val data = http.data(http.call("POST", "/console/as/conversations/batch-get", body))
        val array = data.optJSONArray("conversations") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> parseConversation(array.optJSONObject(i)) }
    }

    suspend fun createConversation(prompt: String, model: String): Pair<String, CloudSession?> {
        val plugins = JSONArray().put(JSONObject().put("name", "weixinpay").put("marketplace", "codebuddy-builtin"))
        val body = JSONObject()
            .put("prompt", prompt.take(100))
            .put("model", model)
            .put("plugins", plugins)
        val data = http.data(http.call("POST", "/console/as/conversations/", body))
        val id = data.optString("id").ifBlank { data.optString("conversationId") }
        if (id.isBlank()) throw CloudException("创建会话失败：响应缺少会话 ID")
        return id to parseSession(id, data.optJSONObject("session"))
    }

    suspend fun fetchSession(id: String): CloudSession {
        val data = http.data(http.call("GET", "/console/as/conversations/${enc(id)}/session"))
        return parseSession(id, data) ?: throw CloudException("会话接入点缺少 link/token")
    }

    suspend fun rename(id: String, title: String) {
        http.call("POST", "/console/as/conversations/${enc(id)}", JSONObject().put("title", title))
    }

    suspend fun archive(id: String) {
        http.call("POST", "/console/as/conversations/${enc(id)}/archive")
    }

    suspend fun delete(id: String) {
        http.call("POST", "/console/as/conversations/${enc(id)}/delete", JSONObject().put("id", id))
    }

    /**
     * 已开通模型（REST 补充来源）。
     *
     * 官方路径 `/console/enterprises/{ent}/models`，`agents[name=cli].models` 是账号
     * 真正可用的集合。优先级：账号的 enterpriseId → personal。任何一步失败都会抛出
     * 可展示的错误，由上层决定合并 ACP 下发的模型或提示重试，避免静默空列表。
     */
    suspend fun listModels(): List<ModelChoice> {
        val candidates = listOf(settings.enterpriseId, AppSettings.ENTERPRISE_PERSONAL)
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        var last: Throwable? = null
        for (ent in candidates) {
            val result = runCatching {
                val data = http.data(http.call("GET", "/console/enterprises/${enc(ent)}/models"))
                parseModels(data)
            }
            result.onSuccess { if (it.isNotEmpty()) return it }
            result.onFailure { last = it }
            if (result.isSuccess) last = CloudException("账号未返回可选模型（$ent）")
        }
        throw CloudException("模型列表加载失败：" + (last?.message ?: "未知原因"))
    }

    private fun parseModels(data: JSONObject): List<ModelChoice> {
        val container = data.optJSONObject("data") ?: data
        val array: JSONArray = container.optJSONArray("models")
            ?: container.optJSONObject("models")?.let { m ->
                m.optJSONArray("items") ?: m.optJSONArray("models") ?: m.optJSONArray("list")
            }
            ?: data.optJSONArray("models")
            ?: return emptyList()
        val enabled = cliModels(container).ifEmpty { cliModels(data) }
        val parsed = (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            if (o.optBoolean("disabled") || o.optBoolean("hidden")) return@mapNotNull null
            val id = sequenceOf(
                o.optString("id"), o.optString("model"), o.optString("modelId"), o.optString("name")
            ).firstOrNull { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            ModelChoice(
                id = id,
                name = sequenceOf(o.optString("name"), o.optString("displayName"), o.optString("label"))
                    .firstOrNull { it.isNotBlank() && it != "null" && it != id } ?: id,
                description = sequenceOf(o.optString("descriptionZh"), o.optString("description"))
                    .firstOrNull { it.isNotBlank() && it != "null" },
                credits = o.optString("credits").takeIf { it.isNotBlank() && it != "null" },
                isDefault = o.optBoolean("isDefault") || o.optBoolean("default")
            )
        }
        // cli.models 与 models 的 id 命名可能不一致；过滤结果为空时保留全部，避免误杀。
        val filtered = if (enabled.isEmpty()) parsed else parsed.filter { it.id in enabled }
        return filtered.ifEmpty { parsed }
    }

    suspend fun usage(): List<UsagePack> {
        val data = runCatching {
            http.data(http.call("POST", "/billing/meter/get-user-resource-summary", JSONObject()))
        }.getOrNull() ?: return emptyList()
        val array = data.optJSONArray("Packages") ?: data.optJSONArray("packages") ?: return emptyList()
        val subscription = data.optString("SubscriptionPackageName")
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val total = number(o, "CycleTotalCapacity", "TotalCapacity") ?: return@mapNotNull null
            val left = number(o, "CycleRemainCapacity", "RemainCapacity") ?: total
            UsagePack(subscription.ifBlank { "积分包" }, total, left)
        }
    }

    // ---------- 积分 / 签到 ----------

    /**
     * 每日签到状态。官方 Web 端用 `checkin-activity-status`，旧接口 `checkin-status` 兜底。
     * 两者都失败时返回默认值而不是抛错——签到是附属信息，不该拖垮设置页。
     */
    suspend fun checkinStatus(): CheckinInfo {
        val primary = runCatching {
            http.data(http.call("POST", "/billing/meter/checkin-activity-status", JSONObject()))
        }.getOrElse { if (it is SessionExpiredException) throw it else null }
        val data = primary ?: runCatching {
            http.data(http.call("POST", "/billing/meter/checkin-status", JSONObject()))
        }.getOrNull() ?: return CheckinInfo()
        return checkinOf(data)
    }

    /** 领取今日签到积分。 */
    suspend fun claimCheckin(): CheckinInfo {
        val data = runCatching {
            http.data(http.call("POST", "/billing/meter/daily-checkin", JSONObject()))
        }.getOrNull() ?: return CheckinInfo(todayCheckedIn = true)
        return checkinOf(data).copy(todayCheckedIn = true)
    }

    private fun checkinOf(o: JSONObject): CheckinInfo {
        fun int(vararg keys: String): Int = keys.firstNotNullOfOrNull { key ->
            if (o.has(key)) o.optInt(key, -1).takeIf { it >= 0 } else null
        } ?: 0
        return CheckinInfo(
            active = !o.has("active") || o.optBoolean("active", true),
            todayCheckedIn = o.optBoolean("today_checked_in") || o.optBoolean("todayCheckedIn"),
            streakDays = int("streak_days", "streakDays"),
            dailyCredit = int("daily_credit", "dailyCredit"),
            todayCredit = int("today_credit", "todayCredit", "credit")
        )
    }

    /** 积分余额与等级；两个接口任一可用即可展示，全部失败返回 null。 */
    suspend fun growth(): GrowthInfo? {
        val energy = runCatching { http.data(http.call("GET", "/activity/growth/energy")) }.getOrNull()
        val profile = runCatching { http.data(http.call("GET", "/v2/activity/growth/profile")) }.getOrNull()
        if (energy == null && profile == null) return null
        return GrowthInfo(
            balance = energy?.optInt("balance", 0) ?: 0,
            level = profile?.optInt("level", 0) ?: 0,
            levelName = profile?.optString("level_name").orEmpty()
                .takeIf { it.isNotBlank() && it != "null" }.orEmpty(),
            completed = profile?.optInt("completed", 0) ?: 0,
            total = profile?.optInt("total", 0) ?: 0
        )
    }

    // ---------- 附件 ----------

    /** 预签名直传（对象存储），返回 agent:// 引用。 */
    suspend fun uploadObject(name: String, mimeType: String, bytes: ByteArray): Artifact =
        withContext(Dispatchers.IO) {
            val key = "uploads/${System.currentTimeMillis()}-${java.util.UUID.randomUUID().toString().take(7)}-" +
                name.substringAfterLast('/').ifBlank { "attachment" }
            val item = presign(listOf(key)).firstOrNull() ?: throw CloudException("上传失败：未取得预签名地址")
            val uploadUrl = item.optString("upload_url").ifBlank { item.optString("url") }
            if (uploadUrl.isBlank()) throw CloudException("上传失败：预签名缺少 upload_url")
            val request = okhttp3.Request.Builder()
                .url(uploadUrl)
                .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
                .build()
            http.stream.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw CloudException("上传失败：HTTP ${response.code}")
            }
            Artifact(name = name, uri = "agent://files/$key", mimeType = mimeType, size = bytes.size.toLong())
        }

    /** 下载产物：沙箱文件走文件网关，对象存储走预签名链接。 */
    suspend fun download(artifact: Artifact, conversationId: String?): ByteArray {
        val path = artifact.sandboxPath
        if (path != null) {
            require(!conversationId.isNullOrBlank()) { "读取沙箱文件需要所属会话" }
            val session = fetchSession(conversationId)
            val endpoint = session.dataEndpoint ?: throw CloudException("会话缺少沙箱文件服务地址")
            return SandboxFs(http, settings).read(endpoint, session.token, path)
        }
        val urls = LinkedHashSet<String>()
        artifact.uri.takeIf { it.startsWith("https://") }?.let(urls::add)
        artifact.downloadUrl?.takeIf { it.startsWith("https://") }?.let(urls::add)
        if (urls.isEmpty()) {
            val keys = SandboxPath.objectKeyVariants(artifact.uri)
            if (keys.isEmpty()) throw CloudException("无法识别的产物地址")
            urls += presign(keys).mapNotNull { it.optString("download_url").takeIf(String::isNotBlank) }
        }
        if (urls.isEmpty()) throw CloudException("下载失败：未取得预签名地址")
        var last = "未知错误"
        repeat(2) { attempt ->
            for (url in urls.toList()) {
                val request = okhttp3.Request.Builder().url(url).get().build()
                http.api.newCall(request).execute().use { response ->
                    if (response.isSuccessful) return response.body?.bytes() ?: ByteArray(0)
                    last = "HTTP ${response.code}"
                }
            }
            if (attempt == 0) {
                val keys = SandboxPath.objectKeyVariants(artifact.uri)
                if (keys.isNotEmpty()) {
                    presign(keys).mapNotNullTo(urls) { it.optString("download_url").takeIf(String::isNotBlank) }
                }
            }
        }
        throw CloudException("下载失败：$last")
    }

    private suspend fun presign(keys: List<String>): List<JSONObject> {
        val body = JSONObject().put("object_keys", JSONArray(keys))
        val data = http.data(http.call("POST", "/console/as/support/presigned_url", body))
        val items = data.optJSONArray("items") ?: data.optJSONObject("data")?.optJSONArray("items")
        return items?.let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it) } }.orEmpty()
    }

    // ---------- 解析 ----------

    fun parseSession(id: String, o: JSONObject?): CloudSession? {
        if (o == null) return null
        val link = sequenceOf(
            o.optString("link"), o.optString("endpoint"), o.optString("url"),
            o.optString("e2bEndpoint"), o.optString("sandboxLink")
        ).firstOrNull { it.isNotBlank() && it != "null" }.orEmpty()
        val token = o.optString("token")
        if (link.isBlank() || token.isBlank()) return null
        return CloudSession(
            conversationId = id,
            link = link,
            token = token,
            cwd = o.optString("cwd").takeIf { it.isNotBlank() && it != "null" },
            sandboxId = o.optString("sandboxId").takeIf { it.isNotBlank() && it != "null" },
            dataEndpoint = SandboxFs.dataEndpoint(o) ?: SandboxFs.deriveDataEndpoint(link),
            acpSessionId = o.optString("sessionId").ifBlank { o.optString("session_id") }.ifBlank { id },
            model = o.optString("model").takeIf { it.isNotBlank() && it != "null" }
        )
    }

    fun parseConversation(o: JSONObject?): Conversation? {
        if (o == null) return null
        val id = o.optString("id").ifBlank { o.optString("conversationId") }
        if (id.isBlank()) return null
        val raw = o.toString()
        val status = TurnStateParser.remote(o)
        return Conversation(
            id = id,
            title = o.optString("name").takeIf { it.isNotBlank() }
                ?: o.optString("title").takeIf { it.isNotBlank() }
                ?: "云端会话",
            state = status,
            model = sequenceOf(
                o.optString("model"),
                o.optJSONObject("status")?.optString("model").orEmpty()
            ).firstOrNull { it.isNotBlank() && it != "null" },
            updatedAt = parseTime(o),
            preview = "",
            artifacts = artifactsOf(raw),
            ask = askOf(o, status),
            error = if (status == TurnState.FAILED) "云端执行失败" else null
        )
    }

    private fun artifactsOf(raw: String): List<Artifact> =
        ARTIFACT_URI.findAll(raw)
            .map { SandboxPath.trimTail(it.value) }
            .filter { it.length > "agent://files/".length }
            .distinct()
            .map { uri ->
                Artifact(
                    name = SandboxPath.decodeSegment(uri.substringAfterLast('/')).ifBlank { "产物文件" },
                    uri = uri
                )
            }
            .toList()

    private fun askOf(o: JSONObject, state: TurnState): AskQuestion? {
        // REST 快照里的 questions 与 ACP 事件同形，复用同一套解析规则。
        val items = findQuestions(o)
            ?.let { parseAskItems(JSONObject().put("questions", it)) }
            .orEmpty()
        if (items.isNotEmpty()) {
            val first = items.first()
            return AskQuestion(
                question = first.question,
                header = first.header,
                options = first.options,
                requestId = findInterruptionId(o),
                items = items
            )
        }
        return if (state == TurnState.WAITING) AskQuestion("云端需要你补充信息后继续") else null
    }

    private fun findQuestions(node: Any?): JSONArray? = when (node) {
        is JSONObject -> node.optJSONArray("questions")?.takeIf { it.length() > 0 }
            ?: node.keys().asSequence().mapNotNull { findQuestions(node.opt(it)) }.firstOrNull()
        is JSONArray -> (0 until node.length()).asSequence().mapNotNull { findQuestions(node.opt(it)) }.firstOrNull()
        else -> null
    }

    private fun findInterruptionId(node: Any?): String? = when (node) {
        is JSONObject -> {
            val direct = node.optString("interruptionId").ifBlank { node.optString("requestId") }
            if (direct.isNotBlank()) direct
            else node.keys().asSequence().mapNotNull { findInterruptionId(node.opt(it)) }.firstOrNull()
        }
        is JSONArray -> (0 until node.length()).asSequence().mapNotNull { findInterruptionId(node.opt(it)) }.firstOrNull()
        else -> null
    }

    private fun parseTime(o: JSONObject): Long {
        val raw = o.optString("updatedAt").ifBlank { o.optString("updateTime") }.ifBlank { o.optString("createdAt") }
        if (raw.isBlank()) return 0L
        raw.toLongOrNull()?.let { return if (it < 100_000_000_000L) it * 1000 else it }
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .parse(raw.take(19))?.time ?: 0L
        }.getOrDefault(0L)
    }

    private fun cliModels(data: JSONObject): Set<String> {
        val agents = data.optJSONArray("agents") ?: return emptySet()
        val agent = (0 until agents.length()).mapNotNull { agents.optJSONObject(it) }
            .firstOrNull { it.optString("name") == "cli" } ?: return emptySet()
        val models = agent.optJSONArray("models") ?: return emptySet()
        return (0 until models.length()).map { models.optString(it) }.filter { it.isNotBlank() }.toSet()
    }

    private fun number(o: JSONObject, vararg keys: String): Double? {
        keys.forEach { key ->
            val value = when (val v = o.opt(key)) {
                is Number -> v.toDouble()
                is String -> v.toDoubleOrNull()
                else -> null
            }
            if (value != null && value.isFinite()) return value
        }
        return null
    }

    private fun enc(raw: String) = URLEncoder.encode(raw, "UTF-8")

    companion object {
        private const val ORIGINS = "agents,workbuddy-mp,workbuddy-app,openapi,workbuddy-deploy"
        private val ARTIFACT_URI =
            Regex("""(?:agent://(?:files/)?|file:///(?:workspace|root)/)[^\s"'\\()\[\]{}<>，。；：！？、]+""")
    }
}

/** 云端会话状态字符串 → 客户端状态。 */
object TurnStateParser {

    /**
     * 取值集合对齐官方 `wVs()`：
     * `planning` / `working|streaming` / `pending|waiting_permission|waiting_question|paused`
     * / `error|interrupted` / `failed` / `terminated`，其余归 idle。
     */
    fun parse(raw: String?): TurnState? = when (normalize(raw)) {
        null -> null
        "running", "working", "streaming", "planning", "in_progress", "inprogress",
        "processing", "queued", "pending", "starting", "created", "initializing" -> TurnState.RUNNING
        "waiting_user_input", "waiting_input", "waiting_permission", "waiting_question",
        "requires_action", "paused", "blocked", "askuser" -> TurnState.WAITING
        "completed", "complete", "done", "success", "succeeded", "finished", "end_turn" -> TurnState.DONE
        "failed", "error", "refusal" -> TurnState.FAILED
        "cancelled", "canceled", "stopped", "aborted", "interrupted", "terminated" -> TurnState.STOPPED
        "idle" -> TurnState.IDLE
        else -> null
    }

    private fun normalize(raw: String?): String? =
        raw?.trim()?.lowercase()?.replace('-', '_')?.replace(' ', '_')?.takeIf { it.isNotEmpty() }

    /**
     * 会话列表项的状态。
     *
     * 官方 `toAgentState()` 是 `const n = t.sessionStatus || t.status` —— 注意 **`sessionStatus` 优先**。
     * 1.0.1 只读 `status`，拿不到值就落到兜底的 `DONE`，于是会话列表里所有会话都显示「已完成」。
     * 兜底同样不该猜「已完成」：官方 `wVs()` 的 default 分支是 `idle`，这里保持一致。
     */
    fun remote(o: JSONObject): TurnState {
        statusValue(o.opt("sessionStatus"))?.let { parse(it) }?.let { return it }
        statusValue(o.opt("status"))?.let { parse(it) }?.let { return it }
        o.optJSONObject("status")?.let { nested ->
            parse(nested.optString("state"))?.let { return it }
            parse(nested.optString("status"))?.let { return it }
            if (nested.has("running")) {
                return if (nested.optBoolean("running")) TurnState.RUNNING else TurnState.DONE
            }
        }
        return TurnState.IDLE
    }

    /** `sessionStatus` 既可能是字符串，也可能包成 `{state:...}` / `{status:...}`。 */
    private fun statusValue(value: Any?): String? = when (value) {
        is String -> value.trim().takeIf { it.isNotEmpty() && it != "null" }
        is JSONObject -> value.optString("state").ifBlank { value.optString("status") }
            .trim().takeIf { it.isNotEmpty() && it != "null" }
        else -> null
    }
}
