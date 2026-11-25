package cn.cloudbuddy.app.data

/**
 * Connect 协议帧编解码。
 *
 * envd 的调用分两种编码，踩错就是 HTTP 415：
 * - **unary**（`filesystem.*`、`process.Process/List`）→ 裸 JSON，`application/json`；
 * - **streaming**（`process.Process/Start`）→ Connect 帧，`application/connect+json`。
 *
 * 帧格式固定：`flags(1 字节) + 长度(4 字节大端) + 载荷(JSON)`。
 * 请求帧的 flags 恒为 0（不压缩、非结束帧）。
 *
 * 这里只放纯函数，网络与解码在 [SandboxExec] 里做，方便单测覆盖边界。
 */
object ConnectFrame {

    /** 单帧头部长度：1 字节 flags + 4 字节大端长度。 */
    const val HEADER_SIZE = 5

    /** 把一条 JSON 载荷包成请求帧（flags = 0，长度大端）。 */
    fun encode(payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = 0
        val len = payload.size
        out[1] = ((len ushr 24) and 0xFF).toByte()
        out[2] = ((len ushr 16) and 0xFF).toByte()
        out[3] = ((len ushr 8) and 0xFF).toByte()
        out[4] = (len and 0xFF).toByte()
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    /**
     * 从 5 字节帧头解出载荷长度（大端）。
     *
     * 流式读取时逐帧调用，和 [decodeAll] 用同一套算法，避免两条路径算出不同结果。
     */
    fun lengthOf(header: ByteArray): Int {
        if (header.size < HEADER_SIZE) return -1
        return ((header[1].toInt() and 0xFF) shl 24) or
            ((header[2].toInt() and 0xFF) shl 16) or
            ((header[3].toInt() and 0xFF) shl 8) or
            (header[4].toInt() and 0xFF)
    }

    /**
     * 从响应字节流里按帧切分，返回每帧的载荷。
     *
     * 末尾不完整的帧直接丢弃——流被网关截断时这是正常现象，
     * 已完整到达的帧仍然有效（报告实测长连接会被断流）。
     */
    fun decodeAll(raw: ByteArray): List<ByteArray> {
        val frames = ArrayList<ByteArray>()
        var off = 0
        while (off + HEADER_SIZE <= raw.size) {
            val len = lengthOf(raw.copyOfRange(off, off + HEADER_SIZE))
            val start = off + HEADER_SIZE
            // 长度非法或超出剩余字节：说明流被截断，停止解析。
            if (len < 0 || len > raw.size - start) break
            frames.add(raw.copyOfRange(start, start + len))
            off = start + len
        }
        return frames
    }
}
