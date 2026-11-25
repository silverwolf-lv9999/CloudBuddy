package cn.cloudbuddy.app.data

/**
 * 把「有哪些技能」写进系统提示。
 *
 * 这里用的是渐进披露（progressive disclosure）：**只放名字和一句话描述**，
 * 正文等模型调用 `use_skill` 时再读。理由很实际——一条技能正文常常上万字，
 * 十条技能全塞进系统提示，每轮都要付这份 token，而且真正用到的可能只有一条。
 *
 * 与提示词片段的区别：片段是「每轮都该遵守的规矩」（编码规范、项目约定），
 * 常驻才对；技能是「这类任务的做法」，按需加载才对。两者混在一起会让模型
 * 在几十条无关说明里找重点。
 */
object SkillPrompt {

    /** 单条描述的上限，与 Agent Skills 规范一致。 */
    private const val MAX_DESCRIPTION = 1024

    /**
     * 生成技能清单段落；没有启用任何技能时返回空串（调用方据此决定要不要拼）。
     *
     * 返回空串而不是一句「暂无技能」，是为了让「一条都没装」和「没这个功能」
     * 在模型眼里完全一样——否则每轮都多一句无用的话。
     */
    fun brief(skills: List<LocalSkill>): String {
        if (skills.isEmpty()) return ""
        return buildString {
            append("## 可用技能\n")
            append("下面这些技能是安装在本机的操作说明。当任务与某个技能的描述相符时，")
            append("先调用 `use_skill` 读取它的完整说明再动手，不要凭名字猜做法；")
            append("说明里如果引用了同目录下的文件路径，再用 `use_skill` 的 path 参数读取那一份。\n")
            for (s in skills) {
                append("- `").append(s.id).append("`：")
                append(s.description.take(MAX_DESCRIPTION).replace('\n', ' ').ifBlank { "（无描述）" })
                append('\n')
            }
        }
    }
}
