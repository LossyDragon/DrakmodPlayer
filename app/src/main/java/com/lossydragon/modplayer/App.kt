package com.lossydragon.modplayer

import android.app.Application
import android.util.Log
import com.lossydragon.modplayer.core.CrashHandler
import com.lossydragon.modplayer.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import timber.log.Timber

class App : Application() {
    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority == Log.DEBUG) return
            Log.println(priority, "Mod Player", message)
        }
    }

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@App)
            modules(appModule)
        }

        Timber.plant(if (BuildConfig.DEBUG) Timber.DebugTree() else ReleaseTree())

        CrashHandler.initialize(this@App)
    }
}
