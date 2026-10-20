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
package io.github.asm0dey.grimmdroid.ui.details

import io.github.asm0dey.grimmdroid.catalog.BookEntity
import io.github.asm0dey.grimmdroid.catalog.DownloadEntity
import io.github.asm0dey.grimmdroid.catalog.DownloadState
import java.io.File
import java.util.Locale

/** What the details screen's button area shows, one at a time. */
sealed interface PrimaryAction {
    /** [readable]: the primary file, the one Read downloads, is an EPUB. */
    data class Download(
        val readable: Boolean,
    ) : PrimaryAction

    /** [progress] is 0..1, or -1 when the size is unknown. */
    data class Downloading(
        val progress: Float,
    ) : PrimaryAction

    data class Downloaded(
        val file: File,
        val sizeLabel: String,
        /** The file on the phone is an EPUB the reader can open. */
        val readable: Boolean,
    ) : PrimaryAction

    data object NoPermission : PrimaryAction

    data object Audiobook : PrimaryAction

    data object Offline : PrimaryAction
}

fun primaryAction(
    book: BookEntity,
    canDownload: Boolean,
    isAdmin: Boolean,
    download: DownloadEntity?,
    file: File?,
    online: Boolean,
): PrimaryAction =
    when {
        book.isAudiobook -> {
            PrimaryAction.Audiobook
        }

        !(canDownload || isAdmin) -> {
            PrimaryAction.NoPermission
        }

        download?.state == DownloadState.DONE && file?.exists() == true -> {
            PrimaryAction.Downloaded(file, sizeLabel(download.sizeBytes), file.extension.equals("epub", ignoreCase = true))
        }

        download?.state == DownloadState.QUEUED || download?.state == DownloadState.RUNNING -> {
            PrimaryAction.Downloading(download.progress)
        }

        !online -> {
            PrimaryAction.Offline
        }

        else -> {
            PrimaryAction.Download(book.primaryFileType.equals("epub", ignoreCase = true))
        }
    }

/** Decimal units like Android's Formatter, but usable without a Context: "1.2 MB". */
fun sizeLabel(
    bytes: Long,
    locale: Locale = Locale.getDefault(),
): String {
    if (bytes < 1000) return "$bytes B"
    val units = listOf("kB", "MB", "GB")
    var value = bytes / 1000.0
    var i = 0
    while (value >= 999.95 && i < units.lastIndex) {
        value /= 1000
        i++
    }
    return String.format(locale, "%.1f %s", value, units[i])
}
