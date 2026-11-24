package com.lossydragon.modplayer.ui.screens.downloads.components

import android.icu.text.CompactDecimalFormat
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.selection.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.res.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.tooling.preview.*
import androidx.compose.ui.unit.*
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.model.Artist
import com.lossydragon.modplayer.model.ArtistInfo
import com.lossydragon.modplayer.model.License
import com.lossydragon.modplayer.model.Module
import com.lossydragon.modplayer.model.ModuleResult
import com.lossydragon.modplayer.model.Sponsor
import com.lossydragon.modplayer.model.SponsorDetails
import com.lossydragon.modplayer.ui.theme.AppTheme
import com.lossydragon.modplayer.util.annotatedLinkString
import com.lossydragon.modplayer.util.formatSize
import com.lossydragon.modplayer.util.formattedComment
import com.lossydragon.modplayer.util.formattedInstruments
import com.lossydragon.modplayer.util.fromHtml
import java.util.Locale

private val HeroShape = RoundedCornerShape(28.dp)
private val SectionShape = RoundedCornerShape(20.dp)

@Composable
internal fun ModuleDetailLayout(
    moduleResult: ModuleResult,
    modifier: Modifier = Modifier,
    isDownloaded: Boolean = false
) {
    val module = moduleResult.module
    val scrollState = rememberScrollState()

    // Random swaps the module in place; start the new one from the top.
    LaunchedEffect(module.filename) { scrollState.scrollTo(0) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ModuleHero(module = module, isDownloaded = isDownloaded)

        ModuleStats(module = module)

        SectionCard(
            title = stringResource(R.string.module_detail_license),
            content = {
                key(module.filename) { LicenseContent(license = module.license) }
            }
        )

        val artistNotes = remember(module) {
            module.artistInfo.artist.mapNotNull { artist ->
                artist.module_data.module_description.fromHtml()
                    .takeIf { it.isNotBlank() }
                    ?.let { artist.alias.trim() to it }
            }
        }
        if (artistNotes.isNotEmpty()) {
            SectionCard(
                title = stringResource(R.string.module_detail_artist_notes),
                content = {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            artistNotes.forEach { (alias, notes) ->
                                Column {
                                    if (artistNotes.size > 1 && alias.isNotEmpty()) {
                                        Text(
                                            text = alias,
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                    }
                                    Text(
                                        text = notes,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }

        val comment = remember(module) { module.formattedComment }
        if (comment.isNotBlank()) {
            SectionCard(
                title = stringResource(R.string.module_detail_song_message),
                content = { MonoSpaceText(text = comment) }
            )
        }

        val instruments = remember(module) { module.formattedInstruments }
        if (instruments.isNotBlank()) {
            SectionCard(
                title = stringResource(R.string.module_detail_instruments),
                content = { MonoSpaceText(text = instruments) }
            )
        }

        if (moduleResult.hasSponsor) {
            val sponsor = moduleResult.sponsor.details
            SectionCard(
                title = stringResource(R.string.module_detail_sponsor),
                content = {
                    Text(
                        text = annotatedLinkString(sponsor.text, sponsor.link),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            )
        }
    }
}

/** Format badge, title, artist link and quick facts. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ModuleHero(module: Module, isDownloaded: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = HeroShape,
        content = {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = {
                    val title = module.songtitle.ifBlank { module.filename }
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    if (module.filename != title) {
                        Text(
                            text = module.filename,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = annotatedLinkString(
                            text = stringResource(R.string.module_detail_artist, module.artist),
                            url = module.infopage,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(
                            space = 8.dp,
                            alignment = Alignment.CenterHorizontally,
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        content = {
                            if (isDownloaded) {
                                InfoPill(
                                    text = stringResource(R.string.module_detail_downloaded),
                                    icon = Icons.Default.Check,
                                    highlighted = true,
                                )
                            }
                            if (module.format.isNotBlank()) {
                                InfoPill(text = module.format)
                            }
                            InfoPill(text = module.bytes.toLong().formatSize())
                            if (module.channels > 0) {
                                InfoPill(
                                    text = pluralStringResource(
                                        R.plurals.module_detail_channels,
                                        module.channels,
                                        module.channels,
                                    )
                                )
                            }
                            val genre = module.genretext.trim()
                            if (genre.isNotEmpty() && !genre.equals("n/a", ignoreCase = true)) {
                                InfoPill(text = genre)
                            }
                            val date = module.date.trim()
                            if (date.isNotEmpty()) {
                                InfoPill(text = date)
                            }
                        }
                    )
                }
            )
        }
    )
}

@Composable
private fun InfoPill(
    text: String,
    icon: ImageVector? = null,
    highlighted: Boolean = false
) {
    Surface(
        color = if (highlighted) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (highlighted) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        shape = CircleShape,
        content = {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                content = {
                    icon?.let {
                        Icon(
                            modifier = Modifier.size(16.dp),
                            imageVector = it,
                            contentDescription = null,
                        )
                    }
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            )
        }
    )
}

/** Download, favourite and rating counts as a row of tiles; absent when the archive has none. */
@Composable
private fun ModuleStats(module: Module) {
    val ratings = module.overallRatings
    val stats = buildList {
        if (module.hits > 0) {
            add(module.hits.compact() to stringResource(R.string.module_detail_stat_downloads))
        }
        if (module.favourites.favoured > 0) {
            add(
                module.favourites.favoured.compact() to
                    stringResource(R.string.module_detail_stat_favourites)
            )
        }
        if (ratings.comment_total > 0 && ratings.comment_rating > 0.0) {
            add(
                "%.1f".format(ratings.comment_rating) to
                    stringResource(R.string.module_detail_stat_rating)
            )
        }
        if (ratings.review_total > 0 && ratings.review_rating > 0) {
            add(
                ratings.review_rating.toString() to
                    stringResource(R.string.module_detail_stat_review)
            )
        }
    }
    if (stats.isEmpty()) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = {
            stats.forEach { (value, label) ->
                Surface(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = SectionShape,
                    content = {
                        Column(
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            content = {
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1,
                                )
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        )
                    }
                )
            }
        }
    )
}

/** 1234567 becomes "1.2M" in the user's locale, so big counts fit a narrow tile. */
private fun Int.compact(): String = CompactDecimalFormat
    .getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT)
    .format(this)

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = SectionShape,
        content = {
            Column(
                modifier = Modifier.padding(16.dp),
                content = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    content()
                }
            )
        }
    )
}

/** License link plus its statement, collapsed to two lines until expanded. */
@Composable
private fun ColumnScope.LicenseContent(license: License) {
    var isExpanded by remember { mutableStateOf(false) }
    var overflows by remember { mutableStateOf(false) }

    Text(
        text = annotatedLinkString(license.title, license.legalurl),
        style = MaterialTheme.typography.titleMedium,
    )

    if (license.description.isNotBlank()) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            modifier = Modifier.animateContentSize(),
            text = license.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (isExpanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!isExpanded) overflows = it.hasVisualOverflow },
        )
        if (overflows) {
            TextButton(
                modifier = Modifier.align(Alignment.End),
                onClick = { isExpanded = !isExpanded },
                content = {
                    Text(
                        text = if (isExpanded) {
                            stringResource(R.string.show_less)
                        } else {
                            stringResource(R.string.show_more)
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun MonoSpaceText(text: String) {
    SelectionContainer {
        Text(
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            text = text
        )
    }
}

@Preview
@Composable
private fun Preview_MonoSpaceText() {
    AppTheme {
        Surface {
            MonoSpaceText(text = stringResource(R.string.app_name))
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface {
            ModuleDetailLayout(
                modifier = Modifier.padding(horizontal = 16.dp),
                moduleResult = ModuleResult(
                    sponsor = Sponsor(
                        details = SponsorDetails(text = "Some Sponsor Text")
                    ),
                    module = Module(
                        filename = "tomorrow_by_kh.mod",
                        songtitle = "Tomorrow",
                        bytes = 669669,
                        channels = 4,
                        genretext = "Demo Style",
                        format = "XM",
                        artistInfo = ArtistInfo(artist = listOf(Artist(alias = "Some Artist"))),
                        infopage = "",
                        license = License(
                            title = "Some License Title",
                            legalurl = "",
                            description = "Some License Description ".repeat(12)
                        ),
                        comment = "Some Comment",
                        instruments = buildAnnotatedString {
                            repeat(20) {
                                append("Some Instrument $it\n")
                            }
                        }.toString()
                    )
                )
            )
        }
    }
}
