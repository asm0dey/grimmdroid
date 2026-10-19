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

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import io.github.asm0dey.grimmdroid.account.AccountEntity
import io.github.asm0dey.grimmdroid.account.AccountRepository
import io.github.asm0dey.grimmdroid.account.TokenCipher
import io.github.asm0dey.grimmdroid.api.BookProgressDto
import io.github.asm0dey.grimmdroid.api.EpubProgressDto
import io.github.asm0dey.grimmdroid.api.GrimmoryApi
import io.github.asm0dey.grimmdroid.api.KoreaderProgressDto
import io.github.asm0dey.grimmdroid.api.TokenSource
import io.github.asm0dey.grimmdroid.api.Tokens
import io.github.asm0dey.grimmdroid.api.authedClient
import io.github.asm0dey.grimmdroid.catalog.CatalogDb
import io.github.asm0dey.grimmdroid.catalog.DownloadEntity
import io.github.asm0dey.grimmdroid.catalog.DownloadState
import io.github.asm0dey.grimmdroid.download.DownloadRepository
import io.github.asm0dey.grimmdroid.reader.cfi.EpubArchive
import io.github.asm0dey.grimmdroid.reader.cfi.PositionTarget
import io.github.asm0dey.grimmdroid.reader.cfi.tinyEpub
import io.github.asm0dey.grimmdroid.settings.SettingsStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** The worker reaches its dependencies through the Application, so the test supplies its own. */
class PositionTestApp :
    Application(),
    PositionUploadHost {
    override lateinit var positionSync: PositionSync
    override lateinit var readerDao: ReaderDao
    override lateinit var accounts: AccountRepository
    override lateinit var downloadRepository: DownloadRepository
    override lateinit var bookmarks: Bookmarks
}

private object FixedTokens : TokenSource {
    override suspend fun current() = Tokens("a", "r")

    override suspend fun save(t: Tokens) = Unit

    override suspend fun expired() = Unit
}

private object PlainCipher : TokenCipher {
    override fun encrypt(plain: ByteArray) = plain

    override fun decrypt(cipher: ByteArray) = cipher
}

private const val BOOK = 7L
private const val PROGRESS_PATH = "/api/v1/app/books/$BOOK/progress"
private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
private const val LOCATOR = """{"href":"c2.xhtml","type":"application/xhtml+xml","locations":{"progression":0.0,"cssSelector":"#a"}}"""

/** Grimmory's progress endpoint for book 7; any other request fails the test. */
internal class FakeServer {
    var progressJson = "{}"
    var failWith: Exception? = null
    var onPut: suspend () -> Unit = {}
    val gets = mutableListOf<String>()
    val puts = mutableListOf<String>()
    val bookmarkPosts = mutableListOf<String>()
    val bookmarkDeletes = mutableListOf<String>()
    val requests get() = gets.size + puts.size

    val api =
        GrimmoryApi(
            authedClient(
                "https://grim.example",
                FixedTokens,
                MockEngine { req ->
                    failWith?.let { throw it }
                    when {
                        req.method == HttpMethod.Get && req.url.encodedPath == PROGRESS_PATH -> {
                            gets += req.url.encodedPath
                            respond(progressJson, HttpStatusCode.OK, jsonHeaders)
                        }

                        req.method == HttpMethod.Put && req.url.encodedPath == PROGRESS_PATH -> {
                            puts += String(req.body.toByteArray())
                            onPut()
                            respond("", HttpStatusCode.NoContent)
                        }

                        req.method == HttpMethod.Get && req.url.encodedPath == "/api/v1/bookmarks/book/$BOOK" -> {
                            respond("[]", HttpStatusCode.OK, jsonHeaders)
                        }

                        req.method == HttpMethod.Post && req.url.encodedPath == "/api/v1/bookmarks" -> {
                            bookmarkPosts += String(req.body.toByteArray())
                            respond("""{"id":${bookmarkPosts.size},"bookId":$BOOK}""", HttpStatusCode.OK, jsonHeaders)
                        }

                        req.method == HttpMethod.Delete && req.url.encodedPath.startsWith("/api/v1/bookmarks/") -> {
                            bookmarkDeletes += req.url.encodedPath
                            respond("", HttpStatusCode.NoContent)
                        }

                        else -> {
                            error("unexpected ${req.method} ${req.url}")
                        }
                    }
                },
            ),
        )
}

