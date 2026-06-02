package com.lossydragon.modplayer.ui.screens.downloads.components

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.*
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
import com.lossydragon.modplayer.ui.util.annotatedLinkString
import com.lossydragon.modplayer.util.formatSize
import kotlinx.coroutines.launch

@Composable
internal fun ModuleDetailLayout(
    moduleResult: ModuleResult,
    modifier: Modifier = Modifier
) {
    val resource = LocalResources.current
    val expandTextColor = MaterialTheme.colorScheme.primary

    val module = moduleResult.module
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var moduleFile by rememberSaveable { mutableStateOf(module.filename) }

    var textLayoutResultState by remember { mutableStateOf<TextLayoutResult?>(null) }
    var isExpanded by remember { mutableStateOf(false) }

    val licenseDescription by remember { mutableStateOf(module.license.description) }
    var licenseText by remember { mutableStateOf(AnnotatedString(licenseDescription)) }
    LaunchedEffect(textLayoutResultState) {
        when {
            isExpanded -> {
                licenseText = buildAnnotatedString {
                    append(licenseDescription)
                    withStyle(
                        style = SpanStyle(
                            color = expandTextColor,
                            fontStyle = FontStyle.Italic
                        ),
                        block = {
                            val text = resource.getString(R.string.show_less)
                            append(text)
                        }
                    )
                }
            }

            !isExpanded && textLayoutResultState!!.hasVisualOverflow -> {
                val lastCharIndex = textLayoutResultState!!.getLineEnd(1, true)
                val showMoreString = resource.getString(R.string.show_more)
                val adjustedText = module.license.description
                    .take(lastCharIndex)
                    .dropLast(showMoreString.length)
                    .dropLastWhile { it == ' ' || it == '.' }

                licenseText = buildAnnotatedString {
                    val text = resource.getString(
                        R.string.module_detail_adjusted_text,
                        adjustedText
                    )
                    append(text)
                    withStyle(
                        style = SpanStyle(
                            color = expandTextColor,
                            fontStyle = FontStyle.Italic
                        ),
                        block = { append(showMoreString) }
                    )
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Scroll to the top on a new module.
        if (module.filename != moduleFile) {
            moduleFile = module.filename
            LaunchedEffect(scrollState) {
                scope.launch {
                    scrollState.scrollTo(0)
                }
            }
        }

        val size = module.bytes.toLong().formatSize()
        val info = stringResource(R.string.module_detail_by, module.format, module.artist, size)

        Spacer(modifier = Modifier.height(10.dp))
        // Title
        Text(text = module.songtitle)
        Spacer(modifier = Modifier.height(5.dp))
        // Filename
        Text(text = module.filename, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(10.dp))
        // Info
        Text(
            text = annotatedLinkString(info, module.infopage),
            style = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        )
        Spacer(modifier = Modifier.height(10.dp))
        // License
        HeaderText(text = stringResource(R.string.module_detail_license))
        Spacer(modifier = Modifier.height(5.dp))
        // Licence Link
        Text(
            text = annotatedLinkString(module.license.title, module.license.legalurl),
            style = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
        )
        Spacer(modifier = Modifier.height(5.dp))
        // Licence Statement
        Text(
            modifier = modifier
                .padding(start = 10.dp, end = 10.dp)
                .clickable { isExpanded = !isExpanded }
                .animateContentSize(),
            text = licenseText,
            textAlign = TextAlign.Center,
            fontSize = 14.sp,
            maxLines = if (isExpanded) Int.MAX_VALUE else 2,
            onTextLayout = { textLayoutResultState = it }
        )
        Spacer(modifier = Modifier.height(10.dp))
        // Comment
        if (module.comment.isNotEmpty()) {
            // Song Message
            HeaderText(text = stringResource(R.string.module_detail_song_message))
            Spacer(modifier = Modifier.height(10.dp))
            // Song Message Content
            MonoSpaceText(text = module.formattedComment)
            Spacer(modifier = Modifier.height(10.dp))
        }
        // Instruments
        HeaderText(text = stringResource(R.string.module_detail_instruments))
        Spacer(modifier = Modifier.height(10.dp))
        // Instruments Content
        MonoSpaceText(text = module.formattedInstruments)
        Spacer(modifier = Modifier.height(10.dp))
        // Sponsor
        if (moduleResult.hasSponsor) {
            val sponsor = moduleResult.sponsor.details
            HeaderText(text = stringResource(R.string.module_detail_sponsor))
            Spacer(modifier = Modifier.height(10.dp))
            // Sponsor Content
            Text(
                text = annotatedLinkString(sponsor.text, sponsor.link),
                style = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun MonoSpaceText(text: String) {
    Text(
        modifier = Modifier.padding(start = 10.dp, end = 10.dp),
        fontFamily = FontFamily.Monospace,
        fontSize = 14.sp,
        text = text
    )
}

@Composable
private fun HeaderText(text: String) {
    Text(
        fontWeight = FontWeight.Bold,
        fontStyle = FontStyle.Italic,
        text = text
    )
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
private fun Preview_HeaderText() {
    AppTheme {
        Surface {
            HeaderText(text = stringResource(R.string.app_name))
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        Surface {
            ModuleDetailLayout(
                moduleResult = ModuleResult(
                    sponsor = Sponsor(
                        details = SponsorDetails(
                            text = "Some Sponsor Text"
                        )
                    ),
                    module = Module(
                        filename = "tomorrow_by_kh.mod",
                        bytes = 669669,
                        format = "XM",
                        artistInfo = ArtistInfo(artist = listOf(Artist(alias = "Some Artist"))),
                        infopage = "",
                        license = License(
                            title = "Some License Title",
                            legalurl = "",
                            description = "Some License Description"
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
