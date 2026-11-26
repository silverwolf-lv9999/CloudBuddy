package cn.cloudbuddy.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工具类别只按标题关键词推断，规则很容易被后来的改动踩坏，所以逐类钉一条。
 * 标题的真实形态见 ACP `tool_call.title`：云端下发的是中文动宾短语
 * （`运行 npm install` / `读取文件` / `生成图表`），偶尔是纯文件名。
 */
class ToolKindTest {

    @Test
    fun `reads are not confused with searches`() {
        assertEquals(ToolKind.READ, toolKindOf("读取文件 config.json"))
        assertEquals(ToolKind.SEARCH, toolKindOf("搜索仓库里的 TODO"))
    }

    @Test
    fun `delete wins over edit`() {
        // 「删除旧文件」里也有「改」的近义词风险，规则顺序保证删优先。
        assertEquals(ToolKind.DELETE, toolKindOf("删除旧文件"))
        assertEquals(ToolKind.EDIT, toolKindOf("修改 config.json"))
    }

    @Test
    fun `command execution is exec even when the command name looks like a file op`() {
        assertEquals(ToolKind.EXEC, toolKindOf("运行 npm install"))
        assertEquals(ToolKind.EXEC, toolKindOf("执行测试"))
        assertEquals(ToolKind.WRITE, toolKindOf("生成图表"))
    }

    @Test
    fun `network beats exec for curl`() {
        // curl 同时在两张表里，NET 先命中：用户关心的是「联网了」而不是「跑了命令」。
        assertEquals(ToolKind.NET, toolKindOf("curl https://example.com"))
    }

    @Test
    fun `unknown titles fall back to other`() {
        assertEquals(ToolKind.OTHER, toolKindOf(""))
        assertEquals(ToolKind.OTHER, toolKindOf("report.md"))
        assertEquals(ToolKind.OTHER, toolKindOf("AskUserQuestion"))
    }
}
