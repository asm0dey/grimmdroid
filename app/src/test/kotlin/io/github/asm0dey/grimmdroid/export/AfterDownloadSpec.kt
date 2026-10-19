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

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.asm0dey.grimmdroid.settings.SettingsStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.io.File
import java.io.IOException
import java.nio.file.Files

class AfterDownloadSpec :
    FunSpec({
        val file = File("book.epub")
        val scopes = mutableListOf<CoroutineScope>()

        class Fixture(
            action: AfterDownloadAction,
            val result: FolderResult = FolderResult.Saved("Books"),
            val failure: Exception? = null,
        ) {
            var saves = 0
            val scope = CoroutineScope(Dispatchers.IO)
            val settings =
                SettingsStore(
                    PreferenceDataStoreFactory.create(scope = scope) {
                        Files.createTempDirectory("ds").resolve("s.preferences_pb").toFile()
                    },
                )
            val afterDownload =
                AfterDownload(
                    settings,
                    object : FolderSaver {
                        override suspend fun save(
                            accountId: Long,
                            file: File,
                        ): FolderResult {
                            saves++
                            failure?.let { throw it }
                            return result
                        }
                    },
                )

            init {
                scopes += scope
            }

            val ready = suspend { settings.setAfterDownload(action) }
        }

        test("NOTHING touches nothing") {
            val f = Fixture(AfterDownloadAction.NOTHING).also { it.ready() }
            f.afterDownload.run(1, file) shouldBe AfterDownloadOutcome.Nothing
            f.saves shouldBe 0
        }

        test("OPEN from the background leaves it to the notification") {
            val f = Fixture(AfterDownloadAction.OPEN).also { it.ready() }
            f.afterDownload.run(1, file) shouldBe AfterDownloadOutcome.OpenViaNotification
            f.saves shouldBe 0
        }

        test("SAVE_TO_FOLDER saves once and reports the folder") {
            val f = Fixture(AfterDownloadAction.SAVE_TO_FOLDER).also { it.ready() }
            f.afterDownload.run(1, file) shouldBe AfterDownloadOutcome.Saved("Books")
            f.saves shouldBe 1
        }

        test("SAVE_TO_FOLDER without a folder says so") {
            val f = Fixture(AfterDownloadAction.SAVE_TO_FOLDER, FolderResult.NeedsFolder).also { it.ready() }
            f.afterDownload.run(1, file) shouldBe AfterDownloadOutcome.NeedsFolder
        }

        test("a revoked grant (SecurityException) is FolderLost, not a worker failure") {
            val f = Fixture(AfterDownloadAction.SAVE_TO_FOLDER, failure = SecurityException()).also { it.ready() }
            f.afterDownload.run(1, file) shouldBe AfterDownloadOutcome.FolderLost
        }

        test("any other save failure is SaveFailed, not a worker failure") {
            for (e in listOf(IllegalStateException(), IOException(), UnsupportedOperationException())) {
                val f = Fixture(AfterDownloadAction.SAVE_TO_FOLDER, failure = e).also { it.ready() }
                f.afterDownload.run(1, file) shouldBe AfterDownloadOutcome.SaveFailed
            }
        }

        afterSpec { scopes.forEach { it.cancel() } }
    })
