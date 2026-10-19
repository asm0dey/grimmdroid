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

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.asm0dey.grimmdroid.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FolderExportTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private lateinit var settings: SettingsStore
    private lateinit var folderExport: FolderExport
    private val book = File.createTempFile("Moby-Dick", ".epub")

    @Before
    fun setUp() {
        val file = File.createTempFile("settings", ".preferences_pb").also { it.delete() }
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { file })
        folderExport = FolderExport(context, settings)
    }

    @After
    fun tearDown() {
        scope.cancel()
        book.delete()
    }

    @Test
    fun saveWithoutFolderNeedsFolder() = runBlocking { assertEquals(FolderResult.NeedsFolder, folderExport.save(1, book)) }

    @Test
    fun foldersArePerAccount() =
        runBlocking {
            val uri = Uri.parse("content://tree/1")
            settings.setFolderUri(1, uri)
            assertEquals(uri, settings.folderUri(1).first())
            assertEquals(null, settings.folderUri(2).first())
            assertEquals(FolderResult.NeedsFolder, folderExport.save(2, book))
        }

    @Test
    fun revokedPermissionIsFolderLost() =
        runBlocking {
            settings.setFolderUri(1, Uri.parse("content://tree/1"))
            assertEquals(FolderResult.FolderLost, folderExport.save(1, book))
        }

    @Test
    fun settingsDefaults() =
        runBlocking {
            assertEquals(AfterDownloadAction.NOTHING, settings.afterDownload.first())
            assertTrue(settings.dynamicColor.first())
        }
}
