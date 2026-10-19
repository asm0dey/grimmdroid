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
package io.github.asm0dey.grimmdroid.catalog

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index

@Entity(primaryKeys = ["accountId", "id"], indices = [Index("accountId", "libraryId")])
data class BookEntity(
    val accountId: Long,
    val id: Long,
    val title: String,
    /** Author names joined with ", ". */
    val authors: String,
    val seriesName: String?,
    val seriesNumber: Float?,
    val libraryId: Long?,
    val addedOn: Long?,
    val lastReadTime: Long?,
    val readProgress: Float?,
    val primaryFileId: Long?,
    val primaryFileType: String?,
    val primaryFileName: String?,
    val fileSizeKb: Long?,
    val coverUpdatedOn: Long?,
    val isAudiobook: Boolean,
    val syncGeneration: Long,
)

@Fts4(contentEntity = BookEntity::class)
@Entity
data class BookFts(
    val title: String,
    val authors: String,
    val seriesName: String?,
)

@Entity(primaryKeys = ["accountId", "id"])
data class LibraryEntity(
    val accountId: Long,
    val id: Long,
    val name: String,
    val bookCount: Int,
)

// A regular and a magic shelf may share an id, so `magic` is part of the key.
@Entity(primaryKeys = ["accountId", "id", "magic"])
data class ShelfEntity(
    val accountId: Long,
    val id: Long,
    val name: String,
    val bookCount: Int,
    val magic: Boolean,
)

@Entity(primaryKeys = ["accountId", "shelfId", "magic", "bookId"], indices = [Index("accountId", "bookId")])
data class ShelfBookCrossRef(
    val accountId: Long,
    val shelfId: Long,
    val magic: Boolean,
    val bookId: Long,
)

enum class DownloadState { QUEUED, RUNNING, DONE, FAILED }

@Entity(primaryKeys = ["accountId", "bookId", "fileId"])
data class DownloadEntity(
    val accountId: Long,
    val bookId: Long,
    val fileId: Long,
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    val state: DownloadState,
    val progress: Float,
)

data class BookRow(
    @Embedded val book: BookEntity,
    val downloaded: Boolean,
)

/** A completed download with its book; the book's columns carry the `b_` prefix. */
data class DownloadWithBook(
    @Embedded val download: DownloadEntity,
    @Embedded(prefix = "b_") val book: BookEntity,
)
