package cn.cloudbuddy.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 回归 1.3.2：OkHttp 不接受 POST + null body，必须在统一 HTTP 层补空 body。 */
class CloudHttpBodyTest {

    @Test
    fun `POST 无 body 时补一个长度为 0 的 JSON body`() {
        val body = CloudHttp.bodyFor("POST", null)
        assertNotNull(body)
        assertEquals(0L, body!!.contentLength())
        assertEquals("application/json; charset=utf-8", body.contentType().toString())
    }

    @Test
    fun `PUT 和 PATCH 无 body 时同样补空 body`() {
        for (method in listOf("PUT", "PATCH")) {
            val body = CloudHttp.bodyFor(method, null)
            assertNotNull(method, body)
            assertEquals(method, 0L, body!!.contentLength())
            }
    }

    @Test
    fun `GET 和 DELETE 无 body 时保持 null`() {
        assertNull(CloudHttp.bodyFor("GET", null))
        assertNull(CloudHttp.bodyFor("DELETE", null))
    }

    @Test
    fun `传了 body 时按 JSON 序列化`() {
        val json = JSONObject().put("a", 1)
        val body = CloudHttp.bodyFor("POST", json)!!
        val sink = okio.Buffer()
        body.writeTo(sink)
        assertEquals(json.toString(), sink.readUtf8())
    }
}
