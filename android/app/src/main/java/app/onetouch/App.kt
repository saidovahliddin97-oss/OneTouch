package app.onetouch

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        OneTouchService.createChannels(this)
    }
}
