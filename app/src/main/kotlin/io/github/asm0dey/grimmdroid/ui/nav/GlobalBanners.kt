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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.asm0dey.grimmdroid.R
import io.github.asm0dey.grimmdroid.sync.SyncState

data class BannerState(
    val offline: Boolean,
    val syncing: SyncState.Running?,
    val signedOutServer: String?,
    val unsupportedVersion: String?,
)

/** Shown under the top bar. The cached library stays visible below every banner. */
@Composable
fun GlobalBanners(
    state: BannerState,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        state.syncing?.let { running ->
            val description = stringResource(R.string.sync_notification)
            val progressModifier = Modifier.fillMaxWidth().semantics { contentDescription = description }
            // Total is 0 until the first page arrives.
            if (running.total > 0) {
                LinearProgressIndicator(progress = { running.done.toFloat() / running.total }, modifier = progressModifier)
            } else {
                LinearProgressIndicator(progressModifier)
            }
        }
        if (state.offline) {
            Surface(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    stringResource(R.string.offline),
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        state.signedOutServer?.let { server ->
            Surface(
                onClick = onSignIn,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(stringResource(R.string.signed_out_banner, server), Modifier.padding(16.dp, 14.dp))
            }
        }
        state.unsupportedVersion?.let { version ->
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiaryContainer) {
                Text(stringResource(R.string.unsupported_banner, version), Modifier.padding(16.dp, 14.dp))
            }
        }
    }
}
