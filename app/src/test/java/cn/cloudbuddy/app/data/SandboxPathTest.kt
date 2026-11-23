package cn.cloudbuddy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SandboxPathTest {

    @Test
    fun `agent uri maps to workspace path`() {
        assertEquals("/workspace/report.md", SandboxPath.of("agent://files/workspace/report.md"))
        assertEquals("/workspace/report.md", SandboxPath.of("agent:///workspace/report.md"))
        assertEquals("/root/notes.txt", SandboxPath.of("file:///root/notes.txt"))
    }

    @Test
    fun `percent encoded segments are decoded`() {
        assertEquals(
            "/workspace/中文 名.txt",
            SandboxPath.of("agent://files/workspace/%E4%B8%AD%E6%96%87%20%E5%90%8D.txt")
        )
    }

    @Test
    fun `path traversal and outside roots are rejected`() {
        assertNull(SandboxPath.of("agent://files/workspace/../../etc/passwd"))
        assertNull(SandboxPath.of("agent://files/etc/passwd"))
        assertNull(SandboxPath.of("agent://files/workspace/%2e%2e/%2e%2e/etc/passwd"))
        assertNull(SandboxPath.of("https://example.com/a.txt"))
        assertNull(SandboxPath.of("agent://files/workspace/a%5Cb"))
    }

    @Test
    fun `object key variants keep original decoded and encoded forms`() {
        val variants = SandboxPath.objectKeyVariants("agent://files/uploads/a%20b.txt")
        assertEquals(listOf("uploads/a%20b.txt", "uploads/a b.txt"), variants)
    }

    @Test
    fun `parent and join helpers`() {
        assertEquals("/workspace", SandboxPath.parentOf("/workspace/uploads"))
        assertEquals("/", SandboxPath.parentOf("/workspace"))
        assertEquals("/workspace/a/b.txt", SandboxPath.join("/workspace/a/", "b.txt"))
        assertEquals("b.txt", SandboxPath.fileName("/workspace/a/b.txt"))
    }

    @Test
    fun `mime detection covers common artifacts`() {
        assertEquals("text/markdown", SandboxPath.mimeOf("README.md"))
        assertEquals("image/png", SandboxPath.mimeOf("chart.PNG"))
        assertEquals("application/octet-stream", SandboxPath.mimeOf("archive.unknownext"))
    }
}
