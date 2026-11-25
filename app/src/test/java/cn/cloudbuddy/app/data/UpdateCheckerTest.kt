package cn.cloudbuddy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    /** 1.4.12 起的发布形态：一个 Release 挂三个包。 */
    private val threePackage = """
        {
          "tag_name": "v1.5.0",
          "html_url": "https://github.com/silverwolf-lv9999/CloudBuddy/releases/tag/v1.5.0",
          "assets": [
            {"name": "CloudBuddy-1.5.0-base-release.apk", "browser_download_url": "https://example.com/base.apk"},
            {"name": "CloudBuddy-1.5.0-release.apk", "browser_download_url": "https://example.com/full.apk"},
            {"name": "CloudBuddy-1.5.0-plus-release.apk", "browser_download_url": "https://example.com/plus.apk"},
            {"name": "notes.txt", "browser_download_url": "https://example.com/n.txt"}
          ]
        }
    """.trimIndent()

    /** 三包拆分之前的发布形态：只有一个不带中缀的 APK。 */
    private val legacy = """
        {
          "tag_name": "v1.1.0",
          "name": "CloudBuddy 1.1.0",
          "body": "## 新增\n- 检查更新",
          "html_url": "https://github.com/silverwolf-lv9999/CloudBuddy/releases/tag/v1.1.0",
          "assets": [
            {"name": "CloudBuddy-1.1.0.apk", "browser_download_url": "https://example.com/a.apk"},
            {"name": "notes.txt", "browser_download_url": "https://example.com/n.txt"}
          ]
        }
    """.trimIndent()

    @Test
    fun `三包 release 各档位拿到自己的包`() {
        assertEquals("https://example.com/base.apk", UpdateChecker.parseRelease(threePackage, "base")!!.apkUrl)
        assertEquals("https://example.com/full.apk", UpdateChecker.parseRelease(threePackage, "full")!!.apkUrl)
        assertEquals("https://example.com/plus.apk", UpdateChecker.parseRelease(threePackage, "plus")!!.apkUrl)
    }

    @Test
    fun `base 与 full 互不串包：串了就是静默换档`() {
        val info = UpdateChecker.parseRelease(threePackage, "base")!!
        assertFalse(info.apkUrl!!.contains("plus"))
        assertFalse(info.apkUrl.contains("full.apk"))
    }

    @Test
    fun `旧版单包形态回退到唯一的 apk`() {
        val info = UpdateChecker.parseRelease(legacy)!!
        assertEquals("1.1.0", info.version)
        assertEquals("https://example.com/a.apk", info.apkUrl)
        assertTrue(info.notes.contains("检查更新"))
    }

    @Test
    fun `release 缺自己档位的包时回退为空而不是抓别人的`() {
        val json = """
            {"tag_name":"v1.5.0","html_url":"https://x/y","assets":[
              {"name":"CloudBuddy-1.5.0-release.apk","browser_download_url":"https://example.com/full.apk"},
              {"name":"CloudBuddy-1.5.0-plus-release.apk","browser_download_url":"https://example.com/plus.apk"}
            ]}
        """.trimIndent()
        assertNull(UpdateChecker.parseRelease(json, "base")!!.apkUrl)
        // base 缺位时 full / plus 仍然各取各的，不能被 base 的缺位连坐
        assertEquals("https://example.com/full.apk", UpdateChecker.parseRelease(json, "full")!!.apkUrl)
        assertEquals("https://example.com/plus.apk", UpdateChecker.parseRelease(json, "plus")!!.apkUrl)
    }

    @Test
    fun `没有 apk 资产时回退为空`() {
        val json = """{"tag_name":"v1.0.11","html_url":"https://x/y","assets":[]}"""
        val info = UpdateChecker.parseRelease(json)!!
        assertNull(info.apkUrl)
        assertEquals("1.0.11", info.version)
    }

    @Test
    fun `字段缺失返回 null 而不是抛异常`() {
        assertNull(UpdateChecker.parseRelease("{}"))
        assertNull(UpdateChecker.parseRelease(""))
        assertNull(UpdateChecker.parseRelease("<html>502</html>"))
    }

    @Test
    fun `html_url 缺失时回退到 releases 列表`() {
        val info = UpdateChecker.parseRelease("""{"tag_name":"v2.0.0"}""")!!
        assertEquals(UpdateChecker.RELEASES_URL, info.pageUrl)
    }

    @Test
    fun `selectAsset 直接可测`() {
        val assets = listOf(
            "CloudBuddy-1.5.0-base-release.apk" to "https://example.com/base.apk",
            "CloudBuddy-1.5.0-release.apk" to "https://example.com/full.apk",
            "CloudBuddy-1.5.0-plus-release.apk" to "https://example.com/plus.apk",
        )
        assertEquals("https://example.com/base.apk", UpdateChecker.selectAsset(assets, "base"))
        assertEquals("https://example.com/plus.apk", UpdateChecker.selectAsset(assets, "plus"))
        // full 是「不带中缀」的那个，不是「随便一个」
        assertEquals("https://example.com/full.apk", UpdateChecker.selectAsset(assets, "full"))
        assertNull(UpdateChecker.selectAsset(emptyList(), "full"))
    }

    @Test
    fun `逐段比较数字而不是字符串`() {
        // 字符串比较会认为 "1.0.9" > "1.0.10"，这里必须按数值比
        assertTrue(UpdateChecker.isNewer("1.0.10", "1.0.9"))
        assertTrue(UpdateChecker.isNewer("1.1.0", "1.0.10"))
        assertFalse(UpdateChecker.isNewer("1.0.9", "1.0.10"))
    }

    @Test
    fun `相同版本不算更新`() {
        assertFalse(UpdateChecker.isNewer("1.1.0", "1.1.0"))
        assertFalse(UpdateChecker.isNewer("v1.1.0", "1.1.0"))
        assertEquals(0, UpdateChecker.compare("1.1", "1.1.0"))
    }

    @Test
    fun `缺位按零补齐`() {
        assertTrue(UpdateChecker.isNewer("1.2", "1.1.9"))
        assertFalse(UpdateChecker.isNewer("1.1", "1.1.0"))
    }

    @Test
    fun `预发布版本低于同号正式版`() {
        assertFalse(UpdateChecker.isNewer("1.1.0-beta.1", "1.1.0"))
        assertTrue(UpdateChecker.isNewer("1.1.0", "1.0.10"))
    }
}
