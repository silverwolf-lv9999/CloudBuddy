package cn.cloudbuddy.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Connect 帧编解码与 `process.Process/Start` 响应解析的单测。
 *
 * 这两块是纯字节/JSON 处理，不需要活沙箱就能覆盖，也是整条执行链最容易出错的地方。
 */
class ConnectFrameTest {

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    @Test
    fun `encode 写入大端长度且首字节为 0`() {
        val payload = "{\"a\":1}".toByteArray(Charsets.UTF_8)
        val frame = ConnectFrame.encode(payload)

        assertEquals(0, frame[0].toInt())
        assertEquals(payload.size + ConnectFrame.HEADER_SIZE, frame.size)
        // 长度字段按大端还原
        val len = ((frame[1].toInt() and 0xFF) shl 24) or
            ((frame[2].toInt() and 0xFF) shl 16) or
            ((frame[3].toInt() and 0xFF) shl 8) or
            (frame[4].toInt() and 0xFF)
        assertEquals(payload.size, len)
        assertEquals("{\"a\":1}", String(frame.copyOfRange(5, frame.size), Charsets.UTF_8))
    }

    @Test
    fun `encode 处理超过 255 字节的载荷`() {
        val payload = ByteArray(1000) { 'x'.code.toByte() }
        val frame = ConnectFrame.encode(payload)

        val len = ((frame[1].toInt() and 0xFF) shl 24) or
            ((frame[2].toInt() and 0xFF) shl 16) or
            ((frame[3].toInt() and 0xFF) shl 8) or
            (frame[4].toInt() and 0xFF)
        assertEquals(1000, len)
        assertEquals(1005, frame.size)
    }

    @Test
    fun `decodeAll 还原多帧`() {
        val a = ConnectFrame.encode("one".toByteArray())
        val b = ConnectFrame.encode("two".toByteArray())
        val frames = ConnectFrame.decodeAll(a + b)

        assertEquals(2, frames.size)
        assertEquals("one", String(frames[0]))
        assertEquals("two", String(frames[1]))
    }

    @Test
    fun `decodeAll 丢弃末尾截断帧`() {
        val full = ConnectFrame.encode("complete".toByteArray())
        val truncated = ConnectFrame.encode("broken".toByteArray()).copyOfRange(0, 8)

        val frames = ConnectFrame.decodeAll(full + truncated)
        assertEquals(1, frames.size)
        assertEquals("complete", String(frames[0]))
    }

    @Test
    fun `decodeAll 空输入返回空列表`() {
        assertTrue(ConnectFrame.decodeAll(ByteArray(0)).isEmpty())
        // 不足一个帧头
        assertTrue(ConnectFrame.decodeAll(ByteArray(3)).isEmpty())
    }

    @Test
    fun `decodeAll 忽略长度非法的帧`() {
        // 声明长度 100，实际只有 2 字节
        val bogus = byteArrayOf(0, 0, 0, 0, 100, 1, 2)
        assertTrue(ConnectFrame.decodeAll(bogus).isEmpty())
    }

    @Test
    fun `merge 累加 stdout 与 stderr 并取 exitCode`() {
        val f1 = JSONObject()
            .put("event", JSONObject().put("data", JSONObject().put("stdout", b64("hello "))))
            .toString().toByteArray()
        val f2 = JSONObject()
            .put("event", JSONObject().put("data", JSONObject().put("stdout", b64("world"))))
            .toString().toByteArray()
        val f3 = JSONObject()
            .put("event", JSONObject().put("data", JSONObject().put("stderr", b64("warn"))))
            .toString().toByteArray()
        val f4 = JSONObject()
            .put("event", JSONObject().put("end", JSONObject().put("exitCode", 0)))
            .toString().toByteArray()

        val r = ProcessOutput.merge(listOf(f1, f2, f3, f4))
        assertEquals("hello world", r.stdout)
        assertEquals("warn", r.stderr)
        assertEquals(0, r.exitCode)
        assertNull(r.error)
        assertTrue(r.ok)
    }

    @Test
    fun `merge 标记非零退出码为失败`() {
        val f = JSONObject()
            .put("event", JSONObject().put("end", JSONObject().put("exitCode", 2)))
            .toString().toByteArray()

        val r = ProcessOutput.merge(listOf(f))
        assertEquals(2, r.exitCode)
        assertTrue(!r.ok)
    }

