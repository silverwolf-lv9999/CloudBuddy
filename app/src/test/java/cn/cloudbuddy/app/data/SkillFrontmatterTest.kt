package cn.cloudbuddy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SKILL.md 的头部解析。
 *
 * 这一层出错的表现很隐蔽：技能能装上、列表里也有，但 description 读没了，
 * 于是模型永远不知道该在什么时候用它——界面上看不出任何异常。所以逐种写法都锁一遍。
 */
class SkillFrontmatterTest {

    @Test
    fun `标准 frontmatter 能读出名字与描述`() {
        val text = """
            ---
            name: code-review
            description: 按团队规范审阅改动
            ---

            # 步骤
            先跑测试。
        """.trimIndent()
        val parsed = SkillFrontmatter.parse(text)
        assertEquals("code-review", parsed.name)
        assertEquals("按团队规范审阅改动", parsed.description)
        assertTrue(parsed.body.startsWith("# 步骤"))
    }

    @Test
    fun `没有 frontmatter 时整段都是正文`() {
        val parsed = SkillFrontmatter.parse("# 随便写点什么\n第二行")
        assertEquals("", parsed.name)
        assertEquals("", parsed.description)
        assertEquals("# 随便写点什么\n第二行", parsed.body)
    }

    @Test
    fun `值带引号要去掉引号`() {
        val parsed = SkillFrontmatter.parse("---\nname: \"css\"\ndescription: '写样式前先看设计稿'\n---\n正文")
        assertEquals("css", parsed.name)
        assertEquals("写样式前先看设计稿", parsed.description)
    }

    @Test
    fun `折行块标量按空格拼成一句`() {
        val text = """
            ---
            name: review
            description: >-
              审阅改动时先看
              diff，再决定是否跑全量测试
            ---
            正文
        """.trimIndent()
        val parsed = SkillFrontmatter.parse(text)
        assertEquals("审阅改动时先看 diff，再决定是否跑全量测试", parsed.description)
    }

    @Test
    fun `竖线块标量保留换行`() {
        val text = """
            ---
            name: review
            description: |
              第一行
              第二行
            ---
            正文
        """.trimIndent()
        assertEquals("第一行\n第二行", SkillFrontmatter.parse(text).description)
    }

    @Test
    fun `CRLF 换行也能解析`() {
        val text = "---\r\nname: win\r\ndescription: 从 Windows 复制来的\r\n---\r\n正文"
        val parsed = SkillFrontmatter.parse(text)
        assertEquals("win", parsed.name)
        assertEquals("从 Windows 复制来的", parsed.description)
    }

    @Test
    fun `compose 出来的文本能被 parse 原样读回`() {
        val text = SkillFrontmatter.compose("build", "构建与运行测试", "1. ./gradlew assembleDebug")
        val parsed = SkillFrontmatter.parse(text)
        assertEquals("build", parsed.name)
        assertEquals("构建与运行测试", parsed.description)
        assertEquals("1. ./gradlew assembleDebug", parsed.body)
    }

    @Test
    fun `坏掉的头部不抛异常，退化成纯正文`() {
        // 只有开头没有结尾的 ---
        val parsed = SkillFrontmatter.parse("---\nname: x\n正文没有结束标记")
        assertEquals("", parsed.name)
        assertTrue(parsed.body.contains("正文没有结束标记"))
    }
}
