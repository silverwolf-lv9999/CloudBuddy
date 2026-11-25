package cn.cloudbuddy.app.data

import cn.cloudbuddy.app.BuildConfig

/**
 * 当前安装的是哪一种档位。
 *
 * 三个包同一份代码：`full` 与 `plus` 含本机 agent（Koog）、第三方 OpenAI 兼容端点与终端命令行，
 * `base` 三者都不在依赖图里。判断依据是 flavor 生成的 BuildConfig 常量，
 * 不是运行时探测——base 包里根本没有那些类，探测也无从谈起。
 *
 * 只在**入口**处用它（要不要显示这个按钮/这一组设置），不要用它包住业务分支：
 * 真正的隔离靠源集（见 [AgentRuntime]），这里多一个 if 只是让用户看不见走不通的入口。
 */
object Edition {
    /** true = 完整版（full / plus）；false = 基础版（base）。 */
    val full: Boolean = BuildConfig.EDITION_FULL

    /** 设置页里对缺失能力的说明。 */
    const val CORE_ONLY_CLOUD =
        "基础版只连接官方云端模型：不含本机 agent、第三方 API 与终端命令行。" +
            "需要这些能力时，装上同版本的完整版即可覆盖升级，登录态与设置都会保留。"
}
