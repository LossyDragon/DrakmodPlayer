package com.lossydragon.modplayer.di

import androidx.room.Room
import com.lossydragon.modplayer.core.Constants
import com.lossydragon.modplayer.data.DownloadHistoryRepository
import com.lossydragon.modplayer.data.ModArchiveService
import com.lossydragon.modplayer.data.ModuleMetadataRepository
import com.lossydragon.modplayer.data.PlaylistRepository
import com.lossydragon.modplayer.db.AppDatabase
import com.lossydragon.modplayer.db.AppPreferences
import com.lossydragon.modplayer.player.ModPlayer
import com.lossydragon.modplayer.player.PlayerEngine
import com.lossydragon.modplayer.player.PlayerViewModel
import com.lossydragon.modplayer.ui.screens.browser.FileBrowserViewModel
import com.lossydragon.modplayer.ui.screens.downloads.viewmodel.DownloadHistoryViewModel
import com.lossydragon.modplayer.ui.screens.downloads.viewmodel.DownloadViewModel
import com.lossydragon.modplayer.ui.screens.downloads.viewmodel.ModuleResultViewModel
import com.lossydragon.modplayer.ui.screens.playlists.PlaylistsViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.accept
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.xml.xml
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    // Core
    single { AppPreferences(androidContext()) }

    // ViewModels
    viewModel { DownloadViewModel(get()) }
    viewModel { FileBrowserViewModel(androidContext(), get(), get()) }
    viewModel { ModuleResultViewModel(androidContext(), get(), get(), get(), get()) }
    viewModel { PlayerViewModel(androidContext(), get(), get()) }
    viewModel { DownloadHistoryViewModel(get()) }
    viewModel { PlaylistsViewModel(androidContext(), get(), get()) }

    // Database
    single {
        Room.databaseBuilder(
            androidContext(),
            AppDatabase::class.java,
            Constants.ROOM_DATABASE_NAME,
        ).build()
    }
    single { get<AppDatabase>().moduleMetadataDao() }
    single { get<AppDatabase>().downloadHistoryDao() }
    single { get<AppDatabase>().playlistDao() }
    single { ModuleMetadataRepository(androidContext(), get()) }
    single { DownloadHistoryRepository(get()) }
    single { PlaylistRepository(get()) }

    // Downloads
    single {
        HttpClient(engineFactory = Android) {
            install(ContentNegotiation) { xml() }
            defaultRequest {
                url(Constants.HTTP_BASE_URL)
                accept(ContentType.Application.Xml)
            }
        }
    }
    single { ModArchiveService(get()) }

    // Media Player
    single { PlayerEngine(androidContext(), get()) }
    single { ModPlayer(androidContext(), get(), get()) }
}
