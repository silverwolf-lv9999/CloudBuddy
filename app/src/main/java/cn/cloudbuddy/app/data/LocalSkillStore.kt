package cn.cloudbuddy.app.data

import android.content.SharedPreferences
import org.json.JSONArray
import java.io.File

/**
 * 本机技能库：`<filesDir>/skills/<id>/SKILL.md`。
 *
 * 存储形态照抄 Agent Skills 规范（也是 RikkaHub 的做法）：一条技能一个目录，
 * 说明放 `SKILL.md`，补充材料放同目录下的相对路径。**不塞进 SharedPreferences**——
 * 技能正文动辄几十 KB，塞进 XML 会让每次 `getSharedPreferences` 都变慢，而且
 * 模型按需读取同目录文件的能力就没了。
 *
 * 与云端技能的关系：这是两套。云端技能（[SkillApi]）是账号资产、由云端 agent 执行；
 * 这里的技能只喂给本机会话的本机 agent。所以同名不冲突，也不会互相覆盖。
 */
class LocalSkillStore(
    private val root: File,
    private val prefs: SharedPreferences,
) {

    /**
     * 目录内容的内存缓存。
     *
     * 不是过早优化：`enabled()` 在**每条消息**发出去之前都会被调一次（系统提示要拼技能清单），
     * 没缓存就是每条消息都把 N 个 SKILL.md 读一遍。写操作（存/删）自己让缓存失效。
     */
    @Volatile
    private var cache: List<LocalSkill>? = null

    /** 目录名即 id；只认含 SKILL.md 的子目录。 */
    fun list(): List<LocalSkill> {
        cache?.let { return it }
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        val on = enabledIds()
        val items = dirs.mapNotNull { dir -> readMeta(dir)?.copy(enabled = dir.name in on) }
            .sortedBy { it.id.lowercase() }
        cache = items
        return items
    }

    fun enabledIds(): Set<String> {
        val raw = prefs.getString(KEY_ENABLED, null) ?: return emptySet()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptySet()
        return (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.toSet()
    }

    fun isEnabled(id: String): Boolean = id in enabledIds()

    fun setEnabled(id: String, enabled: Boolean) {
        val current = enabledIds().toMutableSet()
        if (enabled) current.add(id) else current.remove(id)
        saveEnabled(current)
        // 启停变了要作废目录缓存，否则列表上那个开关要等下次启动才反映出来。
        cache = null
    }

    /** 只留已启用、且目录还在的。系统提示按这个列表注入。 */
    fun enabled(): List<LocalSkill> = list().filter { it.enabled }

    /** 正文（已剥掉 frontmatter）；不存在返回 null。 */
    fun body(id: String): String? {
        val file = entryFile(id)?.takeIf { it.isFile } ?: return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return SkillFrontmatter.parse(text).body
    }

    /**
     * 读技能目录里的补充文件（渐进披露的第二步）。
     *
     * 路径必须落在技能目录内：`../../` 这种写法一律拒绝——技能是可以从外部导入的，
     * 放行等于让一个技能读到整个 app 私有目录。
     */
    fun readRelative(id: String, relative: String): File? {
        if (relative.isBlank()) return null
        // 目录不存在也要拒绝：canonicalFile 对不存在的路径照样能算，
        // <root>/nope/SKILL.md 会通过「在 base 之下」的检查。
        val dir = dirOf(id)?.takeIf { it.isDirectory } ?: return null
        return runCatching {
            val target = File(dir, relative).canonicalFile
            val base = dir.canonicalFile
            if (target.path == base.path || target.path.startsWith(base.path + File.separator)) target else null
        }.getOrNull()
    }

    /**
     * 新建或覆盖一条技能。返回错误文案，成功为 null。
     *
     * 名字直接当目录名用，所以校验在这里做：带 `/`、`..`、空白的名字会在文件系统上
     * 跑到别的地方去。
     */
    fun save(id: String, content: String): String? {
        val clean = id.trim()
        val bad = validateId(clean)
        if (bad != null) return bad
        if (content.isBlank()) return "内容不能为空"
        val dir = File(root, clean)
        if (!dir.exists() && !dir.mkdirs()) return "无法创建目录"
        return runCatching {
            File(dir, ENTRY).writeText(content.trimEnd() + "\n")
            cache = null
            // 新技能默认启用：导入完发现「装了但没生效」比多注入几行更让人困惑。
            setEnabled(clean, true)
            null
        }.getOrElse { it.message ?: "写入失败" }
    }

    /** 导入一份可能是「只有正文」的 SKILL.md：缺 name / description 时用文件名补。 */
    fun import(fileName: String, content: String): String? {
        val parsed = SkillFrontmatter.parse(content)
        val fallbackId = fileName.substringBeforeLast('.').trim().ifBlank { "skill" }
        val id = parsed.name.ifBlank { fallbackId }
        val description = parsed.description.ifBlank { fallbackDescription(parsed.body) }
        val text = if (parsed.name.isNotBlank() || parsed.description.isNotBlank()) {
            content
        } else {
            SkillFrontmatter.compose(id, description, parsed.body)
        }
        return save(id, text)
    }

    fun delete(id: String) {
        val dir = dirOf(id) ?: return
        runCatching { dir.deleteRecursively() }
        cache = null
        setEnabled(id, false)
    }

    /** 更新启用集合时顺手丢掉已经不存在的 id，避免集合无限长。 */
    private fun saveEnabled(ids: Set<String>) {
        val existing = list().map { it.id }.toSet()
        val array = JSONArray()
        ids.filter { it in existing }.forEach(array::put)
        prefs.edit().putString(KEY_ENABLED, array.toString()).apply()
    }

    private fun readMeta(dir: File): LocalSkill? {
        val file = File(dir, ENTRY).takeIf { it.isFile } ?: return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val parsed = SkillFrontmatter.parse(text)
        return LocalSkill(
            id = dir.name,
            title = parsed.name.ifBlank { dir.name },
            description = parsed.description.ifBlank { fallbackDescription(parsed.body) },
        )
    }

    /**
     * 没有 description 的技能等于对模型隐身（系统提示只放名字+描述），
     * 用正文第一行兜底；顺手剥掉 Markdown 标题的 `#` 前缀，清单里看着才不像命令。
     */
    private fun fallbackDescription(body: String): String {
        val first = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return ""
        return first.trimStart('#').trim().take(80)
    }

    private fun dirOf(id: String): File? {
        if (validateId(id) != null) return null
        return File(root, id)
    }

    private fun entryFile(id: String): File? = dirOf(id)?.let { File(it, ENTRY) }

    companion object {
        const val ENTRY = "SKILL.md"
        private const val KEY_ENABLED = "skill.enabled.v1"

        val NAME_RULE = Regex("""^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$""")

        /** 目录名规则：字母数字开头，最长 64，不含路径分隔符。 */
        fun validateId(id: String): String? = when {
            id.isBlank() -> "名称不能为空"
            !NAME_RULE.matches(id) -> "名称只能用字母、数字、点、下划线和短横线，且不能以符号开头"
            else -> null
        }
    }
}
