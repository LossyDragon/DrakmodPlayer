package com.lossydragon.modplayer.ui.screens.browser.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.input.*
import androidx.compose.material.icons.*
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.*
import androidx.compose.ui.tooling.preview.*
import com.lossydragon.modplayer.R
import com.lossydragon.modplayer.model.BrowserSortOrder
import com.lossydragon.modplayer.ui.components.BackButton
import com.lossydragon.modplayer.ui.theme.AppTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BrowserInputField(
    textFieldState: TextFieldState,
    searchBarState: SearchBarState,
    colors: TextFieldColors,
    sortOrder: BrowserSortOrder,
    onSortOrder: (BrowserSortOrder) -> Unit,
    onFolderPick: () -> Unit,
    onFilter: (String) -> Unit
) {
    val scope = rememberCoroutineScope()

    SearchBarDefaults.InputField(
        modifier = Modifier.fillMaxWidth(),
        textFieldState = textFieldState,
        searchBarState = searchBarState,
        colors = colors,
        onSearch = { scope.launch { searchBarState.animateToCollapsed() } },
        placeholder = { Text(text = stringResource(R.string.search_placeholder)) },
        leadingIcon = {
            if (searchBarState.currentValue == SearchBarValue.Expanded) {
                BackButton {
                    scope.launch { searchBarState.animateToCollapsed() }
                    textFieldState.clearText()
                    onFilter("")
                }
            } else {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(R.string.desc_search)
                )
            }
        },
        trailingIcon = {
            if (textFieldState.text.isNotEmpty()) {
                IconButton(
                    onClick = {
                        textFieldState.clearText()
                        onFilter("")
                    },
                    content = {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = stringResource(R.string.desc_search_clear)
                        )
                    }
                )
            } else {
                Row {
                    IconButton(
                        onClick = onFolderPick,
                        content = {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = stringResource(R.string.desc_folder_pick)
                            )
                        }
                    )
                    SortMenu(sortOrder = sortOrder, onSortOrder = onSortOrder)
                }
            }
        },
    )
}

@Composable
private fun SortMenu(
    sortOrder: BrowserSortOrder,
    onSortOrder: (BrowserSortOrder) -> Unit
) {
    var showSortMenu by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { showSortMenu = true },
            content = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Sort,
                    contentDescription = stringResource(R.string.desk_sort_button)
                )
            }
        )
        DropdownMenu(
            expanded = showSortMenu,
            onDismissRequest = { showSortMenu = false },
            shape = MaterialTheme.shapes.small,
            content = {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.name)) },
                    onClick = {
                        onSortOrder(BrowserSortOrder.NAME)
                        showSortMenu = false
                    },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.SortByAlpha, contentDescription = null)
                    },
                    trailingIcon = {
                        if (sortOrder == BrowserSortOrder.NAME) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = null)
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.type)) },
                    onClick = {
                        onSortOrder(BrowserSortOrder.TYPE)
                        showSortMenu = false
                    },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Extension, contentDescription = null)
                    },
                    trailingIcon = {
                        if (sortOrder == BrowserSortOrder.TYPE) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = null)
                        }
                    }
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.size)) },
                    onClick = {
                        onSortOrder(BrowserSortOrder.SIZE)
                        showSortMenu = false
                    },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.DataArray, contentDescription = null)
                    },
                    trailingIcon = {
                        if (sortOrder == BrowserSortOrder.SIZE) {
                            Icon(imageVector = Icons.Default.Check, contentDescription = null)
                        }
                    }
                )
            }
        )
    }
}

@Preview
@Composable
private fun Preview_Sort() {
    AppTheme {
        Surface {
            SortMenu(
                sortOrder = BrowserSortOrder.SIZE,
                onSortOrder = {}
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun Preview() {
    val searchBarState = rememberSearchBarWithGapState()
    val textFieldState = rememberTextFieldState()
    val appBarWithSearchColors = SearchBarDefaults.appBarWithSearchColors(
        scrolledSearchBarContainerColor = Color.Unspecified,
        scrolledAppBarContainerColor = Color.Unspecified,
    )
    AppTheme {
        Surface {
            BrowserInputField(
                textFieldState = textFieldState,
                searchBarState = searchBarState,
                colors = appBarWithSearchColors.searchBarColors.inputFieldColors,
                sortOrder = BrowserSortOrder.SIZE,
                onSortOrder = {},
                onFolderPick = {},
                onFilter = {},
            )
        }
    }
}
