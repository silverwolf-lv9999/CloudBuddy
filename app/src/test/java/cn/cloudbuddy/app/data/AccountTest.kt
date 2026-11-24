package cn.cloudbuddy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 账号表的序列化与展示规则。多账号最容易出问题的地方是「切回来变成另一个号」。 */
class AccountTest {

    @Test
    fun `mask hides the middle of the phone number`() {
        assertEquals("139****8888", Account.maskHint("13912348888"))
        assertEquals("139****8888", Account.maskHint("+86 139 1234 8888"))
        // 太短的不猜，原样显示——宁可露出也不要把用户的输入截成乱码
        assertEquals("12345", Account.maskHint("12345"))
    }

    @Test
    fun `title prefers nickname then masked phone`() {
        assertEquals("小明", Account("a1", hint = "13912348888", nickname = "小明").title)
        assertEquals("139****8888", Account("a1", hint = "13912348888").title)
        assertEquals("未命名账号", Account("a1").title)
    }

    @Test
    fun `encode and parse round trip keeps every field`() {
        val list = listOf(
            Account("a1", "13912348888", "小明", "https://x/a.png", "u1", "ent-9", "https://e.example", 1L),
            Account("a2", "13800001111", "")
        )

        val parsed = Account.listOf(Account.encode(list))

        assertEquals(list, parsed)
    }

    @Test
    fun `broken json degrades to empty list instead of crashing`() {
        assertTrue(Account.listOf("not json").isEmpty())
        assertTrue(Account.listOf(null).isEmpty())
        assertTrue(Account.listOf("""[{"hint":"139"}]""").isEmpty()) // 没有 id 的记录直接丢掉
        assertNull(Account.from(null))
    }

    @Test
    fun `fold mode parses unknown values as whole turn`() {
        assertEquals(FoldMode.BODY, FoldMode.of("body"))
        assertEquals(FoldMode.WHOLE, FoldMode.of("whole"))
        assertEquals(FoldMode.WHOLE, FoldMode.of(""))
        assertEquals(FoldMode.WHOLE, FoldMode.of(null))
        // 旧版本没这个字段时读到的就是默认值，不能因为空串就退化成「只折过程」
        assertEquals(FoldMode.WHOLE, FoldMode.of(FoldMode.DEFAULT))
    }
}
