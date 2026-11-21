package com.lossydragon.modplayer.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import org.koin.compose.viewmodel.koinViewModel

/**
 * A Koin ViewModel owned by the host activity, so it outlives nav entries and tab switches.
 * Falls back to the local owner where there is no activity (previews).
 */
@Composable
inline fun <reified T : ViewModel> activityViewModel(): T {
    val owner = LocalActivity.current as? ViewModelStoreOwner
        ?: checkNotNull(LocalViewModelStoreOwner.current) { "No ViewModelStoreOwner" }
    return koinViewModel(viewModelStoreOwner = owner)
}
