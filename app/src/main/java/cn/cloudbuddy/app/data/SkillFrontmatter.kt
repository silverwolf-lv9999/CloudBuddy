package cn.cloudbuddy.app.data

/**
 * SKILL.md 的 frontmatter 与正文拆解。
 *
 * 只认 `key: value` 与块标量（`|` / `>` 及其 `-` 变体）这几种写法，**不引入 YAML 库**：
 * 我们要的字段只有 name / description，而依赖一个完整 YAML 解析器（snakeyaml 400KB+）
 * 只为读两行头信息不划算。代价是遇到复杂结构（列表、嵌套映射）时返回空——技能正文
 * 本来就不该靠 frontmatter 传数据。
 *
 * 拆成纯函数是为了能单测：解析错了的表现是「技能装上了但模型看不见描述」，
 * 这种问题在界面上看不出来，只能靠测试锁。
 */
object SkillFrontmatter {

    /** 一条技能的头部信息 + 正文。 */
    data class Parsed(val values: Map<String, String>, val body: String) {
        val name: String get() = values["name"].orEmpty()
        val description: String get() = values["description"].orEmpty()

        companion object {
            val EMPTY = Parsed(emptyMap(), "")
        }
    }

    private val END = Regex("""\r?\n---(?:\r?\n|$)""")

    fun parse(text: String): Parsed {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---")) return Parsed(emptyMap(), normalized.trim())
        val end = END.find(normalized, startIndex = 3) ?: return Parsed(emptyMap(), normalized.trim())
        val header = normalized.substring(3, end.range.first)
        val body = normalized.substring(end.range.last + 1).trimStart('\n')
        val values = runCatching { readScalars(header) }.getOrDefault(emptyMap())
        return Parsed(values, body.trim())
    }

    /** 拼回 SKILL.md 文本。导入时把用户只给的正文补上 name/description。 */
    fun compose(name: String, description: String, body: String): String = buildString {
        append("---\n")
        append("name: ").append(name.replace("\n", " ")).append('\n')
        if (description.isNotBlank()) {
            append("description: ").append(description.replace("\n", " ")).append('\n')
        }
        append("---\n\n")
        append(body.trim()).append('\n')
    }

    /**
     * 逐行读标量。块标量（`description: >-` 后跟缩进行）按缩进收集，
     * 折行符按 `>` 的规则换成空格、按 `|` 保留换行。
     */
    private fun readScalars(header: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val lines = header.split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            i++
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val colon = trimmed.indexOf(':')
            if (colon <= 0) continue
            val key = trimmed.substring(0, colon).trim()
            var value = trimmed.substring(colon + 1).trim()
            if (value.isEmpty()) {
                // 可能是块标量，也可能只是个空值。
                val block = ArrayList<String>()
                while (i < lines.size && (lines[i].isBlank() || lines[i].startsWith(" ") || lines[i].startsWith("\t"))) {
                    block.add(lines[i].trim())
                    i++
                }
                val joined = block.joinToString("\n").trim()
                if (joined.isNotEmpty()) out[key] = joined
                continue
            }
            if (value == "|" || value == "|-" || value == "|+" || value == ">" || value == ">-" || value == ">+") {
                val folded = value.startsWith(">")
                val keepTrailing = value.endsWith("+")
                val block = ArrayList<String>()
                while (i < lines.size && (lines[i].isBlank() || lines[i].startsWith(" ") || lines[i].startsWith("\t"))) {
                    block.add(lines[i].trim())
                    i++
                }
                var text = if (folded) block.joinToString(" ") else block.joinToString("\n")
                text = text.trim()
                if (keepTrailing) text += "\n"
                if (text.isNotEmpty()) out[key] = text
                continue
            }
            // 去掉成对引号：`name: "x"` 与 `name: 'x'` 都是常见写法。
            if (value.length >= 2 &&
                ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))
            ) {
                value = value.substring(1, value.length - 1)
            }
            out[key] = value
        }
        return out
    }
}

/**
 * 一条本机技能。
 *
 * 目录形态与 Agent Skills 规范一致：`<root>/<name>/SKILL.md`，正文里可以用相对路径
 * 指向同目录下的补充文件（`references/xxx.md`），模型按需读取——这就是「渐进披露」：
 * 系统提示里只放名字和一句话描述，几十条技能也不会把上下文塞爆。
 */
data class LocalSkill(
    /** 目录名，也是 `use_skill` 的参数值。 */
    val id: String,
    val title: String,
    val description: String,
    /** 是否启用。停用的技能不进系统提示，也不许 `use_skill` 读。 */
    val enabled: Boolean = true,
) {
    val displayName: String get() = title.ifBlank { id }
}
