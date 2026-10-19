/*
 * Grimmdroid - Android client for the Grimmory book server
 * Copyright (C) 2026 Grimmdroid contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.asm0dey.grimmdroid.ui.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import io.github.asm0dey.grimmdroid.R
import io.github.asm0dey.grimmdroid.ui.library.BookListRow

/** Full-screen search over the local mirror; works offline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    vm: SearchViewModel,
    baseUrl: String?,
    onBook: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val text by vm.query.collectAsState()
    val results = vm.results.collectAsLazyPagingItems()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    SearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = text,
                onQueryChange = { vm.query.value = it },
                onSearch = {},
                expanded = true,
                onExpandedChange = { if (!it) onBack() },
                modifier = Modifier.focusRequester(focus),
                placeholder = { Text(stringResource(R.string.search_books)) },
                leadingIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back_action)) }
                },
            )
        },
        expanded = true,
        onExpandedChange = { if (!it) onBack() },
        // The scaffold already pads for the status bar.
        windowInsets = WindowInsets(0.dp),
    ) {
        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp)) {
            items(results.itemCount, key = results.itemKey { it.book.id }) { i -> BookListRow(results[i], baseUrl, onBook) }
        }
    }
}
