package cn.cloudbuddy.app.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/**
 * 沙箱命令执行的鉴权续期验证。
 *
 * 真机上踩到的问题：envd 的 JWT 只有 72 小时（实测 `exp - iat = 259200`），
 * 过期后一律 401。之前 401 只会变成一句 `[error] HTTP 401` 喂给模型，
 * 用户看到的是"agent 突然不会用工具了"，实际只是要换张 token。
 */
class SandboxExecTest {

    private lateinit var server: MockWebServer
    private val hits = AtomicInteger()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** 构造一段 envd 的 server-streaming 响应：一帧 stdout + 一帧 end。 */
    private fun okBody(text: String): MockResponse {
        val out = JSONObject()
            .put("event", JSONObject().put("data", JSONObject()
                .put("stdout", Base64.getEncoder().encodeToString(text.toByteArray()))))
        val end = JSONObject()
            .put("event", JSONObject().put("end", JSONObject().put("exitCode", 0)))
        val payload = ConnectFrame.encode(out.toString().toByteArray()) +
            ConnectFrame.encode(end.toString().toByteArray()) +
            ConnectFrame.encode("{}".toByteArray())
        return MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "application/connect+json")
            .setBody(okio.Buffer().write(payload))
    }

    private fun exec(): SandboxExec = SandboxExec(OkHttpClient(), "")

    @Test
    fun `401 时换新 token 重试一次`() = runBlocking {
        val seen = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val token = request.getHeader("X-Access-Token").orEmpty()
                seen += token
                return if (hits.incrementAndGet() == 1) {
                    MockResponse().setResponseCode(401)
                        .setBody("""{"error":"invalid token"}""")
                } else {
                    okBody("renewed")
                }
            }
        }

        val r = exec().run(
            endpoint = server.url("/").toString(),
            token = "old",
            command = "echo hi",
            onUnauthorized = { "new" },
        )

        assertEquals("renewed", r.stdout)
        assertNull(r.error)
        assertEquals(listOf("old", "new"), seen)
    }

    @Test
    fun `续期拿不到新 token 时如实报错且不重复请求`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                hits.incrementAndGet()
                return MockResponse().setResponseCode(401).setBody("expired")
            }
        }

        val r = exec().run(
            endpoint = server.url("/").toString(),
            token = "old",
            command = "echo hi",
            onUnauthorized = { null },
        )

        assertTrue(r.error!!.startsWith("HTTP 401"))
        assertEquals(1, hits.get())
    }

    @Test
    fun `没有续期钩子时 401 只请求一次`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                hits.incrementAndGet()
                return MockResponse().setResponseCode(403).setBody("forbidden")
            }
        }

        val r = exec().run(server.url("/").toString(), "old", "echo hi")

        assertTrue(r.error!!.startsWith("HTTP 403"))
        assertEquals(1, hits.get())
        assertFalse(r.ok)
    }

    @Test
    fun `流式回调拿到输出，结果与一次性读取一致`() = runBlocking {
        server.enqueue(okBody("hello stream"))

        val chunks = StringBuilder()
        val r = exec().run(
            endpoint = server.url("/").toString(),
            token = "t",
            command = "echo hi",
            onChunk = { text, _ -> chunks.append(text) },
        )

        // 回调里出现的就是命令输出，结尾 merge 出来的结果不能因此少东西。
        assertEquals("hello stream", chunks.toString())
        assertEquals("hello stream", r.stdout)
        assertNull(r.error)
    }

    @Test
    fun `流式读取时 stderr 单独回调`() = runBlocking {
        val out = JSONObject().put("event", JSONObject().put("data", JSONObject()
            .put("stderr", Base64.getEncoder().encodeToString("boom".toByteArray()))))
        val end = JSONObject().put("event", JSONObject().put("end", JSONObject().put("exitCode", 1)))
        val payload = ConnectFrame.encode(out.toString().toByteArray()) +
            ConnectFrame.encode(end.toString().toByteArray())
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/connect+json")
                .setBody(okio.Buffer().write(payload))
        )

        val flags = mutableListOf<Boolean>()
        val r = exec().run(
            endpoint = server.url("/").toString(),
            token = "t",
            command = "ls /nope",
            onChunk = { _, isErr -> flags += isErr },
        )

        assertEquals(listOf(true), flags)
        assertEquals("boom", r.stderr)
        // 非零退出是命令的结局，不是协议错误——流式路径也不能把它算成 error。
        assertNull(r.error)
        assertEquals(1, r.exitCode)
    }

    @Test
    fun `流被截断时已收到的输出仍然保留`() = runBlocking {
        val out = JSONObject().put("event", JSONObject().put("data", JSONObject()
            .put("stdout", Base64.getEncoder().encodeToString("partial".toByteArray()))))
        val good = ConnectFrame.encode(out.toString().toByteArray())
        // 帧头声称还有 500 字节，实际只给 3 字节：真实网关断流就是这个样子。
        val broken = byteArrayOf(0, 0, 0, 1, 244.toByte()) + byteArrayOf(1, 2, 3)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/connect+json")
                .setBody(okio.Buffer().write(good + broken))
        )

        val r = exec().run(
            endpoint = server.url("/").toString(),
            token = "t",
            command = "echo partial",
            onChunk = { _, _ -> },
        )

        assertEquals("partial", r.stdout)
    }
}
