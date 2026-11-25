package cn.cloudbuddy.app.data

import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.JsonElement
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 构造打到「用户自己的 OpenAI 兼容端点」上的那个客户端。
 *
 * 单独抽出来的原因：思考参数（[ThinkingParams]）不是 Koog 支持的强类型字段——
 * Koog 的 `LLMParams.additionalProperties` 在 0.6.0 里会被序列化成嵌套的
 * `additional_properties` 对象（不是平铺进请求体），服务端根本不认。
 * 所以这里在 OkHttp 引擎上挂一个拦截器，把字段直接并进 JSON 请求体。
 *
 * 只改请求体这一层，鉴权、路径、超时仍是 Koog 自己配的，不碰协议细节。
 */
object CustomLlmClient {

    /** 这条配置实际要附加的思考字段（可能为空 = 一个字都不加）。 */
    fun thinkingFields(config: CustomModelConfig): Map<String, JsonElement> =
        ThinkingParams.fields(
            config.baseUrl, config.thinkingStyle, config.thinkingEffort, config.modelId
        )

    fun build(config: CustomModelConfig): OpenAILLMClient {
        val resolved = config.endpoint
        val settings = OpenAIClientSettings(
            baseUrl = resolved.baseUrl,
            chatCompletionsPath = resolved.chatCompletionsPath,
        )
        val fields = thinkingFields(config)
        val http = HttpClient(OkHttp) {
            if (fields.isNotEmpty()) {
                // 见 [ThinkingInterceptor]。
                // 只能挂在 OkHttp 这一层：Koog 拿到我们传进去的 client 之后会
                // `client.config { ... }` 派生一个新实例，派生时 HttpSend 的拦截器
                // 不会带过去（ktor 的 client 级钩子也一样），但引擎配置是共享的。
                engine { addInterceptor(ThinkingInterceptor(fields)) }
            }
        }
        return OpenAILLMClient(config.apiKey, settings, http)
    }

    /**
     * 参数表：除了温度什么都不传。
     *
     * 思考相关的字段全部走 [build] 里的拦截器，这样只有一处会改请求体，
     * 不会再出现「一半参数在 Koog 里、一半在拦截器里」的扯皮。
     */
    fun params(config: CustomModelConfig): LLMParams =
        OpenAIChatParams(temperature = config.temperature.coerceIn(0.0, 2.0))

    fun model(config: CustomModelConfig): LLModel = LLModel(
        provider = CustomLLMProvider,
        id = config.modelId,
        capabilities = listOf(
            LLMCapability.Completion,
            LLMCapability.Temperature,
            LLMCapability.Tools,
            LLMCapability.Schema.JSON.Basic,
            // 必须显式声明用 Chat Completions 协议：Koog 只认识自家内置的模型 ID，
            // 遇到 deepseek-chat 这种自定义 ID 时无法推断端点，会直接报
            // "Cannot determine proper LLM params"。
            LLMCapability.OpenAIEndpoint.Completions,
        ),
        contextLength = 128_000L,
    )
}

/**
 * 在 HTTP 层把思考字段并进请求体。
 *
 * 选这一层是被逼的：Koog 的内部组装是 private，`LLMParams.additionalProperties`
 * 会被序列化成嵌套对象（服务端不认），ktor 的 client 级钩子在 Koog
 * `client.config{}` 派生新实例时又会丢；只有引擎（OkHttp）配置会被派生实例共享。
 *
 * 只读 [okhttp3.RequestBody] 的情况都是可以重复写出的（ktor 的 TextContent 是
 * ByteArrayContent），读不出 JSON 就原样放行，绝不让这段搞坏正常请求。
 */
private class ThinkingInterceptor(
    private val fields: Map<String, JsonElement>,
) : okhttp3.Interceptor {

    override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val body = request.body ?: return chain.proceed(request)
        val text = runCatching {
            val buffer = okio.Buffer()
            body.writeTo(buffer)
            buffer.readUtf8()
        }.getOrNull() ?: return chain.proceed(request)
        // 读出来就一定要用读出来的内容重发：原 body 已经被 writeTo 消费掉了，
        // 还拿原对象 proceed 会发出一个空 body（比不生效更难查）。
        val merged = ThinkingParams.apply(text, fields)
        val patched = merged.toRequestBody(body.contentType())
        return chain.proceed(request.newBuilder().method(request.method, patched).build())
    }
}

/** 第三方端点在 Koog 里的 provider 标识（仅用于日志与能力判定）。 */
internal object CustomLLMProvider : LLMProvider("openai-compatible", "OpenAI 兼容端点")