@RunWith(AndroidJUnit4::class)
@Config(application = PositionTestApp::class)
class PositionSyncTest {
    private lateinit var app: PositionTestApp
    private lateinit var db: CatalogDb
    private lateinit var dao: ReaderDao
    private val server = FakeServer()
    private val enqueued = mutableListOf<Long>()
    private lateinit var sync: PositionSync
    private val epubFile = tinyEpub()
    private lateinit var archive: EpubArchive

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, CatalogDb::class.java).allowMainThreadQueries().build()
        dao = db.readerDao()
        sync = PositionSync(dao, { db.accountDao().get(it) }, { server.api }) { enqueued += it }
        archive = EpubArchive(epubFile)
        val dir = Files.createTempDirectory("ds").toFile()
        val ds =
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            ) { File(dir, "s.preferences_pb") }
        app.positionSync = sync
        app.bookmarks = Bookmarks(dao, { db.accountDao().get(it) }, { server.api }) { enqueued += it }
        app.readerDao = dao
        app.accounts = AccountRepository(db.accountDao(), PlainCipher, SettingsStore(ds))
        app.downloadRepository = DownloadRepository(db.catalogDao(), { error("no downloads in this test") }, app.filesDir)
        check(account() == 1L) // the rows below default to account 1
    }

    @After
    fun tearDown() {
        archive.close()
        db.close()
    }

    private fun row(
        account: Long = 1,
        progression: Double = 0.4,
        pending: PendingUpload = PendingUpload.NORMAL,
    ) = ReadingPosition(account, BOOK, LOCATOR, progression, pending, 5)

    private fun serverAt(percentage: Double) {
        server.progressJson = """{"epubProgress":{"cfi":"epubcfi(/6/4!/4/2)","href":"c2.xhtml","percentage":$percentage}}"""
    }

    // ---- decisions ----

    @Test
    fun decideOnOpenRules() {
        val at = { p: Double -> ServerPosition.Approximate(p) }
        val exact = ServerPosition.Exact(PositionTarget("c2.xhtml", "#a", 0.0), "epubcfi(/6/4!/4/2)", 0.6)
        assertEquals(OpenDecision.OpenAt(exact), decideOnOpen(null, exact))
        assertEquals(OpenDecision.OfferJump(at(0.6)), decideOnOpen(0.4, at(0.6)))
        assertEquals(OpenDecision.Stay, decideOnOpen(0.6, at(0.4)))
        assertEquals(OpenDecision.Stay, decideOnOpen(0.400, at(0.404)))
        assertEquals(OpenDecision.Stay, decideOnOpen(0.4, null))
        assertEquals(OpenDecision.Stay, decideOnOpen(null, null))
    }

    @Test
    fun decideOnSyncNowRules() {
        val at = { p: Double -> ServerPosition.Approximate(p) }
        assertEquals(OpenDecision.OfferJump(at(0.4)), decideOnSyncNow(0.6, at(0.4)))
        assertEquals(OpenDecision.OfferJump(at(0.6)), decideOnSyncNow(0.4, at(0.6)))
        assertEquals(OpenDecision.Stay, decideOnSyncNow(0.400, at(0.404)))
        assertEquals(OpenDecision.Stay, decideOnSyncNow(0.4, null))
    }

    // ---- serverPosition ----

    private fun dto(
        cfi: String? = null,
        percentage: Float? = null,
        koreader: Float? = null,
        href: String? = "c1.xhtml", // deliberately wrong: the target must come from the CFI
    ) = BookProgressDto(
        epubProgress = if (cfi != null || percentage != null) EpubProgressDto(cfi, href, percentage) else null,
        koreaderProgress = koreader?.let { KoreaderProgressDto(it) },
    )

    @Test
    fun resolvableCfiIsExactAndIgnoresHref() {
        assertEquals(
            ServerPosition.Exact(PositionTarget("c2.xhtml", "#a", 0.0), "epubcfi(/6/4!/4/2)", 0.615),
            serverPosition(dto("epubcfi(/6/4!/4/2)", 61.5f), archive),
        )
    }

    @Test
    fun garbageCfiFallsBackToPercentage() {
        assertEquals(ServerPosition.Approximate(0.615), serverPosition(dto("garbage", 61.5f), archive))
        assertEquals(ServerPosition.Approximate(0.615), serverPosition(dto("epubcfi(/6/40!/4/2)", 61.5f), archive))
    }

    @Test
    fun furtherKoreaderWinsWithoutCfi() {
        assertEquals(ServerPosition.Approximate(0.6), serverPosition(dto(percentage = 40f, koreader = 0.6f), archive))
        assertEquals(ServerPosition.Approximate(0.6), serverPosition(dto(percentage = 60f, koreader = 0.3f), archive))
        assertEquals(ServerPosition.Approximate(0.3), serverPosition(dto(koreader = 0.3f), archive))
    }

    @Test
    fun furtherKoreaderBeatsResolvedCfi() {
        assertEquals(ServerPosition.Approximate(0.6), serverPosition(dto("epubcfi(/6/4!/4/2)", 40f, 0.6f), archive))
        val exact = serverPosition(dto("epubcfi(/6/4!/4/2)", 60f, 0.6f), archive)
        assertTrue(exact is ServerPosition.Exact)
        assertEquals(0.6, exact!!.totalProgression, 1e-9)
    }

    @Test
    fun resolvedCfiWithoutPercentageEstimatesFromSpine() {
        // c2 is spine item 2 of 2, at its start: (1 + 0.0) / 2
        assertEquals(
            ServerPosition.Exact(PositionTarget("c2.xhtml", "#a", 0.0), "epubcfi(/6/4!/4/2)", 0.5),
            serverPosition(dto("epubcfi(/6/4!/4/2)"), archive),
        )
    }

    @Test
    fun emptyDtoIsNull() {
        assertNull(serverPosition(BookProgressDto(), archive))
        assertNull(serverPosition(dto("garbage"), archive))
    }

    // ---- PositionSync ----

    @Test
    fun saveLocalMarksNormalButKeepsForce() =
        runBlocking {
            sync.saveLocal(1, BOOK, LOCATOR, 0.3)
            assertEquals(PendingUpload.NORMAL, dao.position(1, BOOK)!!.pending)
            dao.upsertPosition(row(pending = PendingUpload.FORCE))
            sync.saveLocal(1, BOOK, LOCATOR, 0.35)
            val p = dao.position(1, BOOK)!!
            assertEquals(PendingUpload.FORCE, p.pending)
            assertEquals(0.35, p.totalProgression, 0.0)
        }

    @Test
    fun serverAheadHoldsBackNormalUpload() =
        runBlocking {
            dao.upsertPosition(row(progression = 0.4))
            serverAt(60.0)
            assertEquals(UploadResult.HELD_BACK, sync.upload(1, BOOK, 42) { "cfi" })
            assertEquals(UploadResult.HELD_BACK, sync.upload(1, BOOK, 42) { "cfi" })
            assertEquals(0, server.puts.size)
            assertEquals(2, server.gets.size)
            assertEquals(PendingUpload.NORMAL, dao.position(1, BOOK)!!.pending)
            assertTrue(enqueued.isEmpty())
        }

    @Test
    fun forceUploadsOverFurtherServer() =
        runBlocking {
            dao.upsertPosition(row(progression = 0.4, pending = PendingUpload.FORCE))
            serverAt(60.0)
            assertEquals(UploadResult.SENT, sync.upload(1, BOOK, 42) { "epubcfi(/6/4!/4/2)" })
            assertEquals(
                listOf(
                    """{"fileProgress":{"bookFileId":42,"positionData":"epubcfi(/6/4!/4/2)","positionHref":"c2.xhtml","progressPercent":40.0}}""",
                ),
                server.puts,
            )
            assertEquals(0, server.gets.size)
            assertEquals(PendingUpload.NONE, dao.position(1, BOOK)!!.pending)
        }

    @Test
    fun serverBehindUploads() =
        runBlocking {
            dao.upsertPosition(row(progression = 0.6))
            serverAt(40.0)
            assertEquals(UploadResult.SENT, sync.upload(1, BOOK, 42) { "c" })
            assertEquals(1, server.puts.size)
            assertEquals(PendingUpload.NONE, dao.position(1, BOOK)!!.pending)
        }

    @Test
    fun koreaderAheadAlsoHoldsBack() =
        runBlocking {
            dao.upsertPosition(row(progression = 0.4))
            server.progressJson = """{"koreaderProgress":{"percentage":0.6}}"""
            assertEquals(UploadResult.HELD_BACK, sync.upload(1, BOOK, 42) { "c" })
            assertEquals(0, server.puts.size)
        }

    @Test
    fun ioErrorFailsKeepsRowAndEnqueuesOnce() =
        runBlocking {
            dao.upsertPosition(row())
            server.failWith = IOException("offline")
            assertEquals(UploadResult.FAILED, sync.upload(1, BOOK, 42) { "c" })
            assertEquals(listOf(1L), enqueued)
            assertEquals(row(), dao.position(1, BOOK))
            assertEquals(0, server.puts.size)
        }

    @Test
    fun repeatedFailuresEnqueueOnceUntilTheWorkerStarts() =
        runBlocking {
            dao.upsertPosition(row())
            server.failWith = IOException("offline")
            assertEquals(UploadResult.FAILED, sync.upload(1, BOOK, 42) { "c" })
            assertEquals(UploadResult.FAILED, sync.upload(1, BOOK, 42) { "c" })
            assertEquals(listOf(1L), enqueued)
            sync.followUpStarted(1)
            assertEquals(UploadResult.FAILED, sync.upload(1, BOOK, 42) { "c" })
            assertEquals(listOf(1L, 1L), enqueued)
        }

    @Test
    fun uploadWithoutEnqueueOnFailureEnqueuesNothing() =
        runBlocking {
            dao.upsertPosition(row())
            server.failWith = IOException("offline")
            assertEquals(UploadResult.FAILED, sync.upload(1, BOOK, 42, enqueueOnFailure = false) { "c" })
            assertTrue(enqueued.isEmpty())
        }

    @Test
    fun savedDuringUploadStaysPending() =
        runBlocking {
            dao.upsertPosition(row(progression = 0.6))
            serverAt(40.0)
            val newer = row(progression = 0.7).copy(locatorJson = LOCATOR.replace("0.0", "0.5"), updatedAt = 9)
            server.onPut = { dao.upsertPosition(newer) }
            assertEquals(UploadResult.SENT, sync.upload(1, BOOK, 42) { "c" })
            assertEquals(newer, dao.position(1, BOOK))
        }

    @Test
    fun makeCurrentUploadsOverFurtherServer() =
        runBlocking {
            dao.upsertPosition(row(progression = 0.4))
            serverAt(60.0)
            assertEquals(UploadResult.SENT, sync.makeCurrent(1, BOOK, 42) { "c" })
            assertEquals(1, server.puts.size)
            assertEquals(0, server.gets.size)
            assertEquals(PendingUpload.NONE, dao.position(1, BOOK)!!.pending)
            assertTrue(enqueued.isEmpty())
        }

    @Test
    fun makeCurrentFailureStaysForceAndEnqueues() =
        runBlocking {
            dao.upsertPosition(row())
            server.failWith = IOException("offline")
            assertEquals(UploadResult.FAILED, sync.makeCurrent(1, BOOK, 42) { "c" })
            assertEquals(PendingUpload.FORCE, dao.position(1, BOOK)!!.pending)
            assertEquals(listOf(1L), enqueued)
        }

    @Test
    fun makeCurrentWithoutLocalPositionFails() =
        runBlocking {
            assertEquals(UploadResult.FAILED, sync.makeCurrent(1, BOOK, 42) { "c" })
            assertNull(dao.position(1, BOOK))
            assertTrue(enqueued.isEmpty())
            assertEquals(0, server.requests)
        }

    @Test
    fun uploadForSignedOutOrRemovedAccountSendsNothing() =
        runBlocking {
            val signedOut = account(signedOut = true)
            dao.upsertPosition(row(account = signedOut))
            dao.upsertPosition(row(account = 99))
            assertEquals(UploadResult.FAILED, sync.upload(signedOut, BOOK, 42) { "c" })
            assertEquals(UploadResult.FAILED, sync.upload(99, BOOK, 42) { "c" })
            assertEquals(0, server.requests)
            assertTrue(enqueued.isEmpty())
            assertEquals(row(account = signedOut), dao.position(signedOut, BOOK))
        }

    // ---- worker ----

    private fun runWorker(accountId: Long): ListenableWorker.Result =
        runBlocking {
            TestListenableWorkerBuilder<PositionUploadWorker>(app)
                .setInputData(workDataOf(PositionUploadWorker.KEY_ACCOUNT_ID to accountId))
                .build()
                .doWork()
        }

    private fun account(signedOut: Boolean = false) =
        runBlocking {
            db.accountDao().upsert(
                AccountEntity(
                    baseUrl = "https://grim.example",
                    username = "u${System.nanoTime()}",
                    serverVersion = "",
                    canDownload = true,
                    isAdmin = false,
                    encryptedTokens = null,
                    signedOut = signedOut,
                ),
            )
        }

    private fun downloaded(
        accountId: Long,
        path: String = epubFile.path,
    ) = runBlocking { db.catalogDao().upsertDownload(DownloadEntity(accountId, BOOK, 42, "b.epub", path, 1, DownloadState.DONE, 1f)) }

    @Test
    fun workerForRemovedAccountDeletesRowsWithoutRequests() {
        val other = account()
        runBlocking {
            dao.upsertPosition(row(account = 99))
            dao.upsertPosition(row(account = other))
        }
        assertEquals(ListenableWorker.Result.success(), runWorker(99))
        runBlocking {
            assertNull(dao.position(99, BOOK))
            assertEquals(row(account = other), dao.position(other, BOOK))
        }
        assertEquals(0, server.requests)
    }

    @Test
    fun workerForSignedOutAccountKeepsRowsWithoutRequests() {
        val id = account(signedOut = true)
        downloaded(id)
        runBlocking { dao.upsertPosition(row(account = id)) }
        assertEquals(ListenableWorker.Result.success(), runWorker(id))
        runBlocking { assertEquals(row(account = id), dao.position(id, BOOK)) }
        assertEquals(0, server.requests)
    }

    @Test
    fun workerUploadsWithDownloadedFileIdAndLocatorCfi() {
        val id = account()
        downloaded(id)
        runBlocking { dao.upsertPosition(row(account = id, progression = 0.6)) }
        serverAt(40.0)
        assertEquals(ListenableWorker.Result.success(), runWorker(id))
        assertEquals(
            listOf(
                """{"fileProgress":{"bookFileId":42,"positionData":"epubcfi(/6/4!/4/2)","positionHref":"c2.xhtml","progressPercent":60.0}}""",
            ),
            server.puts,
        )
        runBlocking { assertEquals(PendingUpload.NONE, dao.position(id, BOOK)!!.pending) }
    }

    @Test
    fun workerFlushesPendingBookmarksAndRetriesTheirFailures() {
        val id = account()
        runBlocking {
            dao.insertBookmark(BookmarkRow(0, id, BOOK, 5, "c", LOCATOR, "t", 0.4, BookmarkState.PENDING_DELETE))
        }
        server.failWith = IOException("offline")
        assertEquals(ListenableWorker.Result.retry(), runWorker(id))
        assertTrue(enqueued.isEmpty())
    }

    @Test
    fun workerSkipsBookWhoseFileIsGone() {
        val id = account()
        downloaded(id, path = File(app.filesDir, "gone.epub").path)
        runBlocking { dao.upsertPosition(row(account = id)) }
        assertEquals(ListenableWorker.Result.success(), runWorker(id))
        runBlocking { assertEquals(row(account = id), dao.position(id, BOOK)) }
        assertEquals(0, server.requests)
    }

    @Test
    fun workerTreatsHeldBackAsDoneAndRetriesFailures() {
        val id = account()
        downloaded(id)
        runBlocking { dao.upsertPosition(row(account = id, progression = 0.4)) }
        serverAt(60.0)
        assertEquals(ListenableWorker.Result.success(), runWorker(id))
        server.failWith = IOException("offline")
        assertEquals(ListenableWorker.Result.retry(), runWorker(id))
        runBlocking { assertEquals(PendingUpload.NORMAL, dao.position(id, BOOK)!!.pending) }
    }

    @Test
    fun enqueueAppendsToTheAccountsUniqueWorkAndNeedsNetwork() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        enqueuePositionUpload(app, 7)
        enqueuePositionUpload(app, 7)
        val infos =
            WorkManager
                .getInstance(app)
                .getWorkInfosForUniqueWork("pos-upload-7")
                .get()
        // APPEND_OR_REPLACE: the second request waits behind the first instead of being dropped (KEEP would leave one)
        assertEquals(listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED), infos.map { it.state }.sorted())
        infos.forEach { assertEquals(NetworkType.CONNECTED, it.constraints.requiredNetworkType) }
    }
}
