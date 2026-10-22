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
package io.github.asm0dey.grimmdroid.upload

import io.github.asm0dey.grimmdroid.api.GrimmoryApi
import io.github.asm0dey.grimmdroid.api.NoUploadPermission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

data class UploadTarget(
    val libraryId: Long,
    val pathId: Long,
    val watched: Boolean,
)

sealed interface UploadResult {
    data class Found(
        val bookId: Long,
    ) : UploadResult

    data object NotPickedUp : UploadResult

    data object NoPermission : UploadResult

    data class Failed(
        val error: Throwable,
    ) : UploadResult
}

/**
 * Uploads a book and finds it on the server. The upload answers no book id and the server renames the file, so a new
 * book is spotted by its id appearing; an already-catalogued title of the same size (a duplicate) is the existing book.
 */
class BookUploader(
    private val api: GrimmoryApi,
    private val existingBookId: suspend (title: String, sizeKb: Long) -> Long?,
) {
    suspend fun upload(
        file: File,
        fileName: String,
        target: UploadTarget,
        onProgress: (Float) -> Unit,
    ): UploadResult {
        val existing = epubTitle(file)?.let { existingBookId(it, file.length() / 1024) }
        val before = api.bookIds().toSet()
        try {
            api.upload(file, fileName, target.libraryId, target.pathId, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (_: NoUploadPermission) {
            return UploadResult.NoPermission
        } catch (e: Exception) {
            // The server refuses a file whose renamed path already exists: a duplicate, so the existing book stands.
            return existing?.let { UploadResult.Found(it) } ?: UploadResult.Failed(e)
        }
        if (existing != null) return UploadResult.Found(existing)
        if (!target.watched) api.refreshLibrary(target.libraryId)
        val found =
            withTimeoutOrNull(if (target.watched) WATCHED_WAIT_MS else RESCAN_WAIT_MS) {
                while (true) {
                    // shortcut: several new books at once → newest id wins; match by title if that bites
                    // A poll can fail while the server rescans or the network blips; the upload itself already worked.
                    val ids =
                        try {
                            api.bookIds()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    ids?.let { (it - before).maxOrNull() }?.let { return@withTimeoutOrNull it }
                    delay(POLL_MS)
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
        return found?.let { UploadResult.Found(it) } ?: UploadResult.NotPickedUp
    }

    private companion object {
        const val POLL_MS = 2_000L
        const val WATCHED_WAIT_MS = 30_000L
        const val RESCAN_WAIT_MS = 180_000L
    }
}
