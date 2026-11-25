package cn.cloudbuddy.app.data

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolParameterType
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 自研的 OpenAI 兼容流式客户端——本机会话的对话引擎，**不再借道 Koog**。
 *
 * 为什么必须自研（这段是血泪史，删之前想清楚）：
 * 1. Koog 0.6.0 的 OpenAI 客户端**根本不解析 `reasoning_content`**——
 *    在它的 jar 里搜不到这个字符串（2026-11 实测）。DeepSeek / 大量中转站
 *    的思考内容恰恰是用这个字段下发的，于是上一轮套的 ReasoningTee
 *    建立在错误前提上：Koog 上游就没收到过思考，装饰器自然抄不出来。
 * 2. 思考参数（reasoning_effort 这类）Koog 的 LLMParams 塞不进请求体
 *    （additionalProperties 会被序列化成嵌套对象），只能靠 OkHttp 拦截器改 body，
 *    一半参数在 Koog 里一半在拦截器里，谁也说不清最终发出了什么。
 * 3. 参照 rikkahub 的做法：自研协议层（OkHttp/SSE + kotlinx.serialization），
 *    `reasoning_content ?: reasoning` 双字段兜底，请求体自己拼，每个字段都可控。
 *
 * 只支持 Chat Completions 协议：这是所有中转站 / DeepSeek / 通义 / vLLM 的公约数。
 */

/** 发给服务端的一条消息（Chat Completions 形状）。 */
sealed interface ChatMsg {
    data class System(val content: String) : ChatMsg

    /**
     * 用户消息。[images] 非空时走多模态形状：content 变成
     * `[{type:text},{type:image_url}]` 数组，图片是 data URL（base64）
     * 或 http(s) 直链——读图模型直发，文本模型仍只有文本部分。
     */
    data class User(val content: String, val images: List<String> = emptyList()) : ChatMsg

    /**
     * assistant 消息。[toolCalls] 非空表示这是一条「要求调工具」的中间消息：
     * 协议要求它和对应的 tool 结果一起回传，模型才能接着干活。
     */
    data class Assistant(val content: String, val toolCalls: List<ChatToolCall> = emptyList()) : ChatMsg
    data class ToolResult(val toolCallId: String, val content: String) : ChatMsg
}

/** 一次工具调用请求（由模型发起）。[arguments] 是尚未解析的 JSON 文本。 */
data class ChatToolCall(val id: String, val name: String, val arguments: String)

/**
 * 引擎可调用的工具的最小接口。刻意不绑定 Koog：
 * 沙箱工具、技能工具、MCP 工具各自有来源，在装配层统一适配成这个形状。
 */
interface ChatTool {
    val name: String
    val description: String

    /** JSON Schema 形状的 `parameters` 对象。 */
    val parameters: JsonObject

    suspend fun execute(args: JsonObject): String
}

/** 一次流式请求收尾后的汇总。 */
data class ChatStreamResult(
    val content: String,
    val reasoning: String,
    val toolCalls: List<ChatToolCall>,
    val finishReason: String?,
)

/**
 * 流式客户端。一个实例一个 HttpClient（连接池跨轮复用），
 * 会话结束时上层负责 close。
 */
class OpenAiStreamClient : java.io.Closeable {

    private val http = HttpClient(OkHttp) {
        engine {
            config {
                readTimeout(java.time.Duration.ofSeconds(300))
                connectTimeout(java.time.Duration.ofSeconds(30))
            }
        }
        expectSuccess = false
    }

