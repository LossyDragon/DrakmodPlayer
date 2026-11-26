package com.lossydragon.modplayer.ui.screens.browser.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Composable
internal fun BreadCrumbs(
    breadcrumbs: ImmutableList<String>,
    modifier: Modifier = Modifier,
    onCrumbClick: (Int) -> Unit
) {
    val scrollState = rememberLazyListState()

    // Auto-scroll to end
    LaunchedEffect(breadcrumbs.size) {
        if (breadcrumbs.isNotEmpty()) {
            scrollState.animateScrollToItem(breadcrumbs.lastIndex)
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
        content = {
            LazyRow(
                modifier = modifier.fillMaxWidth(),
                state = scrollState,
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(horizontal = 8.dp),
                content = {
                    itemsIndexed(breadcrumbs) { index, crumb ->
                        val isLast = index == breadcrumbs.lastIndex
                        AssistChip(
                            modifier = Modifier.padding(horizontal = 2.dp),
                            enabled = !isLast,
                            colors = AssistChipDefaults.assistChipColors(
                                disabledContainerColor =
                                    MaterialTheme.colorScheme.secondaryContainer,
                                disabledLabelColor =
                                    MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                            border = if (isLast) {
                                null
                            } else {
                                AssistChipDefaults.assistChipBorder(enabled = true)
                            },
                            onClick = { onCrumbClick(index) },
                            label = {
                                Text(
                                    text = crumb.ifBlank { stringResource(R.string.root_folder) },
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            trailingIcon = if (!isLast) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = stringResource(
                                            R.string.desc_breadcrumb
                                        ),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            )
        }
    )
}

@Preview
@Composable
private fun Preview() {
    AppTheme {
        BreadCrumbs(
            breadcrumbs = listOf("AAA", "BBB", "CCC", "DDD", "EEE", "Mod Player").toImmutableList(),
            onCrumbClick = {},
        )
    }
}
