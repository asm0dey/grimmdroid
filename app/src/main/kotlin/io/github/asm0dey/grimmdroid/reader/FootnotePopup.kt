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
package io.github.asm0dey.grimmdroid.reader

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.fromHtml
import io.github.asm0dey.grimmdroid.R

/** A footnote over the page, in the reader palette ([ReaderColors]). [html] is Readium's sanitised note content. */
@Composable
fun FootnotePopup(
    html: String,
    onDismiss: () -> Unit,
) {
    // Links inside a note point into the book, not the web: they stay inert.
    val text = remember(html) { AnnotatedString.fromHtml(html, linkInteractionListener = LinkInteractionListener { }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_action)) } },
        title = { Text(stringResource(R.string.footnote)) },
        text = { Text(text, Modifier.verticalScroll(rememberScrollState())) },
    )
}
