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

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.asm0dey.grimmdroid.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface FolderResult {
    /** No folder chosen yet: the UI launches `OpenDocumentTree` and calls [FolderExport.setFolder]. */
    data object NeedsFolder : FolderResult

    /** The chosen folder's permission was revoked or the folder is gone. */
    data object FolderLost : FolderResult

    data class Saved(
        val folderName: String,
    ) : FolderResult
}

fun interface FolderSaver {
    suspend fun save(
        accountId: Long,
        file: File,
    ): FolderResult
}

class FolderExport(
    private val context: Context,
    private val settings: SettingsStore,
) : FolderSaver {
    suspend fun setFolder(
        accountId: Long,
        uri: Uri,
    ) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(uri, flags)
        val old = settings.folderUri(accountId).first()
        if (old != null && old != uri) {
            try {
                context.contentResolver.releasePersistableUriPermission(old, flags)
            } catch (_: SecurityException) {
                // Already gone.
            }
        }
        settings.setFolderUri(accountId, uri)
    }

    /** Copies [file] into the account's folder; a failed copy or create throws IOException and leaves no partial file. */
    override suspend fun save(
        accountId: Long,
        file: File,
    ): FolderResult {
        val uri = settings.folderUri(accountId).first() ?: return FolderResult.NeedsFolder
        return withContext(Dispatchers.IO) {
            val granted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
            if (!granted) return@withContext FolderResult.FolderLost
            val tree = DocumentFile.fromTreeUri(context, uri)
            if (tree == null || !tree.exists() || !tree.canWrite()) return@withContext FolderResult.FolderLost
            val name = uniqueName(tree.listFiles().mapNotNull { it.name }.toSet(), file.name)
            val doc = tree.createFile(mimeFor(name), name) ?: throw IOException("Cannot create $name")
            try {
                val out = context.contentResolver.openOutputStream(doc.uri) ?: throw IOException("Cannot open ${doc.uri}")
                out.use { o -> file.inputStream().use { it.copyTo(o) } }
            } catch (e: IOException) {
                doc.delete()
                throw e
            }
            FolderResult.Saved(tree.name ?: "")
        }
    }
}
