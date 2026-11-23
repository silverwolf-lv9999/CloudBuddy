package cn.cloudbuddy.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class CloudException(message: String, val code: Int = -1) : IOException(message)

/** 网关不认当前登录态，且静默续期失败，界面据此要求重新登录。 */
class SessionExpiredException(message: String) : IOException(message)

/**
 * WorkBuddy console 网关的统一 HTTP 层。
 *
 * 信封：`{code, msg, data}`，`code == 0` 或缺省视为成功。
 * 登录失效有两种表现：302 被重定向到登录页 / 401。两者都会触发一次静默续期。
 */
class CloudHttp(private val settings: AppSettings) {

    val cookies: PersistentCookieJar get() = settings.cookies

    /** 业务请求：自动跟随重定向，cookie 由 jar 在每一跳注入。 */
    val api: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(settings.cookies)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    /** 登录链路需要逐跳看 Location，所以不自动跟随。 */
    val raw: OkHttpClient = api.newBuilder()
        .followRedirects(false)
        .build()

    /** 长连接（ACP / 文件传输）：读超时放宽。 */
    val stream: OkHttpClient = api.newBuilder()
        .followRedirects(true)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile var renewer: (() -> Boolean)? = null
    @Volatile var onSessionExpired: (() -> Unit)? = null

    private var lastRenewAt = 0L
    private var lastRenewFailedAt = 0L
    private val renewLock = Any()

    fun url(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) path
        else settings.baseUrl.trimEnd('/') + path

    fun headers(builder: Request.Builder): Request.Builder = builder
        .header("User-Agent", settings.userAgent)
        .header("Referer", settings.baseUrl.trimEnd('/') + "/app")
        .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
        .header("X-Submitted-From", "web")
        .header("X-Client-Platform", "web")
        .header("X-Requested-With", "XMLHttpRequest")
        .header("X-Request-ID", "cloudbuddy-" + UUID.randomUUID().toString().take(8))
        .apply {
            // 官方 AccountService 会给每个业务请求注入账号身份头；企业账号下
            // console 的模型/配置接口会按这些头过滤，缺了会拿到空列表。
            if (settings.userId.isNotBlank()) header("X-User-Id", settings.userId)
            val ent = settings.enterpriseId
            if (ent.isNotBlank() && ent != AppSettings.ENTERPRISE_PERSONAL) {
                header("X-Enterprise-Id", ent)
                header("X-Tenant-Id", ent)
            }
        }

    suspend fun call(
        method: String,
        path: String,
        body: JSONObject? = null,
        productCode: Boolean = false,
        allowRenew: Boolean = true
    ): JSONObject = withContext(Dispatchers.IO) {
        var response = execute(method, path, body, productCode)
        var attempt = 0
        while (allowRenew && (response.code == 401 || response.code in 300..399) &&
            attempt < 2 && renewSession()
        ) {
            response = execute(method, path, body, productCode)
            attempt++
        }
        if (response.code !in 200..299) {
            val message = response.text?.let { runCatching { JSONObject(it).optString("msg") }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
            if (response.code == 401 || response.code in 300..399) {
                onSessionExpired?.invoke()
                throw SessionExpiredException(message ?: "登录已过期，请重新登录")
            }
            throw CloudException(message ?: "HTTP ${response.code}", response.code)
        }
        parseEnvelope(response.text)
    }

    /** 暴露给需要自己处理响应的调用方（如文件下载）。 */
    suspend fun executeRaw(
        method: String,
        url: String,
        body: okhttp3.RequestBody? = null,
        accept: String = "*/*",
        extraHeaders: Map<String, String> = emptyMap()
    ): Response = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url).method(method, body)
        headers(builder)
        builder.header("Accept", accept)
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        api.newCall(builder.build()).execute()
    }

    private class Outcome(val code: Int, val text: String?)

    private fun execute(method: String, path: String, body: JSONObject?, productCode: Boolean): Outcome {
        val target = url(path)
        val requestBody = body?.toString()?.toRequestBody(JSON)
        val builder = Request.Builder().url(target).method(method, requestBody)
        headers(builder)
        builder.header("Accept", "application/json, text/plain, */*")
        if (productCode) builder.header("X-Product-Code", "workbuddy")
        requestBody?.let { builder.header("Content-Type", "application/json; charset=utf-8") }
        api.newCall(builder.build()).execute().use { response ->
            val text = runCatching { response.body?.string() }.getOrNull()
            return Outcome(response.code, text)
        }
    }

    private fun parseEnvelope(text: String?): JSONObject {
        if (text.isNullOrBlank()) return JSONObject()
        val root = runCatching { JSONObject(text) }.getOrNull()
            ?: throw CloudException("云端返回了非 JSON 响应")
        val code = root.opt("code")
        if ((code is Number && code.toLong() != 0L) || root.opt("success") == false) {
            val message = root.optString("msg").ifBlank {
                root.optJSONObject("error")?.optString("message").orEmpty()
            }
            throw CloudException(message.ifBlank { "云端返回 code=$code" })
        }
        return root
    }

    private fun renewSession(): Boolean {
        val now = System.currentTimeMillis()
        synchronized(renewLock) {
            if (now - lastRenewAt < 5_000) return false
            if (now - lastRenewFailedAt < 60_000) return false
            lastRenewAt = now
        }
        val ok = runCatching { renewer?.invoke() == true }.getOrDefault(false)
        if (!ok) synchronized(renewLock) { lastRenewFailedAt = System.currentTimeMillis() }
        return ok
    }

    /** 直接给 JSONObject 取 data，缺省回退到根节点。 */
    fun data(root: JSONObject): JSONObject = root.optJSONObject("data") ?: root

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
