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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.asm0dey.grimmdroid.R
import io.github.asm0dey.grimmdroid.catalog.BookRow

fun coverUrl(
    baseUrl: String,
    bookId: Long,
    coverUpdatedOn: Long?,
): String = "${baseUrl.trimEnd('/')}/api/v1/media/book/$bookId/thumbnail" + (coverUpdatedOn?.let { "?t=$it" } ?: "")

/** Dark tones only: white placeholder text stays at 4.5:1 or more on each (asserted in CoversSpec). */
val PlaceholderTones =
    listOf(
        Color(0xFF7C2D12),
        Color(0xFF1E3A8A),
        Color(0xFF14532D),
        Color(0xFF581C87),
        Color(0xFF831843),
        Color(0xFF134E4A),
        Color(0xFF3F3F46),
        Color(0xFF78350F),
    )

fun placeholderColor(title: String): Color = PlaceholderTones[Math.floorMod(title.hashCode(), PlaceholderTones.size)]

/** A 2:3 tile tinted from the title, showing the title until the thumbnail covers it; a failed load keeps the tile. */
@Composable
fun Cover(
    row: BookRow,
    baseUrl: String?,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
) {
    val book = row.book
    Box(modifier.aspectRatio(2f / 3f).clip(MaterialTheme.shapes.small).background(placeholderColor(book.title))) {
        if (showTitle) {
            Text(
                book.title,
                Modifier.align(Alignment.Center).padding(8.dp),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (baseUrl != null) {
            AsyncImage(
                model = coverUrl(baseUrl, book.id, book.coverUpdatedOn),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
        Row(Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            if (row.downloaded) Badge(R.drawable.ic_check, stringResource(R.string.badge_downloaded))
            if (book.isAudiobook) Badge(R.drawable.ic_headphones, stringResource(R.string.badge_audiobook))
        }
    }
}

@Composable
private fun Badge(
    icon: Int,
    description: String,
) {
    Surface(Modifier.padding(start = 2.dp).size(20.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
        Icon(painterResource(icon), description, Modifier.padding(3.dp))
    }
}

/** Stands in for a row Paging hasn't loaded yet. */
@Composable
fun CoverPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(2f / 3f).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant))
}
