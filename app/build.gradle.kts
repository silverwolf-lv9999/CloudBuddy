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
        // 与「云伴 WorkBuddy」原版（cn.cloudbuddy.app）共存安装用的独立身份。
        // 改这里就够：FileProvider 的 authorities 走 ${applicationId}.fileprovider，
        // 文件下载处的 authority 走 context.packageName，两者都会自动跟随。
        // 代价是数据不共享——新包是全新的，原版的登录态与设置不会带过来。
        applicationId = "cn.cloudbuddy.app.plus"
        minSdk = 26
        targetSdk = 35
        versionCode = 48
        versionName = "1.6.6"
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

    // Koog: agent loop + OpenAI 兼容 model executor。
    // 只引 agents-core（已含 prompt-executor-openai-client），不引 koog-agents 全家桶，
    // 避免把 RAG / embeddings / MCP / tokenizer 一并打进 APK。
    implementation("ai.koog:agents-core:0.6.0")
    // 把 LLMClient 包装成 PromptExecutor 的官方适配层（MultiLLMProvider 等）。
    // 注意用 prompt-executor-llms 而不是 prompt-executor-llms-all：后者会把
    // Anthropic/Gemini/Ollama 等全部厂商客户端一起打进来。
    implementation("ai.koog:prompt-executor-llms:0.6.0")
    // ktor 的 HTTP 引擎需使用方自备（http-client-ktor 只依赖 ktor-client-core）。
    // 用 OkHttp 引擎：复用项目已有的 okhttp 4.12.0，版本完全一致，不引入第二套网络栈。
    implementation("io.ktor:ktor-client-okhttp:3.2.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // 端到端验证 agent 循环：本地起一个假的 OpenAI 兼容端点，
    // 断言 tool_call 被解析、工具被执行、结果被回喂。仅测试期依赖，不进 APK。
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
