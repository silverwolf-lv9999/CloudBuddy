import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * 正式签名的凭据只从 local.properties 读（该文件不入库）。
 *
 * 缺这个文件时 release 会退化成未签名产物，`scripts/verify.sh` 的签名校验会失败，
 * 这是有意的：宁可构建报错，也不要悄悄发布一个用 debug key 签的包。
 */
val signingProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val hasReleaseKey = signingProps.getProperty("release.storeFile")?.isNotBlank() == true

android {
    // namespace 决定 R / BuildConfig 的包路径，和安装身份无关：
    // 保持不动，Kotlin 包结构一行都不用改。
    namespace = "cn.cloudbuddy.app"
    compileSdk = 35

    defaultConfig {
        // 默认身份就是上游 CloudBuddy 的包名：基础版与完整版都用它，
        // 这样两个档位可以互相覆盖安装、共用同一份登录态与设置。
        // 只有共存版（flavor `plus`）在它后面加 .plus 后缀。
        // FileProvider 的 authorities 走 ${applicationId}.fileprovider，
        // 文件下载处的 authority 走 context.packageName，两者都会自动跟随。
        applicationId = "cn.cloudbuddy.app"
        minSdk = 26
        targetSdk = 35
        // 版本号接云端仓库（origin/main 是 1.4.11 / 35）：下一个就是 1.4.12 / 36。
        // 之前 1.5.x–1.7.0 是本地测试线，未对外发布，那些号一律不占位。
        versionCode = 36
        versionName = "1.4.12"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("release.storeFile"))
                storePassword = signingProps.getProperty("release.storePassword")
                keyAlias = signingProps.getProperty("release.keyAlias")
                keyPassword = signingProps.getProperty("release.keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else null
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    /**
     * 三个发行包，同一份代码。
     *
     * | flavor | 包名 | 内容 | 与谁共存 |
     * |---|---|---|---|
     * | `base` | `cn.cloudbuddy.app` | 只有云端能力 | 与 `plus` 共存 |
     * | `full` | `cn.cloudbuddy.app` | 云端 + 本机 agent + 第三方 API + 终端 | 与 `plus` 共存 |
     * | `plus` | `cn.cloudbuddy.app.plus` | 同 `full` | 谁都能共存 |
     *
     * `base` 与 `full` 是**同一个应用的两个档位**：包名相同，装哪个都是覆盖，数据原样保留。
     * 想从基础版升到完整版，直接装 `full`；想退回去，再装 `base` 即可，登录态不会丢。
     * 这也是上游 CloudBuddy 的包名，所以这两个包的应用内更新照常跟随上游 Release。
     *
     * `plus` 是给"基础版留着、另外再装一个完整版"的人：包名不同，可以同时装。
     * 它换包名也换了签名来源，因此不跟随上游更新（见 SettingsCommon.FOLLOWS_UPSTREAM）。
     *
     * 三个包的应用名相同，靠包名而不是名字区分，所以这里也不加 versionName 后缀。
     * 做法参照开源阅读 MD3 版的多 flavor 分发。
     */
    flavorDimensions += "edition"
    productFlavors {
        create("base") {
            dimension = "edition"
            buildConfigField("boolean", "EDITION_FULL", "false")
        }
        create("full") {
            dimension = "edition"
            buildConfigField("boolean", "EDITION_FULL", "true")
        }
        create("plus") {
            dimension = "edition"
            applicationIdSuffix = ".plus"
            buildConfigField("boolean", "EDITION_FULL", "true")
        }
    }

    sourceSets {
        // 共存版与完整版是同一套实现：直接挂 full 的源集，不复制第二份代码。
        // 复制一份的后果是改一个 bug 要改两处，第二次总会漏。
        getByName("plus").java.srcDir("src/full/java")
        getByName("testPlus").java.srcDir("src/testFull/java")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // 数据层（思考参数拼装、会话持久化）直接用 kotlinx-serialization 的 JSON。
    // 它原先只是 Koog 的传递依赖，core 版不含 Koog，所以必须显式声明；
    // 版本跟 Koog 的约束一致，避免两个变体拉到不同版本。
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")

    // Koog: agent loop + OpenAI 兼容 model executor。
    // 只引 agents-core（已含 prompt-executor-openai-client），不引 koog-agents 全家桶，
    // 避免把 RAG / embeddings / MCP / tokenizer 一并打进 APK。
    //
    // 必须是 flavor 专属依赖：基础版的要求是「不含本机 agent 框架」，
    // 不是「藏起入口但把库打进去」——依赖图里不出现，进程里就不可能被加载。
    listOf("fullImplementation", "plusImplementation").forEach { conf ->
        add(conf, "ai.koog:agents-core:0.6.0")
        // 把 LLMClient 包装成 PromptExecutor 的官方适配层（MultiLLMProvider 等）。
        // 注意用 prompt-executor-llms 而不是 prompt-executor-llms-all：后者会把
        // Anthropic/Gemini/Ollama 等全部厂商客户端一起打进来。
        add(conf, "ai.koog:prompt-executor-llms:0.6.0")
        // ktor 的 HTTP 引擎需使用方自备（http-client-ktor 只依赖 ktor-client-core）。
        // 用 OkHttp 引擎：复用项目已有的 okhttp 4.12.0，版本完全一致，不引入第二套网络栈。
        // 3.2.3：MCP Kotlin SDK 0.8.1（由 agents-mcp 传递引入）锁的是 3.2.3，
        // ktor 各模块必须同版本，否则运行期会因 ABI 不一致炸在传输层。
        add(conf, "io.ktor:ktor-client-okhttp:3.2.3")
        // SSE 插件本身就在 ktor-client-core 3.x 里（io.ktor.client.plugins.sse），不用单独引。
        // MCP 桥接：把远端 MCP 工具转成 Koog 的 Tool（含 JSON Schema → 工具描述符转换）。
        // 排除 llms-all：它会带进 Anthropic / Gemini / Ollama 等全部厂商客户端，
        // 我们只用 OpenAI 兼容接口，那几个包纯属给 APK 增重。
        add(conf, "ai.koog:agents-mcp:0.6.0") {
            exclude(group = "ai.koog", module = "prompt-executor-llms-all-jvm")
            exclude(group = "ai.koog", module = "prompt-executor-llms-all")
        }
    }

    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // 端到端验证 agent 循环：本地起一个假的 OpenAI 兼容端点，
    // 断言 tool_call 被解析、工具被执行、结果被回喂。仅测试期依赖，不进 APK。
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