    /**
     * 发起一次流式对话并读完整个响应。
     *
     * [onReasoningDelta] / [onContentDelta] 逐段回调原始增量，
     * 上层用它做实时显示；返回值是拼好的全文与工具调用列表。
     */
    suspend fun stream(
        config: CustomModelConfig,
        messages: List<ChatMsg>,
        tools: List<ChatTool> = emptyList(),
        onReasoningDelta: (String) -> Unit = {},
        onContentDelta: (String) -> Unit = {},
    ): ChatStreamResult {
        val url = config.streamUrl
        val body = buildRequestBody(config, messages, tools)

        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = LinkedHashMap<Int, ToolCallAcc>()
        var finish: String? = null

        http.preparePost(url) {
            bearerAuth(config.apiKey)
            // 提供商的自定义请求头（中转站常要 Referer / X-Title 之类），覆盖默认值时放后面生效。
            config.extraHeaders.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) header(k, v)
            }
            header(HttpHeaders.ContentType, "application/json")
            header(HttpHeaders.Accept, "text/event-stream")
            setBody(body)
        }.execute { response: HttpResponse ->
            if (!response.status.isSuccess()) {
                val err = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
                throw ChatStreamException(describeHttpError(response.status.value, err))
            }
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (line.isBlank() || line.startsWith(":")) continue
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                val obj = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                // usage / system 之类的没有 choices 的帧直接跳过
                val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                val delta = choice["delta"]?.jsonObject
                delta?.forEachReasoning { seg ->
                    if (seg.isNotEmpty()) {
                        reasoning.append(seg)
                        runCatching { onReasoningDelta(seg) }
                    }
                }
                delta?.get("content")?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { seg ->
                    content.append(seg)
                    runCatching { onContentDelta(seg) }
                }
                delta?.get("tool_calls")?.jsonArray?.forEach { raw ->
                    val call = raw.jsonObject
                    val index = call["index"]?.jsonPrimitiveOrNull?.intOrNull ?: toolCalls.size
                    val acc = toolCalls.getOrPut(index) { ToolCallAcc() }
                    call["id"]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }?.let { acc.id = it }
                    call["function"]?.jsonObject?.let { fn ->
                        fn["name"]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }?.let { acc.name = it }
                        fn["arguments"]?.jsonPrimitiveOrNull?.contentOrNull?.let { acc.arguments.append(it) }
                    }
                }
                choice["finish_reason"]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }?.let {
                    finish = it
                }
            }
        }

        return ChatStreamResult(
            content = content.toString(),
            reasoning = reasoning.toString(),
            toolCalls = toolCalls.entries.sortedBy { it.key }
                .map { (i, acc) ->
                    ChatToolCall(
                        id = acc.id.ifBlank { "call-$i" },
                        name = acc.name,
                        arguments = acc.arguments.toString(),
                    )
                },
            finishReason = finish,
        )
    }

    /** 拉取端点的模型列表（GET /models）。失败抛 [ChatStreamException]。 */
    suspend fun listModels(config: CustomModelConfig): List<String> {
        val resolved = config.endpoint
        val url = resolved.baseUrl.trimEnd('/') + "/models"
        val resp = http.get(url) { bearerAuth(config.apiKey) }
        if (!resp.status.isSuccess()) {
            throw ChatStreamException("拉取模型列表失败：HTTP ${resp.status.value}")
        }
        val root = runCatching { Json.parseToJsonElement(resp.bodyAsText()).jsonObject }.getOrNull()
            ?: throw ChatStreamException("模型列表响应不是 JSON")
        val data = root["data"]?.jsonArray ?: return emptyList()
        return data.mapNotNull { raw ->
            val o = raw.jsonObject
            o["id"]?.jsonPrimitiveOrNull?.contentOrNull?.takeIf { it.isNotBlank() }
        }
    }

    /** 组请求体。思考字段直接平铺进 JSON——自研后不再需要任何拦截器补丁。 */
    private fun buildRequestBody(
        config: CustomModelConfig,
        messages: List<ChatMsg>,
        tools: List<ChatTool>,
    ): String = ChatRequest.build(config, messages, tools)

    /** delta 里的字段可能缺失或为 null；只接受真正的字符串基本量。 */
    private val JsonElement?.jsonPrimitiveOrNull: JsonPrimitive?
        get() = this as? JsonPrimitive

    private fun JsonObject.forEachReasoning(block: (String) -> Unit) {
        // DeepSeek / 大多数中转站用 reasoning_content，OpenRouter 用 reasoning，都接住。
        val raw = get("reasoning_content") ?: get("reasoning") ?: return
        val text = raw.jsonPrimitiveOrNull?.contentOrNull ?: return
        if (text.isNotEmpty()) block(text)
    }

    private fun describeHttpError(code: Int, body: String): String {
        val detail = runCatching {
            Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")
                ?.jsonPrimitiveOrNull?.contentOrNull
        }.getOrNull()
        val suffix = detail?.takeIf { it.isNotBlank() } ?: body.take(300)
        return "HTTP $code: $suffix"
    }

    private class ToolCallAcc {
        var id: String = ""
        var name: String = ""
        val arguments = StringBuilder()
    }

    override fun close() {
        runCatching { http.close() }
    }
}

