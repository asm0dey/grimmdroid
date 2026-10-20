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

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.asm0dey.grimmdroid.catalog.CatalogDb
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReaderMigrationTest {
    private val dbFile =
        ApplicationProvider.getApplicationContext<Context>().getDatabasePath("migration.db").also {
            it.parentFile!!.mkdirs()
        }

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            dbFile,
            AndroidSQLiteDriver(),
            CatalogDb::class,
            {
                Room
                    .databaseBuilder<CatalogDb>(ApplicationProvider.getApplicationContext(), dbFile.path)
                    .setDriver(AndroidSQLiteDriver())
                    .build()
            },
        )

    private fun SQLiteConnection.count(table: String) =
        prepare("SELECT COUNT(*) FROM $table").use {
            it.step()
            it.getLong(0)
        }

    @Test
    fun v1ToV2KeepsBooksAndAddsReaderTables() {
        helper.createDatabase(1).use { c ->
            c.execSQL(
                "INSERT INTO BookEntity (accountId, id, title, authors, isAudiobook, syncGeneration) VALUES (1, 7, 'Kept', 'A', 0, 1)",
            )
        }
        helper.runMigrationsAndValidate(2, emptyList()).use { c ->
            c.prepare("SELECT title FROM BookEntity WHERE id = 7").use {
                assertEquals(true, it.step())
                assertEquals("Kept", it.getText(0))
            }
            assertEquals(0L, c.count("ReadingPosition"))
            assertEquals(0L, c.count("BookmarkRow"))
        }
    }
}
