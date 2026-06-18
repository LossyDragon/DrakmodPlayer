package com.lossydragon.modplayer.ui.screens.preferences

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.dp
import com.lossydragon.modplayer.BuildConfig
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.components.BackButton
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.modplayer.util.annotatedLinkString
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer

@Composable
fun PreferencesAbout(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val libraries by produceLibraries(R.raw.aboutlibraries)

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(text = stringResource(R.string.pref_title_about)) },
                navigationIcon = { BackButton(onBack = onBack) }
            )
        },
    ) { paddingValues ->
        LibrariesContainer(
            libraries = libraries,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            showDescription = true,
            header = {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "Version ${BuildConfig.VERSION_NAME}" +
                                " (${BuildConfig.VERSION_CODE})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = annotatedLinkString(
                                text = "Project Repo (Github)",
                                url = "https://github.com/LossyDragon/DrakmodPlayer",
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
            divider = { HorizontalDivider(modifier = Modifier.fillMaxWidth()) },
        )
    }
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        PreferencesAbout(onBack = {})
    }
}
