package cn.cloudbuddy.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 字段形状来自企业聚合市场真响应，2026-11-24 抓包。 */
class SkillMarketParseTest {

    @Test
    fun `企业聚合市场的 items 能解析成市场技能`() {
        val raw = JSONObject(
            """{"code":0,"data":{"total":70656,"page":1,"page_size":20,"items":[{
                "skill_id":"skill_2111988461974986752",
                "item_ref":"skill_2111988461974986752",
                "name":"clouduser-qu-aiwei",
                "display_name":"去AI味",
                "display_name_zh":"去AI味",
                "description_zh":"去除文本中的 AI 写作痕迹",
                "version":"1.2.0",
                "source":"builtin",
                "category_name":"效率工具",
                "use_stat":{"install_count":366783}
            }]}}"""
        )
        val list = parseMarketItems(raw)
        assertEquals(1, list.size)
        val skill = list.first()
        assertEquals("skill_2111988461974986752", skill.id)
        assertEquals("去AI味", skill.title)
        assertEquals("去除文本中的 AI 写作痕迹", skill.description)
        assertEquals("1.2.0", skill.version)
        assertEquals("builtin", skill.source)
        assertFalse(skill.installed)
    }

    @Test
    fun `旧版 skills 字段仍可解析`() {
        val raw = JSONObject(
            """{"data":{"skills":[{"slug":"old-one","name":"old","display_name":"老技能","description":"老结构"}]}}"""
        )
        val list = parseMarketItems(raw)
        assertEquals(1, list.size)
        assertEquals("old-one", list.first().id)
        assertEquals("老技能", list.first().title)
    }

    @Test
    fun `空数据不抛异常`() {
        assertEquals(0, parseMarketItems(JSONObject("""{"data":{"items":[]}}""")).size)
        assertEquals(0, parseMarketItems(JSONObject("""{}""")).size)
    }
}
