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
import io.ktor.client.request.get
import io.ktor.client.request.parameter

// Paths are relative so they resolve against authedClient's base URL (which may carry a sub-path).
class GrimmoryApi(
    private val client: HttpClient,
) {
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

    suspend fun libraries(): List<LibraryDto> = client.get("api/v1/app/libraries").body()

    suspend fun shelves(): List<ShelfDto> = client.get("api/v1/app/shelves").body()

    suspend fun magicShelves(): List<MagicShelfDto> = client.get("api/v1/app/shelves/magic").body()

    suspend fun continueReading(): List<BookSummaryDto> = client.get("api/v1/app/books/continue-reading").body()

    suspend fun recentlyAdded(): List<BookSummaryDto> = client.get("api/v1/app/books/recently-added").body()
}
