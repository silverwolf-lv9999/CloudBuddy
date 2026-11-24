package cn.cloudbuddy.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 字段形状全部来自真账号抓到的原始响应，不是照着代码猜的。 */
class ConnectorParseTest {

    @Test
    fun `我的连接器读两个状态位`() {
        val raw = JSONObject(
            """{"code":0,"data":{"connectors":[{
                "user_id":"u-1","name":"github","connect_status":1,"active_status":1,
                "repos":"","display_name":"github","description":"github",
                "oauth_app_name":"codebuddy-connector","connect_mode":"redirect"
            }]}}"""
        )
        val list = parseConnectors(raw)
        assertEquals(1, list.size)
        val github = list.first()
        assertEquals("github", github.name)
        assertEquals("github", github.title)
        assertTrue(github.bound)
        assertTrue(github.active)
        // 解析阶段先按绑过算；mine() 会再用 auth-status 覆盖，失败时才退回这个值。
        assertTrue(github.authOk)
        assertTrue(github.subtitle.contains("已连接"))
    }

    @Test
    fun `repos 既可能是数组也可能是逗号串`() {
        val raw = JSONObject(
            """{"data":{"connectors":[
                {"name":"a","connect_status":1,"active_status":0,"repos":"org/a,org/b"},
                {"name":"b","connect_status":0,"active_status":0,"repos":["org/c"]}
            ]}}"""
        )
        val list = parseConnectors(raw)
        assertEquals(listOf("org/a", "org/b"), list[0].repos)
        assertEquals(listOf("org/c"), list[1].repos)
        assertFalse(list[0].ready)
    }

    @Test
    fun `市场清单不把可安装误判成已绑定`() {
        val raw = JSONObject(
            """{"code":0,"data":{"list":[
                {"id":7,"name":"github","auth_type":"oauth2_code","status":"active",
                 "data":{"display_name":"GitHub MCP","description":"GitHub Copilot MCP Server"},
                 "mcp":{"transport":"http","auth_mode":"gateway"},"is_connected":false},
                {"id":8,"name":"disabled-one","status":"disabled","data":{"display_name":"关掉的"}}
            ]}}"""
        )
        val list = parseConnectors(raw, catalog = true)
        assertEquals(1, list.size)
        assertEquals("GitHub MCP", list[0].title)
        assertEquals("oauth2_code", list[0].authType)
        assertFalse(list[0].bound)
        assertFalse(list[0].active)
    }

    @Test
    fun `授权状态按 statuses 里的对象取`() {
        val raw = JSONObject(
            """{"code":0,"data":{"statuses":{
                "github":{"status":"connected","connected_at":1795476286},
                "figma":{"status":"expired"}
            }}}"""
        )
        val map = parseAuthStatuses(raw)
        assertTrue(map["github"] == true)
        assertTrue(map["figma"] == false)
    }

    @Test
    fun `授权动作识别跳转与已连接`() {
        val redirect = parseAuth(
            JSONObject(
                """{"code":0,"data":{"request_id":"r1","auth_type":"oauth",
                   "next_action":"redirect","authorize_url":"https://github.com/login/oauth/authorize"}}"""
            )
        )
        assertEquals("redirect", redirect.action)
        assertEquals("https://github.com/login/oauth/authorize", redirect.url)

        val connected = parseAuth(JSONObject("""{"data":{"next_action":"connected"}}"""))
        assertEquals("connected", connected.action)
    }
}
