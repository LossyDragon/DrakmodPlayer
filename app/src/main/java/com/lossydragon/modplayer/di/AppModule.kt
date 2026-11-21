package com.lossydragon.modplayer.di

import androidx.room.Room
import com.lossydragon.modplayer.core.Constants
import com.lossydragon.modplayer.core.FileLoggingTree
import com.lossydragon.modplayer.data.DownloadHistoryRepository
import com.lossydragon.modplayer.data.ModArchiveService
import com.lossydragon.modplayer.data.ModuleRepository
import com.lossydragon.modplayer.data.PlaylistRepository
import com.lossydragon.modplayer.data.SafDataSource
import com.lossydragon.modplayer.db.AppDatabase
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.player.Engine
import com.lossydragon.modplayer.player.ModPlayer
import com.lossydragon.modplayer.player.PlayerEngine
import com.lossydragon.modplayer.player.PlayerViewModel
import com.lossydragon.modplayer.ui.screens.browser.FileBrowserViewModel
import com.lossydragon.modplayer.ui.screens.downloads.DownloadsViewModel
import com.lossydragon.modplayer.ui.screens.playlists.PlaylistsViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Qualifier of the process-wide [CoroutineScope] for work that must outlive a ViewModel. */
const val APP_SCOPE = "appScope"

val appModule = module {
    // Core
    single { AppPreferences(androidContext()) }
    single { FileLoggingTree(androidContext()) }

    single(named(APP_SCOPE)) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    single { SafDataSource(androidContext()) }

    // ViewModels
    viewModel { FileBrowserViewModel(get(), get(), get()) }
    viewModel { DownloadsViewModel(get(), get(), get(), get(), get(), get()) }
    viewModel { PlayerViewModel(get(), get(), get()) }
    viewModel { PlaylistsViewModel(get()) }

    // Database
    single {
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            Constants.ROOM_DATABASE_NAME,
        )
            .addMigrations(*AppDatabase.MIGRATIONS)
            // Pre-playlist schemas hold nothing worth keeping; everything newer must migrate
            // so user playlists are never silently dropped by a schema bump.
            .fallbackToDestructiveMigrationFrom(
                true,
                *(1..AppDatabase.LAST_DESTRUCTIVE_VERSION).toList().toIntArray(),
            )
            .build()
    }
    single { get<AppDatabase>().moduleMetadataDao() }
    single { get<AppDatabase>().downloadHistoryDao() }
    single { get<AppDatabase>().playlistDao() }
    single { ModuleRepository(get(), get(), get(), get(named(APP_SCOPE))) }
    single { DownloadHistoryRepository(get()) }
    single { PlaylistRepository(get(), get()) }

    // Downloads
    single {
        HttpClient(engineFactory = Android) {
            expectSuccess = true
            install(HttpTimeout) {
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 30_000
                requestTimeoutMillis = 30_000
            }
            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 2)
                exponentialDelay()
            }
            // Accept headers are per request: XML for the API, none for binary downloads.
            defaultRequest { url(Constants.HTTP_BASE_URL) }
        }
    }
    single { ModArchiveService(get()) }

    // Media Player
    single<Engine> { PlayerEngine(androidContext(), get()) }
    single { ModPlayer(androidContext(), get(), get()) }
}
