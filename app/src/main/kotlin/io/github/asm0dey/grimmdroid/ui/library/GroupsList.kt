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
package io.github.asm0dey.grimmdroid.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.asm0dey.grimmdroid.R
import io.github.asm0dey.grimmdroid.catalog.Group

/** Authors or series with their read counts; tapping one opens its page. */
@Composable
fun GroupsList(
    groups: List<Group>,
    onGroup: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (groups.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_groups), Modifier.padding(32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier.fillMaxSize()) {
        items(groups, key = { it.name }) { g ->
            ListItem(
                headlineContent = { Text(g.name) },
                supportingContent = { Text(pluralStringResource(R.plurals.read_of_total, g.total, g.readCount, g.total)) },
                trailingContent = { Text(pluralStringResource(R.plurals.book_count, g.total, g.total)) },
                modifier = Modifier.clickable(role = Role.Button) { onGroup(g.name) },
            )
        }
    }
}
