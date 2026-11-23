package com.lossydragon.modplayer

import android.app.Application
import android.util.Log
import com.lossydragon.modplayer.core.CrashHandler
import com.lossydragon.modplayer.core.FileLoggingTree
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.di.APP_SCOPE
import com.lossydragon.modplayer.di.appModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import timber.log.Timber

class App : Application() {

    private class ReleaseTree : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority < Log.INFO) return
            Log.println(priority, "Drakmod", message)
        }
    }

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@App)
            modules(appModule)
        }

        Timber.plant(if (BuildConfig.DEBUG) Timber.DebugTree() else ReleaseTree())

        val appScope = get<CoroutineScope>(named(APP_SCOPE))
        val fileLoggingTree = get<FileLoggingTree>()
        Timber.plant(fileLoggingTree)
        appScope.launch {
            get<AppPreferences>().getFileLoggingFlow().collect { enabled ->
                fileLoggingTree.enabled = enabled
            }
        }

        CrashHandler.initialize(this@App)
        appScope.launch(Dispatchers.IO) { CrashHandler.exportPendingReports(this@App) }
    }
}
