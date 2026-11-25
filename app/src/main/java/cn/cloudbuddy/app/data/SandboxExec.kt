package cn.cloudbuddy.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 沙箱命令执行（E2B envd `process.Process/Start`）。
 *
 * 这条通道与 ACP 对话完全独立：**不产生会话记录、不消耗积分**，
 * 因此可以拿来做 agent 工具调用，也可以做纯执行面板。
 *
 * 报文（实测确认）：
 * ```
 * POST {endpoint}/process.Process/Start
 * Content-Type: application/connect+json
 * Connect-Protocol-Version: 1
 * X-Access-Token: {token}
 * 帧: {"process":{"cmd":"/bin/sh","args":["-c","<cmd>"],"cwd":"/workspace"},"stdin":false}
 * ```
 *
 * 响应是 server-streaming：按 Connect 帧返回，`data.stdout` / `data.stderr` 为 base64。
 * 这里一次性读完整个流再解帧——命令执行通常秒级结束，够用且实现简单；
 * 代价是拿不到"边跑边看"的实时输出，那需要真正的流式读取（后续可加）。
 */
class SandboxExec(
    private val api: okhttp3.OkHttpClient,
    /** 请求头里的 Origin，需与当前云端域名一致；测试时可留空。 */
    private val origin: String = "",
) {

    /** 生产路径：复用 [CloudHttp] 的连接池与通用头。 */
    constructor(http: CloudHttp, settings: AppSettings) : this(http.api, settings.baseUrl)

    /** 一次命令执行的结果。 */
    data class Result(
        val stdout: String,
        val stderr: String,
        val exitCode: Int?,
        val error: String? = null,
    ) {
        val ok: Boolean get() = error == null && (exitCode == null || exitCode == 0)
    }

    /**
     * 执行一条命令。
     *
     * [timeoutSec] 会真正生效：`supplyCallTimeout` 让 OkHttp 在超时后中止整个 call，
     * 而不是靠读超时被动等（早先版本这个参数没接上，命令挂住就不会返回）。
     *
     * 协程被取消（用户点停止）时会主动 `cancel()` 这个 call：不取消的话，
     * 停止只是不再等结果，沙箱里那条命令仍在跑，白烧沙箱算力。
     */
    suspend fun run(
        endpoint: String,
        token: String,
        command: String,
        cwd: String = "/workspace",
        timeoutSec: Int = 120,
        /**
         * token 过期时的续期钩子：返回新 token 就用它重试一次。
         *
         * 实测沙箱 JWT 有效期 72 小时（`exp - iat = 259200`），过期后 envd 一律回
         * 401/403。长会话隔天回来必然撞上，没有这个钩子只能让用户重建会话。
         */
        onUnauthorized: (suspend () -> String?)? = null,
        /**
         * 边跑边吐输出：`(文本, 是否 stderr)`。
         *
         * 传了就走增量读帧，命令还没结束就能看到第一批输出；
         * 不传则沿用一次性读完的路径（工具调用场景不需要流式，少一层开销）。
         */
        onChunk: ((String, Boolean) -> Unit)? = null,
    ): Result {
        val attempt = execute(endpoint, token, command, cwd, timeoutSec, onChunk)
        if (!attempt.unauthorized || onUnauthorized == null) return attempt.result
        // 续期失败就如实返回第一次的 401，不掩盖真实原因。
        val fresh = runCatching { onUnauthorized() }.getOrNull()
        if (fresh.isNullOrBlank() || fresh == token) return attempt.result
        return execute(endpoint, fresh, command, cwd, timeoutSec, onChunk).result
    }

    /** 单次尝试：结果 + 是否因为鉴权失败（决定要不要续期重试）。 */
    private class Attempt(val result: Result, val unauthorized: Boolean)

    private suspend fun execute(
        endpoint: String,
        token: String,
        command: String,
        cwd: String,
        timeoutSec: Int,
        onChunk: ((String, Boolean) -> Unit)? = null,
    ): Attempt = withContext(Dispatchers.IO) {
        val url = endpoint.trimEnd('/') + "/process.Process/Start"
        val body = JSONObject()
            .put("process", JSONObject()
                .put("cmd", "/bin/sh")
                .put("args", org.json.JSONArray().put("-c").put(command))
                .put("cwd", cwd.ifBlank { "/workspace" }))
            .put("stdin", false)

        val frame = ConnectFrame.encode(body.toString().toByteArray(Charsets.UTF_8))
        val builder = Request.Builder().url(url).post(frame.toRequestBody(CONNECT_JSON))
        builder.header("X-Access-Token", token)
        builder.header("Content-Type", CONNECT_JSON.toString())
        builder.header("Connect-Protocol-Version", "1")
        builder.header("Accept", CONNECT_JSON.toString())
        if (origin.isNotBlank()) builder.header("Origin", origin.trimEnd('/'))

        val call = api.newCall(builder.build())
        runCatching {
            call.timeout().timeout(timeoutSec.coerceIn(1, 3600) * 1000L, TimeUnit.MILLISECONDS)
        }

        // 协程取消 → 同步取消底层 call，避免命令在沙箱里空转。
        val watchdog = CoroutineScope(Dispatchers.IO).launch {
            try {
                awaitCancellation()
            } finally {
                runCatching { call.cancel() }
            }
        }

        try {
            val frames: List<ByteArray> = call.execute().use { response ->
                if (!response.isSuccessful) {
                    val text = runCatching { response.body?.string() }.getOrNull().orEmpty()
                    return@withContext Attempt(
                        Result("", "", null, "HTTP ${response.code}: ${text.take(300)}"),
                        response.code == 401 || response.code == 403,
                    )
                }
                val source = response.body?.source()
                if (onChunk == null || source == null) {
                    // 一次性读完：工具调用是高频路径，多一层增量解析没收益。
                    return@use ConnectFrame.decodeAll(response.body?.bytes() ?: ByteArray(0))
                }
                // 流式路径：边读边回调，命令没跑完就能看到输出。
                // 帧仍然全部攒下来，结尾走同一套 merge，退出码/错误语义保持一致。
                val acc = ArrayList<ByteArray>()
                while (true) {
                    val ready = ioGuard { source.request(ConnectFrame.HEADER_SIZE.toLong()) } ?: break
                    if (!ready) break
                    val header = ioGuard { source.readByteArray(ConnectFrame.HEADER_SIZE.toLong()) } ?: break
                    val len = ConnectFrame.lengthOf(header)
                    // 长度非法说明流脏了；继续读只会读到错位的数据，直接收尾。
                    if (len <= 0 || len > MAX_FRAME_BYTES) break
                    val payload = ioGuard { source.readByteArray(len.toLong()) } ?: break
                    acc.add(payload)
                    emitChunk(payload, onChunk)
                }
                acc
            }
            Attempt(ProcessOutput.merge(frames), false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.InterruptedIOException) {
            // OkHttp 超时/取消都落在 InterruptedIOException：区分是用户停止还是超时。
            val msg = if (!currentCoroutineContext().isActive) "已停止" else "执行超时（${timeoutSec}s）"
            Attempt(Result("", "", null, msg), false)
        } catch (e: Exception) {
            Attempt(Result("", "", null, e.message ?: "请求失败"), false)
        } finally {
            watchdog.cancel()
        }
    }

    /** 单帧载荷上限。超过它只可能是帧头读错位，继续读下去会产出垃圾。 */
    private val MAX_FRAME_BYTES = 8 * 1024 * 1024

    /**
     * 读流时的容错：流被网关掐断是常态，已收到的输出仍然有用，
     * 所以把解析失败降级成"读完了"，而不是整条结果作废。
     *
     * 取消与超时必须原样抛出去——那是用户点了停止或真的超时，
     * 吞掉的话界面会一直显示"执行中"。
     */
    private inline fun <T> ioGuard(block: () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.InterruptedIOException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /**
     * 从一帧里抽出 stdout/stderr 增量，喂给流式回调。
     *
     * 解析失败静默跳过：流式只是"锦上添花"的显示通道，
     * 真出错会由结尾的 [ProcessOutput.merge] 统一报告，不该在这里抛。
     */
    private fun emitChunk(payload: ByteArray, onChunk: (String, Boolean) -> Unit) {
        val obj = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: return
        val data = obj.optJSONObject("event")?.optJSONObject("data") ?: return
        ProcessOutput.decodeB64(data.optString("stdout")).takeIf { it.isNotEmpty() }
            ?.let { runCatching { onChunk(it, false) } }
        ProcessOutput.decodeB64(data.optString("stderr")).takeIf { it.isNotEmpty() }
            ?.let { runCatching { onChunk(it, true) } }
    }

    private companion object {
        val CONNECT_JSON = "application/connect+json".toMediaType()
    }
}

/**
 * `process.Process/Start` 响应帧的解析。
 *
 * 拆成纯函数是为了能单测：真实响应需要活沙箱，而帧格式本身有足够多的边界
 * （空载荷、截断帧、只带 stderr、end 帧缺失、错误帧）。
 */
object ProcessOutput {

    /** 把多帧响应合并成一次执行结果。 */
    fun merge(frames: List<ByteArray>): SandboxExec.Result {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var exitCode: Int? = null
        var error: String? = null

        for (payload in frames) {
            val obj = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: continue

            // 顶层 error：Connect 的 end-of-stream 错误帧。
            obj.optJSONObject("error")?.let { err ->
                error = err.optString("message").ifBlank { err.toString() }
                return@let
            }

            val event = obj.optJSONObject("event") ?: continue

            event.optJSONObject("data")?.let { data ->
                stdout.append(decodeB64(data.optString("stdout")))
                stderr.append(decodeB64(data.optString("stderr")))
            }

            event.optJSONObject("end")?.let { end ->
                exitCode = intOf(end, "exitCode", "exit_code")
                // 实测：非零退出码时 envd 会把 `"error": "exit status 7"` 一并塞进 end 帧
                // （`status` 字段也是同一句话）。这是命令的退出状态，不是执行/协议错误，
                // 当成错误会误导模型去重试明明已经跑完的命令。
                val msg = end.optString("error")
                if (msg.isNotBlank() && !isExitStatus(msg)) error = msg
            }

            // 兼容：部分版本把 exitCode 直接放在 event 层。
            if (exitCode == null) exitCode = intOf(event, "exitCode", "exit_code")
        }

        return SandboxExec.Result(stdout.toString(), stderr.toString(), exitCode, error)
    }

    /** base64 解码；空串或非法输入返回空串，不让脏数据打断合并。 */
    fun decodeB64(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            String(Base64.getDecoder().decode(raw), Charsets.UTF_8)
        }.getOrElse {
            // 少数网关直接给明文而不是 base64，退回原文。
            raw
        }
    }

    /**
     * 判断 end 帧里的 `error` 是否只是「命令以非零码退出」的描述。
     *
     * envd 的三种形态实测：`"exit status 7"`、`"status 7"`、`"signal: killed"`。
     * 这些都要当正常结果返回（stdout/stderr 才是模型需要的信息），
     * 只有不符合这些形态的才是真正的执行错误。
     */
    private fun isExitStatus(msg: String): Boolean {
        val s = msg.trim()
        return EXIT_STATUS.matches(s) || s.startsWith("signal: ")
    }

    private val EXIT_STATUS = Regex("^(exit )?status \\d+$")

    private fun intOf(o: JSONObject, vararg keys: String): Int? {
        for (k in keys) {
            if (o.has(k) && !o.isNull(k)) {
                val v = o.opt(k)
                if (v is Number) return v.toInt()
                if (v is String) return v.toIntOrNull()
            }
        }
        return null
    }
}
