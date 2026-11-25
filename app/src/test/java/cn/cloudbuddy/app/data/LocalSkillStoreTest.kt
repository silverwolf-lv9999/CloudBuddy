package cn.cloudbuddy.app.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 本机技能库的读写。
 *
 * 用真实的临时目录而不是 mock 文件系统：这一层的价值就在于「文件真的被写进去了」，
 * mock 掉 File 等于把被测的东西换掉了。
 */
class LocalSkillStoreTest {

    private val root = File.createTempFile("skills", "").let { f ->
        f.delete()
        f.mkdirs()
        f
    }
    private val prefs = FakePrefs()
    private val store = LocalSkillStore(root, prefs)

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun skill(id: String, description: String = "描述") =
        SkillFrontmatter.compose(id, description, "# $id\n正文")

    @Test
    fun `保存后能列出，且默认启用`() {
        assertNull(store.save("review", skill("review", "审阅改动")))
        val list = store.list()
        assertEquals(1, list.size)
        assertEquals("review", list[0].id)
        assertEquals("审阅改动", list[0].description)
        assertTrue(list[0].enabled)
        assertTrue(File(root, "review/SKILL.md").isFile)
    }

    @Test
    fun `正文读出时不含 frontmatter`() {
        store.save("review", skill("review", "审阅改动"))
        val body = store.body("review")
        assertNotNull(body)
        // 正文里不该再出现头部字段，否则模型会把 YAML 当指令读进去。
        assertFalse(body!!.contains("description:"))
        assertTrue(body.contains("# review"))
    }

    @Test
    fun `停用后不进 enabled，但仍然在列表里`() {
        store.save("review", skill("review"))
        store.setEnabled("review", false)
        assertTrue(store.enabled().isEmpty())
        assertEquals(1, store.list().size)
        assertFalse(store.list()[0].enabled)
    }

    @Test
    fun `删除会把目录和启用记录一起清掉`() {
        store.save("review", skill("review"))
        store.delete("review")
        assertTrue(store.list().isEmpty())
        assertFalse(File(root, "review").exists())
        assertTrue(store.enabledIds().isEmpty())
    }

    @Test
    fun `名字里带路径分隔符会被拒绝`() {
        assertEquals("名称只能用字母、数字、点、下划线和短横线，且不能以符号开头", store.save("../evil", skill("x")))
        assertFalse(File(root.parentFile, "evil").exists())
    }

    @Test
    fun `空内容会被拒绝`() {
        assertNotNull(store.save("review", "   "))
    }

    @Test
    fun `导入没有 frontmatter 的文件时用文件名当名字，正文首行当描述`() {
        assertNull(store.import("deploy.md", "# 部署\n先跑 release 构建"))
        val list = store.list()
        assertEquals(1, list.size)
        assertEquals("deploy", list[0].id)
        assertEquals("部署", list[0].description)
    }

    @Test
    fun `导入带 frontmatter 的文件时保留原头部`() {
        val text = SkillFrontmatter.compose("k8s", "集群操作规范", "只允许读操作")
        assertNull(store.import("whatever.md", text))
        val list = store.list()
        assertEquals("k8s", list[0].id)
        assertEquals("集群操作规范", list[0].description)
    }

    @Test
    fun `相对路径不能逃出技能目录`() {
        store.save("review", skill("review"))
        assertNull(store.readRelative("review", "../../../etc/passwd"))
        assertNotNull(store.readRelative("review", "SKILL.md"))
        // 目录不存在时也不该抛
        assertNull(store.readRelative("nope", "SKILL.md"))
    }

    @Test
    fun `目录里没有 SKILLmd 的子目录不算技能`() {
        File(root, "half").mkdirs()
        File(root, "half/README.md").writeText("只是说明")
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `缓存会在写入后失效`() {
        store.save("a", skill("a"))
        assertEquals(1, store.list().size)
        store.save("b", skill("b"))
        // 没有失效就会一直返回只有 a 的那份旧列表
        assertEquals(2, store.list().size)
    }

    @Test
    fun `修改内容后重新读到的正文是新写的`() {
        store.save("a", skill("a", "旧描述"))
        assertEquals("旧描述", store.list()[0].description)
        store.save("a", skill("a", "新描述"))
        assertEquals("新描述", store.list()[0].description)
        assertTrue(store.body("a")!!.contains("# a"))
    }
}
