package cn.cloudbuddy.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 工具调用的**类别**：只决定图标与摘要里的简称，不参与状态判断。
 *
 * 为什么不落库：ACP 的 `tool_call` 虽然有 `kind` 字段，但云端实际下发的是
 * 中文标题（`运行 npm install` / `读取文件` / `生成图表`），`kind` 时常缺失；
 * 落库就要动 `StoredMessage` 的兼容，而按标题归类对历史消息同样有效——
 * 老会话翻回去也有图标，不必重拉。
 *
 * 状态（跑着 / 完成 / 失败）仍由 [cn.cloudbuddy.app.data.ToolState] 决定颜色和尾部标记，
 * 两者正交：图标回答「在干什么」，颜色回答「干到哪了」。
 *
 * 图标一律取 MD3 图标集（`androidx.compose.material3` + `material-icons-extended`）里的
 * `ImageVector`，与项目其余 146 处 `Icons.Filled.*` 同风格：矢量绘制、跟随 tint、
 * 按 dp 缩放。**不用 emoji / 字符号**——那些在不同设备字体下会变形、无法着色、
 * 也跟不上字号与深色模式。
 */
internal enum class ToolKind(val short: String) {
    READ("读"),
    WRITE("写"),
    EDIT("改"),
    EXEC("运行"),
    SEARCH("搜"),
    NET("联网"),
    DELETE("删"),
    THINK("想"),
    OTHER("工具");

    val icon: ImageVector
        get() = when (this) {
            READ -> Icons.Filled.MenuBook
            WRITE -> Icons.Filled.NoteAdd
            EDIT -> Icons.Filled.Edit
            EXEC -> Icons.Filled.Terminal
            SEARCH -> Icons.Filled.Search
            NET -> Icons.Filled.Language
            DELETE -> Icons.Filled.Delete
            THINK -> Icons.Filled.Psychology
            OTHER -> Icons.Filled.Build
        }
}

/**
 * 关键词表：按顺序匹配，先命中先算。
 *
 * 顺序有讲究——`删除` 要先于 `改`（「删除旧文件」不该算修改），
 * `搜索` 要先于 `读`（`grep 查找` 比「打开」更具体），`运行` 要先于 `写`
 * （「运行 npm install」不是写文件）。
 */
private val RULES: List<Pair<ToolKind, List<String>>> = listOf(
    ToolKind.DELETE to listOf("删除", "移除", "清空", "delete", "remove", "unlink", " rm ", "rm -"),
    ToolKind.SEARCH to listOf("搜索", "查找", "检索", "grep", "glob", "find ", "search", "query", "rg "),
    ToolKind.NET to listOf("联网", "抓取", "下载", "请求", "网页", "fetch", "http", "url", "curl", "browser"),
    ToolKind.EXEC to listOf(
        "运行", "执行", "命令", "终端", "编译", "构建", "测试", "安装",
        "bash", "shell", "exec", "command", "terminal", "npm", "pnpm", "yarn",
        "gradle", "python", "node ", "git ", "sh -", "curl "
    ),
    ToolKind.EDIT to listOf("修改", "编辑", "替换", "重命名", "移动", "改写", "edit", "replace", "patch", "update", "rename", "move"),
    ToolKind.WRITE to listOf("写入", "新建", "创建", "保存", "生成", "追加", "write", "create", "save", "mkdir", "touch"),
    ToolKind.READ to listOf("读取", "查看", "打开", "阅读", "列出", "read", "cat ", "view", "open", "list", "ls "),
    ToolKind.THINK to listOf("思考", "推理", "分析", "think", "reason"),
)

/** 从工具标题（云端下发的中文动宾短语）推断类别；认不出来就当普通工具。 */
internal fun toolKindOf(title: String): ToolKind {
    if (title.isBlank()) return ToolKind.OTHER
    val lower = title.lowercase()
    RULES.forEach { (kind, keys) ->
        if (keys.any { lower.contains(it) }) return kind
    }
    return ToolKind.OTHER
}
