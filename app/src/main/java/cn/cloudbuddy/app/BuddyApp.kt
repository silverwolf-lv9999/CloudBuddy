package cn.cloudbuddy.app

import android.app.Application
import cn.cloudbuddy.app.data.AppSettings
import cn.cloudbuddy.app.data.BuddyRepository
import kotlinx.coroutines.flow.MutableStateFlow

class BuddyApp : Application() {

    lateinit var settings: AppSettings
        private set

    lateinit var repository: BuddyRepository
        private set

    /** 主题设置变更时递增，驱动 Activity 重新取色。 */
    val themeTick = MutableStateFlow(0)

    /**
     * 本机外观类设置（导航栏形态等）变更时递增。
     *
     * 这些值只在组合里以普通 getter 读取，改动后不会自动触发重组；递增这个计数让
     * 根布局重建一次 settings 快照（见 MainActivity 的 remember(tick, uiTick)）。
     */
    val uiTick = MutableStateFlow(0)

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        repository = BuddyRepository(settings)
        repository.start()
    }

    fun applyTheme() {
        themeTick.value = themeTick.value + 1
    }

    /** 外观类设置改完后调一次，让根布局重读设置。 */
    fun notifyUiSettings() {
        uiTick.value = uiTick.value + 1
    }
}
