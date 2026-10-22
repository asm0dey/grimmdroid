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

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class PublicSettings(
    val oidcEnabled: Boolean = false,
    val remoteAuthEnabled: Boolean = false,
    val oidcForceOnlyMode: Boolean = false,
    val oidcProviderDetails: OidcProviderDetails? = null,
)

@Serializable
data class OidcProviderDetails(
    val providerName: String? = null,
    val clientId: String,
    val issuerUri: String,
    val scopes: String? = null,
)

// Public GET /api/v1/healthcheck, a SuccessResponse wrapper.
@Serializable
data class Healthcheck(
    val data: HealthData,
)

@Serializable
data class HealthData(
    val version: String,
)

@Serializable
data class VersionInfo(
    val current: String,
    val latest: String? = null,
)

// Server AccessTokenDto; expires is the access-token lifetime in seconds.
@Serializable
data class Tokens(
    val accessToken: String,
    val refreshToken: String,
    val expires: Long? = null,
    val isDefaultPassword: Boolean? = null,
) {
    override fun toString() = "Tokens(accessToken=***, refreshToken=***, expires=$expires, isDefaultPassword=$isDefaultPassword)"
}

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
) {
    override fun toString() = "LoginRequest(username=$username, password=***)"
}

@Serializable
data class RefreshRequest(
    val refreshToken: String,
) {
    override fun toString() = "RefreshRequest(refreshToken=***)"
}

// Lombok's `boolean isAdmin` getter makes Jackson emit "admin"; accept both spellings.
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AppUserInfo(
    @JsonNames("admin") val isAdmin: Boolean = false,
    val canDownload: Boolean = false,
    val canUpload: Boolean = false,
)

@Serializable
data class AppPage<T>(
    val content: List<T> = emptyList(),
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val hasNext: Boolean,
)

// Instants are kept as ISO-8601 strings, LocalDates as "yyyy-MM-dd" strings.
@Serializable
data class BookSummaryDto(
    val id: Long,
    val title: String? = null,
    val authors: List<String> = emptyList(),
    val seriesName: String? = null,
    val seriesNumber: Float? = null,
    val libraryId: Long? = null,
    val addedOn: String? = null,
    val lastReadTime: String? = null,
    val readProgress: Float? = null,
    val readStatus: String? = null,
    val primaryFileId: Long? = null,
    val primaryFileType: String? = null,
    val primaryFileName: String? = null,
    val coverUpdatedOn: String? = null,
    val audiobookCoverUpdatedOn: String? = null,
    val fileSizeKb: Long? = null,
    val pageCount: Int? = null,
    val language: String? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
    val categories: List<String> = emptyList(),
)

@Serializable
data class BookDetailDto(
    val id: Long,
    val title: String? = null,
    val subtitle: String? = null,
    val description: String? = null,
    val authors: List<String> = emptyList(),
    val seriesName: String? = null,
    val seriesNumber: Float? = null,
    val libraryId: Long? = null,
    val libraryName: String? = null,
    val addedOn: String? = null,
    val lastReadTime: String? = null,
    val readProgress: Float? = null,
    val readStatus: String? = null,
    val primaryFileType: String? = null,
    val fileTypes: List<String> = emptyList(),
    val files: List<BookFileDto> = emptyList(),
    val coverUpdatedOn: String? = null,
    val audiobookCoverUpdatedOn: String? = null,
    val pageCount: Int? = null,
    val language: String? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
    val categories: List<String> = emptyList(),
)

// Same Lombok/Jackson naming as AppUserInfo: `boolean isPrimary` is emitted as "primary".
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BookFileDto(
    val id: Long,
    val fileName: String? = null,
    val bookType: String? = null,
    val extension: String? = null,
    val fileSizeKb: Long? = null,
    @JsonNames("primary") val isPrimary: Boolean = false,
)

@Serializable
data class LibraryDto(
    val id: Long,
    val name: String,
    val bookCount: Long = 0,
    val watch: Boolean = false,
    val paths: List<LibraryPathDto> = emptyList(),
)

@Serializable
data class LibraryPathDto(
    val id: Long,
    val path: String? = null,
)

@Serializable
data class ShelfDto(
    val id: Long,
    val name: String,
    val bookCount: Int = 0,
)

@Serializable
data class MagicShelfDto(
    val id: Long,
    val name: String,
)

@Serializable
data class BookProgressDto(
    val epubProgress: EpubProgressDto? = null,
    val koreaderProgress: KoreaderProgressDto? = null,
)

@Serializable
data class EpubProgressDto(
    val cfi: String? = null,
    val href: String? = null,
    val percentage: Float? = null,
    val updatedAt: String? = null,
)

@Serializable
data class KoreaderProgressDto(
    val percentage: Float? = null,
    val lastSyncTime: String? = null,
)

@Serializable
data class UpdateProgressDto(
    val fileProgress: FileProgressDto,
)

@Serializable
data class FileProgressDto(
    val bookFileId: Long,
    val positionData: String? = null,
    val positionHref: String? = null,
    val progressPercent: Float,
)

@Serializable
data class BookmarkDto(
    val id: Long,
    val bookId: Long,
    val cfi: String? = null,
    val title: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class CreateBookmarkDto(
    val bookId: Long,
    val cfi: String,
    val title: String? = null,
)

@Serializable
data class CustomFontDto(
    val id: Long,
    val fontName: String,
    val format: String,
    val fileSize: Long? = null,
)
