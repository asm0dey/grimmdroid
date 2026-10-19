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
package io.github.asm0dey.grimmdroid.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

const val FILES_AUTHORITY = "io.github.asm0dey.grimmdroid.files"

enum class ExportResult { Started, NoApp }

class Exporter(
    private val context: Context,
) {
    /** Needs a FileProvider-covered [file] (`filesDir/books/`); anything else throws IllegalArgumentException. */
    fun openWith(file: File): ExportResult {
        val uri = FileProvider.getUriForFile(context, FILES_AUTHORITY, file)
        val view =
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeFor(file.name))
                .also { it.clipData = ClipData.newRawUri("", uri) }
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context.packageManager.queryIntentActivities(view, 0).isEmpty()) return ExportResult.NoApp
        context.startActivity(Intent.createChooser(view, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ExportResult.Started
    }

    fun share(
        file: File,
        title: String,
    ) {
        val uri = FileProvider.getUriForFile(context, FILES_AUTHORITY, file)
        val send =
            Intent(Intent.ACTION_SEND)
                .setType(mimeFor(file.name))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TITLE, title)
                .also { it.clipData = ClipData.newRawUri("", uri) }
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
