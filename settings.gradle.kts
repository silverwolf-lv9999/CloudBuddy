// GitHub Actions 的 runner 在海外，连国内镜像又慢又容易断；本地构建正相反。
// 用 GITHUB_ACTIONS 区分，避免「CI 上卡在第一个依赖十分钟」。
val ci = System.getenv("GITHUB_ACTIONS") == "true"

pluginManagement {
    repositories {
        mavenLocal()
        if (ci) {
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
        if (ci) {
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
