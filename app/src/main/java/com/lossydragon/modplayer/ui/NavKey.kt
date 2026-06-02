package com.lossydragon.modplayer.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.*
import androidx.navigation3.runtime.NavKey
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.screens.downloads.viewmodel.SearchType
import kotlinx.serialization.Serializable

sealed interface NavKeyPlaylists : NavKey {
    @Serializable data object List : NavKeyPlaylists

    @Serializable data class Entries(
        val playlistId: Long,
        val playlistName: String,
        val playlistComment: String
    ) : NavKeyPlaylists
}

@Serializable
sealed class NavKeyRoot : NavKey {
    @Serializable
    data object Main : NavKeyRoot()

    @Serializable
    data object Player : NavKeyRoot()
}

@Serializable
sealed class NavKeyMain(@param:StringRes val title: Int) : NavKey {
    abstract val selectedIcon: ImageVector
    abstract val unselectedIcon: ImageVector

    @Serializable
    data object Browser : NavKeyMain(R.string.nav_browser) {
        override val selectedIcon = Icons.Filled.Folder
        override val unselectedIcon = Icons.Outlined.Folder
    }

    @Serializable
    data object Playlists : NavKeyMain(R.string.nav_playlists) {
        override val selectedIcon = Icons.AutoMirrored.Filled.List
        override val unselectedIcon = Icons.AutoMirrored.Outlined.List
    }

    @Serializable
    data object Downloads : NavKeyMain(R.string.nav_downloads) {
        override val selectedIcon = Icons.Filled.Download
        override val unselectedIcon = Icons.Outlined.Download
    }

    @Serializable
    data object Settings : NavKeyMain(R.string.nav_settings) {
        override val selectedIcon = Icons.Filled.Settings
        override val unselectedIcon = Icons.Outlined.Settings
    }
}

sealed interface NavKeyDownload : NavKey {
    @Serializable data object Search : NavKeyDownload

    @Serializable data object History : NavKeyDownload

    @Serializable data class SearchResult(val query: String, val type: SearchType) : NavKeyDownload

    @Serializable data class Module(val moduleId: Int) : NavKeyDownload
}

sealed interface NavKeyPreferences : NavKey {
    @Serializable data object Preferences : NavKeyPreferences

    @Serializable data object About : NavKeyPreferences

    @Serializable data object Formats : NavKeyPreferences
}
