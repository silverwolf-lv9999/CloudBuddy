package cn.cloudbuddy.app.data

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import kotlinx.serialization.Serializable

/**
 * 本机技能的读取工具。
 *
 * 只暴露一个 `use_skill`，做两件事：读技能正文、读技能目录里的补充文件。
 * 不按技能各生成一个工具——技能是用户随时增删的，工具表跟着用户数据变会让
 * 「模型能调什么」变得不可预测；一个固定的入口加一份清单更稳。
 */
class LocalSkillTools(private val store: LocalSkillStore) {

    fun registry(): ToolRegistry = ToolRegistry { tool(UseSkillTool()) }

    @Serializable
    data class UseSkillArgs(
        /** 技能目录名，见系统提示里的清单。 */
        val name: String,
        /** 可选：技能目录内的相对路径（正文里引用到的补充文件）。 */
        val path: String? = null,
    )

    inner class UseSkillTool : SimpleTool<UseSkillArgs>(
        UseSkillArgs.serializer(),
        "use_skill",
        "读取一条本机技能的完整说明。name 取系统提示里列出的技能名；" +
            "如果说明里引用了同目录下的文件（如 references/api.md），用 path 再读那一份。",
    ) {
        override suspend fun execute(args: UseSkillArgs): String {
            val id = args.name.trim().trim('`')
            if (LocalSkillStore.validateId(id) != null || store.body(id) == null) {
                val available = store.enabled().joinToString("、") { it.id }.ifBlank { "（没有启用的技能）" }
                return "没有名为「$id」的技能。可用的技能：$available"
            }
            val relative = args.path?.trim().orEmpty()
            if (relative.isBlank()) {
                return store.body(id) ?: "技能正文读取失败"
            }
            val file = store.readRelative(id, relative)
                ?: return "路径「$relative」不在技能目录内，或技能不存在"
            if (!file.isFile) return "技能 $id 里没有文件「$relative」"
            val text = runCatching { file.readText() }.getOrNull()
                ?: return "读取「$relative」失败"
            // 正文里常带很长的示例；截断上限比沙箱工具宽松，仍留一个保险。
            return if (text.length > 120_000) text.take(120_000) + "\n…（已截断）" else text
        }
    }
}
