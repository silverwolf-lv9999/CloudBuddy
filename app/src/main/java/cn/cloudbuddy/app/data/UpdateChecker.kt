package cn.cloudbuddy.app.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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
    /** 与当前档位匹配的 APK 直链；Release 没有对应资产时为 null，回退到页面地址。 */
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
     * 拉取最新 release 里**属于当前档位**的那个包。仓库还没有 release 时 GitHub
     * 返回 404，这里当成「没有可用更新」。
     *
     * @param edition 当前包的档位（`base` / `full` / `plus`，取自 BuildConfig.EDITION_NAME）。
     * @throws java.io.IOException 网络不通；调用方据此区分「检查失败」与「已是最新」。
     */
    fun fetchLatest(edition: String = "full"): ReleaseInfo? {
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
            return parseRelease(body, edition)
        }
    }

    /** 解析 `releases/latest` 的响应体；字段缺失或形状怪时返回 null 而不是抛。 */
    fun parseRelease(json: String, edition: String = "full"): ReleaseInfo? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val tag = root.optString("tag_name").trim()
        if (tag.isEmpty()) return null
        val assets = root.optJSONArray("assets")?.let(::assets).orEmpty()
        return ReleaseInfo(
            version = tag.removePrefix("v").removePrefix("V"),
            tag = tag,
            notes = root.optString("body").trim(),
            pageUrl = root.optString("html_url").ifBlank { RELEASES_URL },
            apkUrl = selectAsset(assets, edition)
        )
    }

    /** 资产名 → 下载直链。 */
    private fun assets(arr: JSONArray): List<Pair<String, String>> =
        (0 until arr.length())
            .mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                name to o.optString("browser_download_url")
            }

    /**
     * 三个包的资产命名（见 `scripts/verify.sh`）：基础版 `-base-`、共存版 `-plus-`、
     * 完整版不带中缀（`CloudBuddy-1.5.0-release.apk`）。
     *
     * 选错包的后果不只是下错文件：base 用户拿到完整版的包虽然能覆盖安装（同包名），
     * 但「静默升级成另一个档位」不是用户点「更新」时期待的事；反过来 full 拿到 base
     * 的包会把功能降没。所以这里按档位严格匹配，匹配不到宁可给 null（回退到
     * Release 页面让用户自己挑），也不抓第一个 `.apk` 糊弄。
     *
     * 老发布（三包拆分之前）只有一个不带中缀的 APK，资产里没有任何 `-base-`/`-plus-`
     * 标记时按旧形态处理：那个包就是唯一选择。
     *
     * @param assets 资产名 → 下载直链
     * @return 匹配档位的 APK 直链
     */
    fun selectAsset(assets: List<Pair<String, String>>, edition: String): String? {
        val apks = assets.filter { it.first.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null
        val splitRelease = apks.any { it.first.contains("-base-") || it.first.contains("-plus-") }
        if (!splitRelease) return apks.first().second
        return when (edition) {
            "base" -> apks.firstOrNull { it.first.contains("-base-") }?.second
            "plus" -> apks.firstOrNull { it.first.contains("-plus-") }?.second
            else -> apks.firstOrNull {
                !it.first.contains("-base-") && !it.first.contains("-plus-")
            }?.second
        }
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
