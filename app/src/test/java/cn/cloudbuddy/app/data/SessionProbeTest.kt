package cn.cloudbuddy.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 启动探测的三态判定。
 *
 * 这一层的意义就是**不要让「连不上」和「没凭据」混成一个 false**：
 * 混在一起的结果是网络一差就把已登录用户扔回登录页。
 */
class SessionProbeTest {

    @Test
    fun `no error means the session is valid`() {
        assertEquals(SessionProbe.VALID, sessionProbeOf(null))
    }

    @Test
    fun `expired session maps to invalid`() {
        assertEquals(SessionProbe.INVALID, sessionProbeOf(SessionExpiredException("登录已过期")))
    }

    @Test
    fun `401 and 403 map to invalid`() {
        assertEquals(SessionProbe.INVALID, sessionProbeOf(CloudException("未授权", 401)))
        assertEquals(SessionProbe.INVALID, sessionProbeOf(CloudException("禁止访问", 403)))
    }

    @Test
    fun `network failures map to unreachable`() {
        assertEquals(SessionProbe.UNREACHABLE, sessionProbeOf(SocketTimeoutException("timeout")))
        assertEquals(SessionProbe.UNREACHABLE, sessionProbeOf(UnknownHostException("no dns")))
        assertEquals(SessionProbe.UNREACHABLE, sessionProbeOf(IOException("offline")))
    }

    /** 服务端 5xx 说明不了凭据有没有问题，不能因此登出。 */
    @Test
    fun `server errors map to unreachable`() {
        assertEquals(SessionProbe.UNREACHABLE, sessionProbeOf(CloudException("网关错误", 502)))
    }
}
