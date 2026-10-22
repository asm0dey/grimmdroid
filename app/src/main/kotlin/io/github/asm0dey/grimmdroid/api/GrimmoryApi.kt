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
package io.github.asm0dey.grimmdroid.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.delete
import io.ktor.client.request.forms.ChannelProvider
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.util.cio.readChannel
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class NoUploadPermission : IOException("Your account can't upload books")

// Paths are relative so they resolve against authedClient's base URL (which may carry a sub-path).
class GrimmoryApi(
    private val client: HttpClient,
) {
    suspend fun version(): VersionInfo = client.get("api/v1/version").body()

    suspend fun me(): AppUserInfo = client.get("api/v1/app/users/me").body()

    suspend fun books(
        page: Int,
        size: Int = 50,
    ): AppPage<BookSummaryDto> =
        client
            .get("api/v1/app/books") {
                parameter("page", page)
                parameter("size", size)
                parameter("sort", "addedOn")
                parameter("dir", "asc")
            }.body()

    suspend fun bookIds(
        shelfId: Long? = null,
        magicShelfId: Long? = null,
    ): List<Long> =
        client
            .get("api/v1/app/books/ids") {
                parameter("shelfId", shelfId)
                parameter("magicShelfId", magicShelfId)
            }.body()

    suspend fun book(id: Long): BookDetailDto = client.get("api/v1/app/books/$id").body()

    // Grimmory v3.5.0's app endpoint answers 500 for any library (lazy libraryPaths); the web one lists the same libraries.
    suspend fun libraries(): List<LibraryDto> =
        try {
            client.get("api/v1/app/libraries").body()
        } catch (_: ServerResponseException) {
            client.get("api/v1/libraries").body()
        }

    // Only the web endpoint carries `watch`; the app one sends paths but not whether the folder is watched.
    suspend fun uploadLibraries(): List<LibraryDto> = client.get("api/v1/libraries").body()

    /** Answers 204 with no body: the server only moves the file into the folder; its watcher (or a rescan) indexes it. */
    suspend fun upload(
        file: File,
        fileName: String,
        libraryId: Long,
        pathId: Long,
        onProgress: (Float) -> Unit,
    ) {
        try {
            client.submitFormWithBinaryData(
                "api/v1/files/upload",
                formData {
                    append("libraryId", libraryId.toString())
                    append("pathId", pathId.toString())
                    append(
                        "file",
                        ChannelProvider(file.length()) { file.readChannel() },
                        Headers.build {
                            append(HttpHeaders.ContentDisposition, "filename=\"${fileName.replace("\"", "")}\"")
                            append(HttpHeaders.ContentType, "application/epub+zip")
                        },
                    )
                },
            ) { onUpload { sent, total -> if (total != null && total > 0) onProgress(sent.toFloat() / total) } }
        } catch (e: ClientRequestException) {
            throw if (e.response.status == HttpStatusCode.Forbidden) NoUploadPermission() else e
        }
    }

    // 403 means the account can't manage libraries: the folder watcher is then the only way in, so not an error.
    suspend fun refreshLibrary(libraryId: Long) {
        try {
            client.put("api/v1/libraries/$libraryId/refresh")
        } catch (e: ClientRequestException) {
            if (e.response.status != HttpStatusCode.Forbidden) throw e
        }
    }

    suspend fun shelves(): List<ShelfDto> = client.get("api/v1/app/shelves").body()

    suspend fun magicShelves(): List<MagicShelfDto> = client.get("api/v1/app/shelves/magic").body()

    suspend fun continueReading(): List<BookSummaryDto> = client.get("api/v1/app/books/continue-reading").body()

    suspend fun recentlyAdded(): List<BookSummaryDto> = client.get("api/v1/app/books/recently-added").body()

    suspend fun progress(bookId: Long): BookProgressDto = client.get("api/v1/app/books/$bookId/progress").body()

    suspend fun putProgress(
        bookId: Long,
        body: UpdateProgressDto,
    ) {
        client.put("api/v1/app/books/$bookId/progress") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    }

    suspend fun bookmarks(bookId: Long): List<BookmarkDto> = client.get("api/v1/bookmarks/book/$bookId").body()

    suspend fun createBookmark(body: CreateBookmarkDto): BookmarkDto =
        client
            .post("api/v1/bookmarks") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }.body()

    suspend fun deleteBookmark(id: Long) {
        client.delete("api/v1/bookmarks/$id")
    }

    // The server answers 403 to accounts without the font permission: null, not an error, so fonts already kept stay.
    suspend fun customFonts(): List<CustomFontDto>? =
        try {
            client.get("api/v1/custom-fonts").body()
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.Forbidden) null else throw e
        }

    /** Streams the font to a temp file beside [target], then renames it into place. */
    suspend fun downloadFont(
        id: Long,
        target: File,
    ) {
        val tmp = File(target.parentFile, "${target.name}.part")
        try {
            client.prepareGet("api/v1/custom-fonts/$id/file").execute { response ->
                withContext(Dispatchers.IO) { tmp.outputStream().use { response.bodyAsChannel().copyTo(it) } }
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp.toPath())
        }
    }
}
