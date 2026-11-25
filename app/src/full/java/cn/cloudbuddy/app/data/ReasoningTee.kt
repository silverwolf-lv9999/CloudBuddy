package cn.cloudbuddy.app.data

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message

/**
 * 把模型的思考过程从 LLM 响应里捡出来。
 *
 * 为什么要在这一层「偷看」：
 * Koog 的 `AIAgent.run()` 只返回最终答案文本，中间那条 `Message.Reasoning`
 * （由 `reasoning_content` 反序列化而来）在 agent 内部就被消化掉了。
 * 而思考强度真的生效与否，用户唯一的判断依据就是「有没有思考内容可看」——
 * 拿不到它，光看设置页那几个字段是没法验证的。
 *
 * 做法是给 [PromptExecutor] 套一层装饰：`execute` 原样委托、原样返回，
 * 只在返回的响应列表里顺手抄一份 `Message.Reasoning` 的文本。
 * 不解析 JSON、不碰 HTTP：字段名与各家写法差异都交给 Koog 自己处理，
 * 它已经把 `reasoning_content` ↔ `Message.Reasoning` 的映射做好了。
 *
 * 一次工具循环里 `execute` 会被调多次，所以每段思考都是独立一次回调，
 * 上层能把它们和工具调用按时间顺序摆在一起，而不是攒到最后一次性糊出来。
 */
internal class ReasoningTee(
    private val delegate: PromptExecutor,
    private val onThought: (String) -> Unit,
) : PromptExecutor by delegate {

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): List<Message.Response> {
        val responses = delegate.execute(prompt, model, tools)
        val thought = responses.filterIsInstance<Message.Reasoning>()
            .flatMap { it.parts }
            .map { it.text }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
        // 回调失败绝不能影响本轮对话：思考只是附加信息，丢了比不上屏更严重的是答不出来。
        if (thought.isNotEmpty()) runCatching { onThought(thought) }
        return responses
    }
}
