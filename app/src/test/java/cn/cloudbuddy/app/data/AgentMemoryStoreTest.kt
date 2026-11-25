package cn.cloudbuddy.app.data

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自定义模式的历史存储。
 *
 * 这里验证的是「切会话不串台」这条硬约束：沙箱跟着会话走，历史也必须跟着会话走。
 * 串了的后果不是显示错乱，而是**把 A 会话的上下文喂给 B 会话的模型**——
 * 模型会开始回答上一个项目的文件，用户完全无从理解。
 */
class AgentMemoryStoreTest {

    /** 极简内存实现，避免为几个字符串引 Robolectric。 */
    private class FakePrefs : SharedPreferences {
        val map = HashMap<String, Any?>()

        override fun getAll(): MutableMap<String, Any?> = map
        override fun getString(key: String, def: String?): String? = map[key] as? String ?: def
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, def: MutableSet<String>?): MutableSet<String>? =
            map[key] as? MutableSet<String> ?: def
        override fun getInt(key: String, def: Int): Int = map[key] as? Int ?: def
        override fun getLong(key: String, def: Long): Long = map[key] as? Long ?: def
        override fun getFloat(key: String, def: Float): Float = map[key] as? Float ?: def
        override fun getBoolean(key: String, def: Boolean): Boolean = map[key] as? Boolean ?: def
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        inner class Editor : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private val removed = HashSet<String>()
            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { removed += key }
            override fun clear() = apply { map.clear() }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                removed.forEach { map.remove(it) }
                map.putAll(pending)
            }
        }
    }

    @Test
    fun `保存后能原样读回`() {
        val store = AgentMemoryStore(FakePrefs())
        store.save("c1", listOf(
            AgentTurn(fromUser = true, text = "看下 README"),
            AgentTurn(fromUser = false, text = "看过了，共 120 行", note = "run_shell: wc -l README.md"),
        ))

        val turns = store.load("c1")
        assertEquals(2, turns.size)
        assertTrue(turns[0].fromUser)
        assertEquals("看下 README", turns[0].text)
        assertEquals("run_shell: wc -l README.md", turns[1].note)
    }

    @Test
    fun `两个会话的历史互不干扰`() {
        val store = AgentMemoryStore(FakePrefs())
        store.save("c1", listOf(AgentTurn(true, "A 的问题")))
        store.save("c2", listOf(AgentTurn(true, "B 的问题")))

        assertEquals("A 的问题", store.load("c1").single().text)
        assertEquals("B 的问题", store.load("c2").single().text)
        // 没存过的会话必须是空的，不能回落到别人的历史
        assertTrue(store.load("c3").isEmpty())
    }

    @Test
    fun `空文本条目被跳过`() {
        val store = AgentMemoryStore(FakePrefs())
        store.save("c1", listOf(AgentTurn(true, ""), AgentTurn(false, "有内容")))
        assertEquals(1, store.load("c1").size)
    }

    @Test
    fun `超长内容截断但保留可读部分`() {
        val store = AgentMemoryStore(FakePrefs())
        store.save("c1", listOf(AgentTurn(false, "x".repeat(20_000))))
        val text = store.load("c1").single().text
        assertEquals(8_000, text.length)
    }

    @Test
    fun `note 缺省时读回 null 而不是空串`() {
        val store = AgentMemoryStore(FakePrefs())
        store.save("c1", listOf(AgentTurn(true, "hi")))
        assertNull(store.load("c1").single().note)
    }

    @Test
    fun `clear 只清指定会话`() {
        val store = AgentMemoryStore(FakePrefs())
        store.save("c1", listOf(AgentTurn(true, "A")))
        store.save("c2", listOf(AgentTurn(true, "B")))

        store.clear("c1")
        assertTrue(store.load("c1").isEmpty())
        assertEquals("B", store.load("c2").single().text)
    }

    @Test
    fun `clearAll 清掉所有会话但不动其它偏好`() {
        val prefs = FakePrefs()
        prefs.edit().putString("theme", "dark").apply()
        val store = AgentMemoryStore(prefs)
        store.save("c1", listOf(AgentTurn(true, "A")))
        store.save("c2", listOf(AgentTurn(true, "B")))

        store.clearAll()
        assertTrue(store.load("c1").isEmpty())
        assertTrue(store.load("c2").isEmpty())
        assertEquals("dark", prefs.getString("theme", null))
    }
}
