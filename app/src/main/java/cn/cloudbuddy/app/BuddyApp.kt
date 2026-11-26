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

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        repository = BuddyRepository(settings)
        repository.start()
    }

    fun applyTheme() {
        themeTick.value = themeTick.value + 1
    }
}
