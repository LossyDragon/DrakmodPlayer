package com.lossydragon.modplayer

import android.app.Application
import android.util.Log
import com.lossydragon.modplayer.core.CrashHandler
import com.lossydragon.modplayer.core.FileLoggingTree
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.di.appModule
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import timber.log.Timber

class App : Application() {
    private val appScope = MainScope()

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

        val fileLoggingTree = get<FileLoggingTree>()
        Timber.plant(fileLoggingTree)
        appScope.launch {
            get<AppPreferences>().getFileLoggingFlow().collect { enabled ->
                fileLoggingTree.enabled = enabled
            }
        }

        CrashHandler.initialize(this@App)
    }

    override fun onTerminate() {
        super.onTerminate()
        appScope.cancel()
    }
}