    @Test
    fun `merge 解析顶层 error 帧`() {
        val f = JSONObject()
            .put("error", JSONObject().put("message", "permission denied"))
            .toString().toByteArray()

        val r = ProcessOutput.merge(listOf(f))
        assertEquals("permission denied", r.error)
        assertTrue(!r.ok)
    }

    @Test
    fun `merge 跳过非 JSON 帧`() {
        val r = ProcessOutput.merge(listOf("not json".toByteArray(), ByteArray(0)))
        assertEquals("", r.stdout)
        assertEquals(null, r.exitCode)
    }

    @Test
    fun `decodeB64 兼容空串与非法输入`() {
        assertEquals("", ProcessOutput.decodeB64(""))
        // 纯空白视同空载荷（base64 里没有有意义的空白）
        assertEquals("", ProcessOutput.decodeB64("   "))
        // 非法 base64 时退回原文，避免整段输出丢失
        assertEquals("!!!", ProcessOutput.decodeB64("!!!"))
    }

    @Test
    fun `merge 兼容 event 层直接给 exitCode`() {
        val f = JSONObject()
            .put("event", JSONObject().put("exitCode", "0"))
            .toString().toByteArray()

        val r = ProcessOutput.merge(listOf(f))
        assertEquals(0, r.exitCode)
    }

    /**
     * 真实环境实测帧（`sh -c 'echo hi; exit 7'`）：
     * envd 在 end 里既给 exitCode 又给 `error: "exit status 7"`。
     * 后者是退出状态描述，不是执行错误——当成 error 会让上层以为沙箱坏了。
     */
    @Test
    fun `merge 不把 exit status 文本当成错误`() {
        val end = JSONObject()
            .put("exitCode", 7)
            .put("exited", true)
            .put("status", "exit status 7")
            .put("error", "exit status 7")
        val f = JSONObject().put("event", JSONObject().put("end", end)).toString().toByteArray()

        val r = ProcessOutput.merge(listOf(f))
        assertEquals(7, r.exitCode)
        assertNull(r.error)
        // 仍然是失败结果：命令确实非零退出，只是不该报成协议错误。
        assertTrue(!r.ok)
    }

    @Test
    fun `merge 识别 signal 与裸 status 形态`() {
        for (text in listOf("status 143", "signal: killed")) {
            val end = JSONObject().put("exitCode", 143).put("error", text)
            val f = JSONObject().put("event", JSONObject().put("end", end)).toString().toByteArray()
            assertNull("不应把 $text 当成错误", ProcessOutput.merge(listOf(f)).error)
        }
    }

    @Test
    fun `merge 保留真正的 end 错误`() {
        val end = JSONObject().put("error", "sandbox restarted, process lost")
        val f = JSONObject().put("event", JSONObject().put("end", end)).toString().toByteArray()

        val r = ProcessOutput.merge(listOf(f))
        assertEquals("sandbox restarted, process lost", r.error)
        assertTrue(!r.ok)
    }

    @Test
    fun `lengthOf 与 decodeAll 用同一套长度算法`() {
        val payload = "{\"event\":{}}".toByteArray()
        val raw = ConnectFrame.encode(payload) + ConnectFrame.encode("x".toByteArray())
        val frames = ConnectFrame.decodeAll(raw)
        assertEquals(2, frames.size)

        // 逐帧头解出来的长度必须等于真实载荷长度，
        // 否则流式读取会读错位（读过工具输出的人都知道那有多难查）。
        val len = ConnectFrame.lengthOf(raw.copyOfRange(0, ConnectFrame.HEADER_SIZE))
        assertEquals(payload.size, len)
        assertEquals("x".toByteArray().size, ConnectFrame.lengthOf(raw.copyOfRange(raw.size - 6, raw.size - 1)))
    }

    @Test
    fun `lengthOf 对不完整帧头返回负数`() {
        assertEquals(-1, ConnectFrame.lengthOf(byteArrayOf(0, 0)))
        assertEquals(-1, ConnectFrame.lengthOf(ByteArray(0)))
    }

    @Test
    fun `decodeAll 丢弃末尾被截断的帧`() {
        val full = ConnectFrame.encode("hello".toByteArray())
        val cut = full.copyOfRange(0, full.size - 2)
        assertEquals(1, ConnectFrame.decodeAll(full + cut).size)
    }
}