/** 流式请求失败。message 是给用户看的一句话。 */
class ChatStreamException(message: String) : Exception(message)

/**
 * Chat Completions 请求体组装。独立成纯函数是为了单测不用起服务器：
 * 思考字段发错时服务端一声不响地忽略，表现只是「模型还是不想」，
 * 只能靠把真正要发出的 JSON 抓下来核对。
 */
internal object ChatRequest {

    fun build(
        config: CustomModelConfig,
        messages: List<ChatMsg>,
        tools: List<ChatTool>,
    ): String = buildJsonObject {
        put("model", config.modelId)
        put("stream", true)
        put("temperature", config.temperature.coerceIn(0.0, 2.0))
        put("messages", JsonArray(messages.map { it.toJson() }))
        if (tools.isNotEmpty()) {
            put("tools", JsonArray(tools.map { t ->
                buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", t.name)
                        put("description", t.description)
                        put("parameters", t.parameters)
                    })
                }
            }))
            put("tool_choice", "auto")
        }
        // 思考参数（reasoning_effort / enable_thinking 这类）按端点风格平铺。
        ThinkingParams.fields(
            config.baseUrl, config.thinkingStyle, config.thinkingEffort, config.modelId
        ).forEach { (k, v) -> put(k, v) }
    }.toString()

    private fun ChatMsg.toJson(): JsonObject = when (this) {
        is ChatMsg.System -> buildJsonObject {
            put("role", "system"); put("content", content)
        }
        is ChatMsg.User -> buildJsonObject {
            put("role", "user")
            if (images.isEmpty()) {
                put("content", content)
            } else {
                // 多模态：文本 + 每张图一个 image_url part（OpenAI 兼容标准形状，
                // DeepSeek / 通义 / 中转站的读图模型都认）。data URL 或 http 直链都放 url。
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", content)
                    })
                    images.forEach { url ->
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", url) })
                        })
                    }
                })
            }
        }
        is ChatMsg.Assistant -> buildJsonObject {
            put("role", "assistant")
            put("content", content)
            if (toolCalls.isNotEmpty()) {
                put("tool_calls", JsonArray(toolCalls.map { c ->
                    buildJsonObject {
                        put("id", c.id)
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", c.name)
                            put("arguments", c.arguments.ifBlank { "{}" })
                        })
                    }
                }))
            }
        }
        is ChatMsg.ToolResult -> buildJsonObject {
            put("role", "tool")
            put("tool_call_id", toolCallId)
            put("content", content)
        }
    }
}

/** 把 Koog 工具表里的工具适配成 [ChatTool]（描述转 JSON Schema，执行走 decodeArgs）。 */
@Suppress("UNCHECKED_CAST")
fun Tool<*, *>.asChatTool(): ChatTool {
    val tool = this
    val d = tool.descriptor
    val params = (d.requiredParameters + d.optionalParameters)
    return object : ChatTool {
        override val name = d.name
        override val description = d.description
        override val parameters: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                params.forEach { p -> put(p.name, schemaOf(p.type)) }
            }
            putJsonArray("required") {
                d.requiredParameters.forEach { add(JsonPrimitive(it.name)) }
            }
        }

        override suspend fun execute(args: JsonObject): String {
            val decoded = (tool as Tool<Any, Any>).decodeArgs(args)
            return tool.execute(decoded).toString()
        }
    }
}

/** Koog 参数类型 → JSON Schema 片段。认不出的类型按 string 兜底。 */
private fun schemaOf(type: ToolParameterType): JsonObject = when (type) {
    is ToolParameterType.String -> buildJsonObject { put("type", "string") }
    is ToolParameterType.Integer -> buildJsonObject { put("type", "integer") }
    is ToolParameterType.Boolean -> buildJsonObject { put("type", "boolean") }
    is ToolParameterType.Enum -> buildJsonObject {
        put("type", "string")
        putJsonArray("enum") { type.entries.forEach { add(JsonPrimitive(it)) } }
    }
    is ToolParameterType.List -> buildJsonObject {
        put("type", "array")
        put("items", schemaOf(type.itemsType))
    }
    else -> buildJsonObject { put("type", "string") }
}
