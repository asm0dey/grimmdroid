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
package io.github.asm0dey.grimmdroid.ui.settings

import android.app.Application
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.asm0dey.grimmdroid.account.AccountRepository
import io.github.asm0dey.grimmdroid.account.TokenCipher
import io.github.asm0dey.grimmdroid.api.AppUserInfo
import io.github.asm0dey.grimmdroid.api.Tokens
import io.github.asm0dey.grimmdroid.catalog.BookEntity
import io.github.asm0dey.grimmdroid.catalog.CatalogDb
import io.github.asm0dey.grimmdroid.catalog.DownloadEntity
import io.github.asm0dey.grimmdroid.catalog.DownloadState
import io.github.asm0dey.grimmdroid.download.DownloadRepository
import io.github.asm0dey.grimmdroid.export.AfterDownloadAction
import io.github.asm0dey.grimmdroid.export.FolderExport
import io.github.asm0dey.grimmdroid.settings.SettingsStore
import io.github.asm0dey.grimmdroid.sync.LibrarySync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

private object XorCipher : TokenCipher {
    override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

    override fun decrypt(cipher: ByteArray) = encrypt(cipher)
}

private const val BOOK = 12L

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsViewModelSpec {
    private lateinit var db: CatalogDb
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsStore
    private lateinit var accounts: AccountRepository
    private lateinit var vm: SettingsViewModel
    private lateinit var folderExport: FolderExport
    private lateinit var sync: LibrarySync
    private val store = ViewModelStore()
    private val dropped = mutableListOf<Long>()
    private var dropFails = false

    @Volatile private var signOutFailures = 0

    @Volatile private var coverClears = 0

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        // viewModelScope would otherwise post to the main looper, which runBlocking holds.
        Dispatchers.setMain(Dispatchers.Unconfined)
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        db = Room.inMemoryDatabaseBuilder(ctx, CatalogDb::class.java).allowMainThreadQueries().build()
        dir = Files.createTempDirectory("settings").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") })
        accounts = AccountRepository(db.accountDao(), XorCipher, settings)
        folderExport = FolderExport(ctx, settings)
        sync = LibrarySync(db.catalogDao(), accounts, { error("no network in this test") }, System::currentTimeMillis)
        val factory =
            viewModelFactory {
                initializer {
                    SettingsViewModel(
                        ctx,
                        accounts,
                        settings,
                        db.catalogDao(),
                        DownloadRepository(db.catalogDao(), { error("no network in this test") }, dir),
                        sync,
                        folderExport,
                        dropClient = {
                            if (dropFails) throw IllegalStateException("boom")
                            dropped += it
                        },
                        coverCacheSize = { 0L },
                        clearCoverCache = { coverClears++ },
                        appScope = scope,
                        onSignOutFailed = { signOutFailures++ },
                    )
                }
            }
        vm = ViewModelProvider.create(store, factory)[SettingsViewModel::class]
    }

    @After
    fun tearDown() {
        store.clear()
        db.close()
        scope.cancel()
        dir.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun book(
        accountId: Long,
        id: Long,
    ) = BookEntity(accountId, id, "T", "A", null, null, null, null, null, null, null, "EPUB", null, null, null, false, 1)

    /** An account with one downloaded book on disk; returns the account id and the file. */
    private suspend fun accountWithBook(): Pair<Long, File> {
        val id = accounts.addOrUpdate("https://a", "u", "1.0", AppUserInfo(canDownload = true), Tokens("t", "r"))
        val dao = db.catalogDao()
        dao.upsertBooks(listOf(book(id, BOOK)))
        val file = File(dir, "books/$id/$BOOK/t.epub").apply { parentFile!!.mkdirs() }
        file.writeText("book")
        dao.upsertDownload(DownloadEntity(id, BOOK, 0, "t.epub", file.path, 4, DownloadState.DONE, 1f))
        return id to file
    }

    @Test
    fun choosingSaveToFolderPersistsIt() =
        runBlocking {
            val subscribed = CompletableDeferred<Unit>()
            val saved =
                async {
                    settings.afterDownload.onEach { subscribed.complete(Unit) }.first { it == AfterDownloadAction.SAVE_TO_FOLDER }
                }
            // DataStore 1.2.1 can strand a collector whose first read overlaps a write (fixed in 1.3.0),
            // so let ours read its start state before the write begins.
            subscribed.await()
            vm.setAfterDownload(AfterDownloadAction.SAVE_TO_FOLDER)
            withTimeout(5_000) { saved.await() }
            Unit
        }

    @Test
    fun signOutWithDeleteRemovesAccountRowsFilesAndTokens() =
        runBlocking {
            val (id, file) = accountWithBook()
            vm.signOutNow(id, removeAccount = true)
            assertNull(accounts.account(id))
            assertNull(accounts.tokenSource(id).current())
            assertFalse(file.exists())
            assertFalse(File(dir, "books/$id").exists())
            assertNull(db.catalogDao().download(id, BOOK).first())
            assertNull(db.catalogDao().book(id, BOOK).first())
            assertEquals(listOf(id), dropped)
            assertEquals(1, coverClears)
            assertNull(settings.currentAccountId.first())
        }

    @Test
    fun signOutWithKeepLeavesFilesAndCatalogue() =
        runBlocking {
            val (id, file) = accountWithBook()
            vm.signOutNow(id, removeAccount = false)
            val account = checkNotNull(accounts.account(id))
            assertTrue(account.signedOut)
            assertNull(account.encryptedTokens)
            assertNull(accounts.tokenSource(id).current())
            assertTrue(file.exists())
            assertNotNull(db.catalogDao().download(id, BOOK).first())
            assertNotNull(db.catalogDao().book(id, BOOK).first())
            assertEquals(0, coverClears)
        }

    @Test
    fun removalFinishesEvenWhenSettingsIsClosedMidWay() =
        runBlocking {
            val (id, _) = accountWithBook()
            val folder = Uri.parse("content://tree/books")
            folderExport.setFolder(id, folder)
            val resolver = ApplicationProvider.getApplicationContext<Application>().contentResolver
            assertTrue(resolver.persistedUriPermissions.any { it.uri == folder })
            val job = vm.signOut(id, removeAccount = true)
            store.clear() // navigation popped Settings: viewModelScope is cancelled
            job.join()
            assertFalse(resolver.persistedUriPermissions.any { it.uri == folder })
            assertNull(settings.folderUri(id).first())
            assertEquals(1, coverClears)
            assertNull(accounts.account(id))
        }

    @Test
    fun removalWaitsForARunningSweepSoItsRowsDontSurvive() =
        runBlocking {
            val (id, _) = accountWithBook()
            val started = CompletableDeferred<Unit>()
            val sweep =
                launch(Dispatchers.IO) {
                    sync.exclusive(id) {
                        started.complete(Unit)
                        delay(300)
                        db.catalogDao().upsertBooks(listOf(book(id, 99)))
                    }
                }
            started.await()
            vm.signOutNow(id, removeAccount = true)
            sweep.join()
            assertNull(db.catalogDao().book(id, 99).first())
            assertNull(db.catalogDao().book(id, BOOK).first())
        }

    @Test
    fun failedSignOutIsReportedInsteadOfCrashing() =
        runBlocking {
            val (id, _) = accountWithBook()
            dropFails = true
            val job = vm.signOut(id, removeAccount = false)
            job.join()
            assertFalse(job.isCancelled)
            assertEquals(1, signOutFailures)
        }

    @Test
    fun removalDropsTheAccountsSyncState() =
        runBlocking {
            val (id, _) = accountWithBook()
            accounts.signOut(id)
            sync.sync(id)
            assertTrue(id in sync.state.value)
            vm.signOutNow(id, removeAccount = true)
            assertFalse(id in sync.state.value)
        }
}
