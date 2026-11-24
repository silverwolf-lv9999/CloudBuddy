package cn.cloudbuddy.app.data

/**
 * 中间步骤的折叠方式。
 *
 * 两种模式对「什么算过程」的定义不同：
 *
 * - [WHOLE]：整轮折叠。一轮里除最终回答外的一切（思考、工具调用、过程叙述）
 *   合成一个组，插在这轮开头。回答少而步骤多的任务看这个最清爽，但过程里
 *   写下的正文也会被一起藏进去。
 * - [BODY]：只折思考与工具调用。正文（包括任务过程中陆续输出的段落）始终可见，
 *   它们之间的思考与工具调用各自变成一行折叠条。参考 DSH / Claude 一类终端的
 *   呈现：读者关心的是「它说了什么」，不看「它想了什么」。
 */
enum class FoldMode(val key: String) {
    WHOLE("whole"),
    BODY("body");

    companion object {
        /** 默认整轮折叠：短会话里步骤占了绝大多数体积。 */
        const val DEFAULT = "whole"

        fun of(raw: String?): FoldMode = when (raw) {
            BODY.key -> BODY
            else -> WHOLE
        }
    }
}
