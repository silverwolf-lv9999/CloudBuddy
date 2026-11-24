// GitHub Actions 的 runner 在海外，连国内镜像又慢又容易断；本地构建正相反。
// 用 GITHUB_ACTIONS 区分，避免「CI 上卡在第一个依赖十分钟」。
//
// 注意：pluginManagement / dependencyResolutionManagement 是独立编译的脚本块，
// 看不到本文件里 `val` 定义的变量（`Unresolved reference: ci`），只能用表达式。
pluginManagement {
    repositories {
        mavenLocal()
        if (System.getenv("GITHUB_ACTIONS") == "true") {
            gradlePluginPortal()
            google()
            mavenCentral()
        } else {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
            maven("https://repo.huaweicloud.com/repository/maven")
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        if (System.getenv("GITHUB_ACTIONS") == "true") {
            google()
            mavenCentral()
        } else {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
            maven("https://repo.huaweicloud.com/repository/maven")
        }
    }
}

rootProject.name = "CloudBuddy"
include(":app")
