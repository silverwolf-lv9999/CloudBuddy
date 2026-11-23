package cn.cloudbuddy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    private val sample = """
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
    fun `解析 release 去掉 v 前缀并挑出 apk`() {
        val info = UpdateChecker.parseRelease(sample)!!
        assertEquals("1.1.0", info.version)
        assertEquals("v1.1.0", info.tag)
        assertEquals("https://example.com/a.apk", info.apkUrl)
        assertTrue(info.notes.contains("检查更新"))
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
