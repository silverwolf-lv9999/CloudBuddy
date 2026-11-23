package cn.cloudbuddy.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 检查 GitHub Releases 上是否有比当前更新的版本。
 *
 * 只有这一条外部网络路径：直连 `api.github.com`。国内网络下它时通时不通，
 * 所以调用方要把「连不上」和「已是最新」区分开——前者应该提示「检查失败」，
 * 不能让用户以为自己用的是最新版。
 */
data class ReleaseInfo(
    /** 去掉 `v` 前缀的版本号，如 `1.1.0`。 */
    val version: String,
    val tag: String,
    val notes: String,
    val pageUrl: String,
    /** Release 里附带的 APK 直链；没有资产时为 null，回退到页面地址。 */
    val apkUrl: String?
)

object UpdateChecker {

    const val REPO_SLUG = "silverwolf-lv9999/CloudBuddy"
    const val REPO_URL = "https://github.com/$REPO_SLUG"
    const val RELEASES_URL = "$REPO_URL/releases"
    private const val LATEST_API = "https://api.github.com/repos/$REPO_SLUG/releases/latest"

    /** 检查更新专用的客户端：短超时，失败就是失败，不拖住界面。 */
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 拉取最新 release。仓库还没有 release 时 GitHub 返回 404，这里当成「没有可用更新」。
     *
     * @throws java.io.IOException 网络不通；调用方据此区分「检查失败」与「已是最新」。
     */
    fun fetchLatest(): ReleaseInfo? {
        val request = Request.Builder()
            .url(LATEST_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CloudBuddy-Android")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) {
                throw java.io.IOException("GitHub 返回 HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            return parseRelease(body)
        }
    }

    /** 解析 `releases/latest` 的响应体；字段缺失或形状怪时返回 null 而不是抛。 */
    fun parseRelease(json: String): ReleaseInfo? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val tag = root.optString("tag_name").trim()
        if (tag.isEmpty()) return null
        val apk = root.optJSONArray("assets")?.let { assets ->
            (0 until assets.length())
                .mapNotNull { assets.optJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?.optString("browser_download_url")
                ?.takeIf { it.isNotBlank() }
        }
        return ReleaseInfo(
            version = tag.removePrefix("v").removePrefix("V"),
            tag = tag,
            notes = root.optString("body").trim(),
            pageUrl = root.optString("html_url").ifBlank { RELEASES_URL },
            apkUrl = apk
        )
    }

    /**
     * 语义化版本比较：逐段比数字，缺位按 0 处理（`1.1` 等价 `1.1.0`）。
     *
     * 预发布后缀（`-beta.1`）一律按「小于同号正式版」处理——这里只用于提示更新，
     * 精确排序不是目标，别把它当成完整的 semver 实现。
     */
    fun compare(latest: String, current: String): Int {
        val a = numericParts(latest)
        val b = numericParts(current)
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    fun isNewer(latest: String, current: String): Boolean = compare(latest, current) > 0

    private fun numericParts(version: String): List<Int> =
        version.trim()
            .removePrefix("v").removePrefix("V")
            .substringBefore('-').substringBefore('+')
            .split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
}
