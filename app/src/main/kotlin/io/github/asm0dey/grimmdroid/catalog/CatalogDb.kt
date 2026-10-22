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

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import io.github.asm0dey.grimmdroid.account.AccountDao
import io.github.asm0dey.grimmdroid.account.AccountEntity
import io.github.asm0dey.grimmdroid.reader.BookmarkRow
import io.github.asm0dey.grimmdroid.reader.ReaderDao
import io.github.asm0dey.grimmdroid.reader.ReadingPosition

@Database(
    entities =
        [
            AccountEntity::class,
            BookEntity::class,
            BookFts::class,
            LibraryEntity::class,
            ShelfEntity::class,
            ShelfBookCrossRef::class,
            DownloadEntity::class,
            ReadingPosition::class,
            BookmarkRow::class,
            BookAuthor::class,
        ],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
    exportSchema = true,
)
abstract class CatalogDb : RoomDatabase() {
    abstract fun accountDao(): AccountDao

    abstract fun catalogDao(): CatalogDao

    abstract fun readerDao(): ReaderDao
}
