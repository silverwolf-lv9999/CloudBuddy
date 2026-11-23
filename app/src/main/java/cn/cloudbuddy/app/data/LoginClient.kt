package cn.cloudbuddy.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URL

/**
 * 手机号 + 短信验证码登录，全程原生 HTTP，不开 WebView。
 *
 * 官方 auth-v2 链路：
 * ```
 * createSession: /auth-v2/realms/copilot/signin/phone/login  GET  ?platform=web
 * sendSms:       …/phone/sms    POST {session_state, phone}
 * verify:        …/phone/verify POST {session_state, phone, sms_code, skip_enterprise}
 * finalize:      …/finalize     GET  ?ticket=…&response_type=code&session_state=…
 * shortSession:  …/session      POST {code, issuer, redirect_uri}
 * ```
 * 登录完成后还要用同一套 SSO cookie 静默走一遍 OIDC，让网关把 `session` 种下来。
 */
class LoginClient(
    private val settings: AppSettings,
    private val http: CloudHttp
) {

    private var sessionState: String? = null

    /** 第 1~2 步：建立登录会话并下发短信。返回重发倒计时秒数。 */
    suspend fun sendCode(phone: String): Int = withContext(Dispatchers.IO) {
        sessionState = null
        if (bootstrapConsoleSession() && verify()) {
            settings.saveSession(account = phone)
            return@withContext ALREADY_SIGNED_IN
        }
        val state = createAuthSession()
        sessionState = state
        val payload = JSONObject()
            .put("session_state", state)
            .put("phone", phoneNumber(phone))
        val res = call("POST", authV2(SMS_PATH), payload.toString(), follow = true, json = true)
        val json = runCatching { JSONObject(res.body) }.getOrNull()
            ?: throw CloudException(if (res.status >= 400) "验证码发送失败（HTTP ${res.status}）" else "验证码发送失败，请稍后重试")
        when (json.optString("status")) {
            "ok" -> json.optInt("retry_after", DEFAULT_COOLDOWN).takeIf { it > 0 } ?: DEFAULT_COOLDOWN
            "cooldown" -> throw CloudException("发送过于频繁，请 " + json.optInt("retry_after", DEFAULT_COOLDOWN) + " 秒后再试")
            "captcha_required" -> throw CloudException("服务端要求人机安全验证，请稍后再试或更换网络")
            else -> throw CloudException(smsError(json))
        }
    }

    /** 第 3~5 步：校验验证码并落地登录态。 */
    suspend fun submit(phone: String, code: String): String = withContext(Dispatchers.IO) {
        val state = sessionState ?: throw CloudException("请先获取验证码")
        val payload = JSONObject()
            .put("session_state", state)
            .put("phone", phoneNumber(phone))
            .put("sms_code", code.trim())
            .put("skip_enterprise", true)
        val res = call("POST", authV2(VERIFY_PATH), payload.toString(), follow = true, json = true)
        val json = runCatching { JSONObject(res.body) }.getOrNull()
        when (val status = json?.optString("status").orEmpty()) {
            "ready_to_login" -> Unit
            "oneid_required" -> throw CloudException("该账号需要企业身份验证，请改用官方 App 登录")
            "confirm_bind" -> throw CloudException("该手机号需要确认绑定，请改用官方 App 登录")
            else -> throw CloudException(verifyError(json, res.status, status))
        }
        val ticket = json?.optString("ticket").orEmpty()
        if (ticket.isBlank()) throw CloudException("登录票据缺失，请重新获取验证码")
        val nickname = json?.optString("nickname").orEmpty()

        val finalizeUrl = authV2(FINALIZE_PATH) + "?ticket=" + enc(ticket) +
            "&response_type=code&session_state=" + enc(state)
        val finalized = runCatching {
            JSONObject(call("GET", finalizeUrl, null, follow = true, json = true).body)
        }.getOrNull()
        val authCode = finalized?.optString("code").orEmpty()
        if (authCode.isNotBlank()) runCatching { shortSession(authCode) }

        if (!verify()) {
            bootstrapConsoleSession()
            if (!verify()) throw CloudException("登录未生效，请稍后重试")
        }
        settings.saveSession(account = phone, nickname = nickname)
        phone
    }

    /** 静默续期：只要 SSO cookie 还有效，就不必重收短信。 */
    /**
     * 静默续期。
     *
     * @return true 续上了；false 凭据确实不可用（对方明确拒绝、或没有可续的会话）。
     * @throws java.io.IOException 网络不可用时抛出。调用方要据此区分「该登出」和
     *   「先离线用着」——把一次超时当成登录失效，就是网络一差就跳登录页的原因。
     */
    suspend fun renew(): Boolean = withContext(Dispatchers.IO) {
        if (!bootstrapConsoleSession()) return@withContext false
        verify()
    }

    suspend fun probe(): Boolean = withContext(Dispatchers.IO) { verify() }

    private fun createAuthSession(): String {
        val res = call("GET", authV2(PHONE_LOGIN_PATH) + "?platform=web", null, follow = true, json = true)
        val state = runCatching { JSONObject(res.body).optString("session_state") }.getOrNull()
        if (state.isNullOrBlank()) throw CloudException("无法发起登录，请检查网络后重试")
        return state
    }

    private fun shortSession(authCode: String) {
        val payload = JSONObject()
            .put("code", authCode)
            .put("issuer", settings.baseUrl.trimEnd('/') + ISSUER_PATH)
            .put("redirect_uri", settings.baseUrl.trimEnd('/') + CALLBACK_PATH)
        call("POST", authV2(SHORT_SESSION_PATH), payload.toString(), follow = true, json = true)
    }

    /**
     * 用 SSO 静默走一遍 OIDC 让网关种下 `session`。
     * 关键：先丢掉失效的网关 session —— 网关只在完全没 session 时才 302 到 Keycloak。
     *
     * 这里不再吞异常：网络失败必须让调用方看见，否则「连不上」会被当成「没凭据」。
     */
    private fun bootstrapConsoleSession(): Boolean {
        settings.cookies.dropGatewaySession()
        val boot = call("GET", settings.baseUrl.trimEnd('/') + OIDC_ENTRY, null, follow = false)
        val location = boot.location?.takeIf { it.isNotBlank() } ?: return false
        call("GET", absolute(location), null, follow = true)
        return true
    }

    private fun verify(): Boolean {
        val res = call("GET", settings.baseUrl.trimEnd('/') + PROBE_PATH, null, follow = true)
        val code = runCatching { JSONObject(res.body).optInt("code", -1) }.getOrDefault(-1)
        return res.status in 200..299 && code == 0
    }

    // ---------- HTTP ----------

    private class Reply(val status: Int, val location: String?, val body: String)

    private fun call(
        method: String,
        url: String,
        body: String?,
        follow: Boolean,
        json: Boolean = false
    ): Reply {
        var target = url
        var verb = method
        var payload = body
        var hops = 0
        while (true) {
            val requestBody = if (verb == "GET" || verb == "HEAD") null else payload?.toRequestBody(JSON)
            val builder = Request.Builder().url(target).method(verb, requestBody)
            builder.header("User-Agent", settings.userAgent)
            builder.header("Accept-Language", "zh-CN,zh;q=0.9")
            if (json) {
                builder.header("Referer", loginPageUrl())
                builder.header("Origin", originOf(settings.baseUrl))
                builder.header("Accept", "application/json, text/plain, */*")
            } else {
                builder.header("Referer", settings.baseUrl.trimEnd('/') + "/")
                builder.header("Accept", "text/html, application/json, */*")
            }
            val response = try {
                http.raw.newCall(builder.build()).execute()
            } catch (e: Exception) {
                throw CloudException("网络不可用：" + (e.message ?: e.javaClass.simpleName))
            }
            val status: Int
            val text: String
            val location: String?
            response.use { r ->
                status = r.code
                location = r.header("Location")
                text = runCatching { r.body?.string() }.getOrNull().orEmpty()
            }
            val redirect = status == 301 || status == 302 || status == 303 || status == 307 || status == 308
            if (!redirect || location.isNullOrBlank()) return Reply(status, location, text)
            hops++
            if (!follow || hops > MAX_HOPS) return Reply(status, location, text)
            if (status == 303 || (status == 302 && verb == "POST")) {
                verb = "GET"
                payload = null
            }
            target = URL(URL(target), location).toString()
        }
    }

    fun loginPageUrl(): String {
        val root = settings.baseUrl.trimEnd('/')
        return root + "/login/?platform=agents&state=0&redirect_uri=" + enc(root + "/app")
    }

    private fun authV2(path: String) = settings.baseUrl.trimEnd('/') + AUTH_V2 + path

    private fun absolute(url: String): String = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("/") -> settings.baseUrl.trimEnd('/') + url
        else -> settings.baseUrl.trimEnd('/') + "/" + url
    }

    private fun originOf(url: String): String = runCatching {
        val u = URL(url)
        val port = if (u.port > 0) ":${u.port}" else ""
        "${u.protocol}://${u.host}$port"
    }.getOrDefault(url.trimEnd('/'))

    private fun phoneNumber(phone: String) = "+86" + phone.trim().filter { it.isDigit() }

    private fun enc(raw: String) = java.net.URLEncoder.encode(raw, "UTF-8")

    private fun smsError(json: JSONObject): String {
        val error = json.optString("error")
        val desc = json.optString("error_description")
        return when {
            desc.contains("PhoneNumberDailyLimit", true) -> "该号码今日验证码次数已达上限，请明天再试"
            desc.contains("MinuteLimit", true) || desc.contains("SecondLimit", true) -> "发送过于频繁，请稍后再试"
            error == "invalid_request" -> "手机号不可用，请检查后重试"
            error == "invalid_session" -> "登录会话已失效，请重新获取验证码"
            error == "account_unavailable" -> "该账号暂不可用，请更换手机号"
            desc.isNotBlank() -> "短信发送失败：$desc"
            error.isNotBlank() -> "短信发送失败（$error）"
            else -> "验证码发送失败，请稍后重试"
        }
    }

    private fun verifyError(json: JSONObject?, status: Int, rawStatus: String): String {
        val error = json?.optString("error").orEmpty()
        val errno = json?.optString("errno").orEmpty()
        return when {
            error == "invalid_grant" || errno == "invalid_sms_code" -> "验证码错误或已失效，请重新输入"
            error == "invalid_session" || errno == "invalid_session" -> "登录会话已失效，请重新获取验证码"
            error == "account_unavailable" -> "该账号暂不可用，请更换手机号"
            rawStatus.isNotBlank() -> "验证码校验失败（status=$rawStatus）"
            error.isNotBlank() -> "验证码校验失败（$error）"
            else -> "验证码校验失败（HTTP $status）"
        }
    }

    companion object {
        const val ALREADY_SIGNED_IN = 0

        private const val AUTH_V2 = "/auth-v2/realms/copilot/signin"
        private const val PHONE_LOGIN_PATH = "/phone/login"
        private const val SMS_PATH = "/phone/sms"
        private const val VERIFY_PATH = "/phone/verify"
        private const val FINALIZE_PATH = "/finalize"
        private const val SHORT_SESSION_PATH = "/session"
        private const val OIDC_ENTRY = "/console/accounts"
        private const val PROBE_PATH = "/console/as/conversations/?type=cloud&page=1&size=1"
        private const val ISSUER_PATH = "/auth/realms/copilot"
        private const val CALLBACK_PATH = "/app"
        private const val DEFAULT_COOLDOWN = 60
        private const val MAX_HOPS = 8

        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
