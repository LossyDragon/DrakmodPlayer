package com.lossydragon.modplayer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lossydragon.modplayer.db.AppPreferences
import kotlinx.coroutines.flow.Flow
import org.koin.compose.koinInject

/** The injected preferences, or a throwaway instance in previews. */
@Composable
fun rememberAppPreferences(): AppPreferences = if (LocalView.current.isInEditMode) {
    val context = LocalContext.current
    remember(context) { AppPreferences(context) }
} else {
    koinInject()
}

/** Collects a preference flow that is built once, not on every recomposition. */
@Composable
fun <T> AppPreferences.collectPref(initial: T, flow: AppPreferences.() -> Flow<T>): State<T> =
    remember(this) { flow() }.collectAsStateWithLifecycle(initial)
