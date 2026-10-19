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
package io.github.asm0dey.grimmdroid.ui.nav

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.github.asm0dey.grimmdroid.R
import io.github.asm0dey.grimmdroid.account.AccountEntity
import io.ktor.http.Url

/** The host the user typed, e.g. "books.example.net"; falls back to the stored URL if it can't be parsed. */
internal fun serverName(baseUrl: String): String = runCatching { Url(baseUrl).host }.getOrNull()?.ifEmpty { null } ?: baseUrl

/** "Synced 5 minutes ago"; under a minute "Synced just now", where DateUtils would say "0 minutes ago". */
internal fun syncedLabel(
    context: Context,
    at: Long,
    now: Long = System.currentTimeMillis(),
): String =
    if (now - at < DateUtils.MINUTE_IN_MILLIS) {
        context.getString(R.string.synced_just_now)
    } else {
        context.getString(R.string.synced_at, DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS))
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(
    accounts: List<AccountEntity>,
    currentId: Long?,
    onSwitch: (Long) -> Unit,
    onAddServer: () -> Unit,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Transparent so list rows sit on the sheet's own surface colour.
    val rowColors = ListItemDefaults.colors(containerColor = Color.Transparent)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        accounts.forEach { account ->
            val current = account.id == currentId
            ListItem(
                headlineContent = { Text(serverName(account.baseUrl)) },
                supportingContent = {
                    if (account.signedOut) {
                        Text("${account.username} · ${stringResource(R.string.signed_out)}", color = MaterialTheme.colorScheme.error)
                    } else {
                        Text(account.username)
                    }
                },
                trailingContent = { if (current) Icon(painterResource(R.drawable.ic_check), contentDescription = null) },
                colors = rowColors,
                modifier =
                    Modifier
                        .semantics { selected = current }
                        .clickable(role = Role.RadioButton) { onSwitch(account.id) },
            )
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.add_server)) },
            leadingContent = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
            colors = rowColors,
            modifier = Modifier.clickable(onClick = onAddServer),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings)) },
            leadingContent = { Icon(painterResource(R.drawable.ic_settings), contentDescription = null) },
            colors = rowColors,
            modifier = Modifier.clickable(onClick = onSettings),
        )
    }
}
