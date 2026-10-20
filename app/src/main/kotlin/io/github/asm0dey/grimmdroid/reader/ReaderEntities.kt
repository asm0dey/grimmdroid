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
package io.github.asm0dey.grimmdroid.reader

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** What still has to go to the server for a position. FORCE comes from "Make this position current". */
enum class PendingUpload { NONE, NORMAL, FORCE }

enum class BookmarkState { SYNCED, PENDING_CREATE, PENDING_DELETE }

@Entity(primaryKeys = ["accountId", "bookId"])
data class ReadingPosition(
    val accountId: Long,
    val bookId: Long,
    val locatorJson: String,
    val totalProgression: Double,
    val pending: PendingUpload,
    val updatedAt: Long,
)

@Entity(indices = [Index("accountId", "bookId")])
data class BookmarkRow(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val accountId: Long,
    val bookId: Long,
    val serverId: Long?,
    val cfi: String?,
    val locatorJson: String,
    val title: String,
    val totalProgression: Double,
    val state: BookmarkState,
)
