package cn.cloudbuddy.app.data

import android.content.SharedPreferences

/**
 * 极简内存 SharedPreferences。
 *
 * 这些存储类只是「读写一段 JSON」，为了几个字符串引 Robolectric 不划算；
 * 用一个 30 行的假实现就能在普通 JVM 单测里跑完，也顺带说明它们只依赖
 * `getString` / `edit().putString()` 这么几个方法。
 */
internal class FakePrefs : SharedPreferences {
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
