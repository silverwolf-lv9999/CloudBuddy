# Copilot 指令

完整约定见仓库根目录的 [AGENTS.md](../AGENTS.md)。这里只放最容易被忽略的硬规则。

## 构建与验证

- Gradle 不在 PATH 里：`/opt/gradle/gradle-8.9/bin/gradle`。
- 所有 Gradle 命令**必须带 `--offline`**，不带会去连镜像并超时。
- 不要升级 AGP / Kotlin / Compose BOM——离线缓存里没有新版本。
- 交付前三件套必须全绿：`:app:assembleDebug`、`:app:testDebugUnitTest`、`:app:lintDebug`（0 error）。

## 交付

- 每次内容变化都要同时升 `app/build.gradle.kts` 里的 `versionCode` 与 `versionName`，不能复用旧号。
- 产物归档到 `dist/CloudBuddy-<versionName>-debug.apk`，并用 `aapt2 dump badging` + `apksigner verify` 校验。
- 同步更新 `README.md` 的产物块与 `docs/FIXLOG.md` 的版本章节。

## 代码

- 协议解析、事件归约写成**纯函数**并配单测，不要塞进 Composable。
- 不要引入 Hilt、不要拆 Gradle 模块（理由见 `docs/ARCHITECTURE.md`）。
- 这个目录不是 git 仓库，不要执行 git 命令或建议开 PR。
