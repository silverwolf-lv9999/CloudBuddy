package cn.cloudbuddy.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxFsTest {

    @Test
    fun `ListDir Connect RPC response is parsed`() {
        val json = JSONObject().put(
            "entries",
            JSONArray()
                .put(
                    JSONObject()
                        .put("name", "src")
                        .put("path", "/workspace/src")
                        .put("type", 2)
                        .put("size", "0")
                        .put("modifiedTime", "2026-11-23T10:00:00Z")
                )
                .put(
                    JSONObject()
                        .put("name", "report.md")
                        .put("path", "/workspace/report.md")
                        .put("type", "FILE_TYPE_FILE")
                        .put("size", 1234)
                        .put("modifiedTime", JSONObject().put("seconds", 1763890000L).put("nanos", 0))
                )
        ).toString()

        val entries = SandboxEntryParser.parse(json, "/workspace")!!
        assertEquals(2, entries.size)
        assertTrue(entries[0].isDir)
        assertNull(entries[0].mimeType)
        assertFalse(entries[1].isDir)
        assertEquals(1234L, entries[1].size)
        assertEquals("text/markdown", entries[1].mimeType)
        assertTrue(entries[0].modifiedAt > 0L)
        assertTrue(entries[1].modifiedAt > 0L)
    }

    @Test
    fun `directory type accepts enum strings and mode bits`() {
        val byEnum = SandboxEntryParser.parse(
            """{"entries":[{"name":"a","type":"FILE_TYPE_DIRECTORY"}]}""", "/workspace"
        )!!
        assertTrue(byEnum.single().isDir)
        assertEquals("/workspace/a", byEnum.single().path)

        val byMode = SandboxEntryParser.parse(
            """[{"name":"b","type":1,"mode":16877}]""", "/workspace"
        )!!
        assertTrue(byMode.single().isDir)
    }

    @Test
    fun `alternate envelopes and empty directory are handled`() {
        assertEquals(1, SandboxEntryParser.parse("""[{"name":"x.txt","type":1}]""", "/workspace")!!.size)
        assertEquals(
            1,
            SandboxEntryParser.parse("""{"result":{"entries":[{"name":"y","type":2}]}}""", "/workspace")!!.size
        )
        assertEquals(
            1,
            SandboxEntryParser.parse("""{"data":{"files":[{"name":"z","type":1}]}}""", "/workspace")!!.size
        )
        assertTrue(SandboxEntryParser.parse("""{"entries":[]}""", "/workspace")!!.isEmpty())
        assertNull(SandboxEntryParser.parse("404 page not found", "/workspace"))
        assertNull(SandboxEntryParser.parse("""{"error":"not a listing"}""", "/workspace"))
    }

    @Test
    fun `unsafe entries are rejected`() {
        val entries = SandboxEntryParser.parse(
            """{"entries":[{"name":"..","type":2},{"name":"ok","type":1},{"name":"bad\\path","type":1}]}""",
            "/workspace"
        )!!
        assertEquals(1, entries.size)
        assertEquals("ok", entries.single().name)
    }

    @Test
    fun `file gateway endpoint follows official derivation rules`() {
        val session = JSONObject()
            .put("link", "https://8080-abcd1234.e2b.ap-guangzhou.sandbox.cloudstudio.club/acp")
            .put("token", "t")
        assertEquals(
            "https://49983-abcd1234.e2b.ap-guangzhou.sandbox.cloudstudio.club",
            SandboxFs.dataEndpoint(session)
        )

        val explicit = JSONObject()
            .put("e2bDataEndpoint", "http://49983-abcd1234.e2b.dev/")
            .put("link", "https://8080-abcd1234.e2b.dev/acp")
        assertEquals("https://49983-abcd1234.e2b.dev", SandboxFs.dataEndpoint(explicit))

        val withId = JSONObject()
            .put("sandboxId", "real-id")
            .put("link", "https://8080-placeholder.e2b.dev/acp")
        assertEquals("https://49983-real-id.e2b.dev", SandboxFs.deriveDataEndpoint(withId))
    }

    @Test
    fun `username depends on path root`() {
        assertEquals("user", SandboxFs.userFor("/workspace/report.md"))
        assertEquals("root", SandboxFs.userFor("/root/notes.txt"))
        assertEquals("root", SandboxFs.userFor("/root"))
    }
}
