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

import io.github.asm0dey.grimmdroid.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.File

enum class AfterDownloadAction { NOTHING, OPEN, SAVE_TO_FOLDER }

sealed interface AfterDownloadOutcome {
    data object Nothing : AfterDownloadOutcome

    /** Android blocks activity starts from the background: the completion notification's Open action is the way in. */
    data object OpenViaNotification : AfterDownloadOutcome

    data class Saved(
        val folderName: String,
    ) : AfterDownloadOutcome

    data object NeedsFolder : AfterDownloadOutcome

    data object FolderLost : AfterDownloadOutcome

    /** The copy failed; the download itself is fine. */
    data object SaveFailed : AfterDownloadOutcome
}

class AfterDownload(
    private val settings: SettingsStore,
    private val folderExport: FolderSaver,
) {
    suspend fun run(
        accountId: Long,
        file: File,
    ): AfterDownloadOutcome =
        when (settings.afterDownload.first()) {
            AfterDownloadAction.NOTHING -> {
                AfterDownloadOutcome.Nothing
            }

            AfterDownloadAction.OPEN -> {
                AfterDownloadOutcome.OpenViaNotification
            }

            AfterDownloadAction.SAVE_TO_FOLDER -> {
                when (val r = saveOrNull(accountId, file)) {
                    null -> AfterDownloadOutcome.SaveFailed
                    FolderResult.NeedsFolder -> AfterDownloadOutcome.NeedsFolder
                    FolderResult.FolderLost -> AfterDownloadOutcome.FolderLost
                    is FolderResult.Saved -> AfterDownloadOutcome.Saved(r.folderName)
                }
            }
        }

    // A failed export must never turn a finished download into a failed worker.
    private suspend fun saveOrNull(
        accountId: Long,
        file: File,
    ): FolderResult? =
        try {
            folderExport.save(accountId, file)
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            FolderResult.FolderLost
        } catch (_: Exception) {
            null
        }
}
