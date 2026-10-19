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
package io.github.asm0dey.grimmdroid.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.asm0dey.grimmdroid.account.AccountRepository
import io.github.asm0dey.grimmdroid.account.TokenCipher
import io.github.asm0dey.grimmdroid.api.AppUserInfo
import io.github.asm0dey.grimmdroid.api.BookSummaryDto
import io.github.asm0dey.grimmdroid.api.GrimmoryApi
import io.github.asm0dey.grimmdroid.api.Tokens
import io.github.asm0dey.grimmdroid.api.authedClient
import io.github.asm0dey.grimmdroid.catalog.BookEntity
import io.github.asm0dey.grimmdroid.catalog.BookQuery
import io.github.asm0dey.grimmdroid.catalog.CatalogDb
import io.github.asm0dey.grimmdroid.catalog.toSql
import io.github.asm0dey.grimmdroid.settings.SettingsStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

private class XorCipher : TokenCipher {
    override fun encrypt(plain: ByteArray) = plain.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()

    override fun decrypt(cipher: ByteArray) = encrypt(cipher)
}

private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

/** Minimal Grimmory /api/v1/app server; book JSON is kept tiny so 10k-book sweeps stay fast. */
private class FakeServer {
    var titles: Map<Long, String> = emptyMap()
    var canDownload = true
    var failOnPage: Int? = null
    var failShelfIds = false
    var requests = 0
    var unauthorized = false
    var hasNextStuck = false
    var shelves = mapOf(10L to listOf(1L, 2L))
    var magicShelves = mapOf(10L to listOf(3L))
    var maxSize = 0
    var meMissing = false
    var shelfIdsMissing = false
    var version: String? = "v9.9.9"

    val engine =
        MockEngine { req ->
            requests++
            val p = req.url.parameters
            when {
                req.url.encodedPath == "/api/v1/auth/refresh" -> {
                    respondError(HttpStatusCode.Unauthorized)
                }

                unauthorized -> {
                    respondError(HttpStatusCode.Unauthorized)
                }

                else -> {
                    val body =
                        when (req.url.encodedPath) {
                            "/api/v1/version" -> {
                                val v = version ?: return@MockEngine respondError(HttpStatusCode.InternalServerError)
                                """{"current":"$v"}"""
                            }

                            "/api/v1/app/users/me" -> {
                                if (meMissing) return@MockEngine respondError(HttpStatusCode.NotFound)
                                """{"isAdmin":false,"canDownload":$canDownload}"""
                            }

                            "/api/v1/app/libraries" -> {
                                """[{"id":1,"name":"L","bookCount":${titles.size}}]"""
                            }

                            "/api/v1/app/shelves" -> {
                                shelves.keys.joinToString(",", "[", "]") { """{"id":$it,"name":"S$it"}""" }
                            }

                            "/api/v1/app/shelves/magic" -> {
                                magicShelves.keys.joinToString(",", "[", "]") { """{"id":$it,"name":"M$it"}""" }
                            }

                            "/api/v1/app/books/ids" -> {
                                if (shelfIdsMissing) return@MockEngine respondError(HttpStatusCode.NotFound)
                                if (failShelfIds) return@MockEngine respondError(HttpStatusCode.InternalServerError)
                                val ids = p["shelfId"]?.let { shelves[it.toLong()] } ?: magicShelves.getValue(p["magicShelfId"]!!.toLong())
                                ids.joinToString(",", "[", "]")
                            }

                            "/api/v1/app/books" -> {
                                page(p["page"]!!.toInt(), p["size"]!!.toInt())
                            }

                            else -> {
                                return@MockEngine respondError(HttpStatusCode.NotFound)
                            }
                        }
                    if (body == null) respondError(HttpStatusCode.InternalServerError) else respond(body, HttpStatusCode.OK, jsonHeaders)
                }
            }
        }

    private fun page(
        n: Int,
        size: Int,
    ): String? {
        maxSize = maxOf(maxSize, size)
        if (n == failOnPage) return null
        val all = titles.entries.sortedBy { it.key }
        val content = all.drop(n * size).take(size).joinToString(",") { """{"id":${it.key},"title":"${it.value}"}""" }
        val totalPages = (all.size + size - 1) / size
        val hasNext = hasNextStuck || n + 1 < totalPages
        return """{"content":[$content],"page":$n,"size":$size,"totalElements":${all.size},"totalPages":$totalPages,"hasNext":$hasNext}"""
    }
}

@RunWith(AndroidJUnit4::class)
class LibrarySyncTest {
    private lateinit var db: CatalogDb
    private lateinit var dir: File
    private lateinit var scope: CoroutineScope
    private lateinit var accounts: AccountRepository
    private lateinit var sync: LibrarySync
    private val server = FakeServer()
    private var now = 1_000L
    private var accountId = 0L

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, CatalogDb::class.java).allowMainThreadQueries().build()
        dir = Files.createTempDirectory("ds").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val ds = PreferenceDataStoreFactory.create(scope = scope) { File(dir, "settings.preferences_pb") }
        accounts = AccountRepository(db.accountDao(), XorCipher(), SettingsStore(ds))
        accountId =
            runBlocking {
                accounts.addOrUpdate("https://grim.example", "u", "1.0", AppUserInfo(canDownload = false), Tokens("a", "r"))
            }
        sync =
            LibrarySync(db.catalogDao(), accounts, { GrimmoryApi(authedClient(it.baseUrl, accounts.tokenSource(it.id), server.engine)) }) {
                now++
            }
    }

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun books(): List<BookEntity> =
        runBlocking {
            val r = db.catalogDao().books(BookQuery(accountId).toSql()).load(PagingSource.LoadParams.Refresh(null, 20_000, false))
            (r as PagingSource.LoadResult.Page).data.map { it.book }
        }

    private fun shelfBooks(q: BookQuery): List<Long> =
        runBlocking {
            val r =
                db
                    .catalogDao()
                    .books(q.toSql())
                    .load(PagingSource.LoadParams.Refresh(null, 100, false))
            (r as PagingSource.LoadResult.Page).data.map { it.book.id }.sorted()
        }

    private fun state() = sync.state.value[accountId]

    private fun account() = runBlocking { accounts.accounts.first().single() }

    @Test
    fun syncOf10050BooksStoresAllAndKeepsMemoryBoundedPerPage() =
        runBlocking {
            server.titles = (1L..10_050L).associateWith { "T" }
            val running = mutableListOf<SyncState.Running>()
            val collector = scope.launch { sync.state.collect { (it[accountId] as? SyncState.Running)?.let(running::add) } }
            sync.sync(accountId)
            collector.cancel()
            assertEquals(10_050, books().size)
            assertTrue(server.maxSize in 1..50)
            assertEquals(SyncState.Idle, state())
            val progress = running.filter { it.done > 0 }
            assertTrue(progress.isNotEmpty())
            assertTrue(progress.all { it.total == 10_050 })
        }

    @Test
    fun absentBookIsDeletedAndChangedTitleUpdated() =
        runBlocking {
            server.titles = mapOf(1L to "One", 2L to "Two", 3L to "Three")
            sync.sync(accountId)
            server.titles = mapOf(1L to "Uno", 3L to "Three")
            sync.sync(accountId)
            assertEquals(mapOf(1L to "Uno", 3L to "Three"), books().associate { it.id to it.title })
            assertTrue(account().lastSyncedAt != null)
        }

    @Test
    fun failedSweepDeletesNothing() =
        runBlocking {
            server.titles = (1L..200L).associateWith { "T" }
            sync.sync(accountId)
            val syncedAt = account().lastSyncedAt
            server.titles = (1L..10L).associateWith { "T" } + (1000L..1200L).associateWith { "N" }
            server.failOnPage = 3
            sync.sync(accountId)
            assertTrue(state() is SyncState.Failed)
            assertTrue(books().map { it.id }.containsAll((1L..200L).toList()))
            assertEquals(syncedAt, account().lastSyncedAt)
        }

    @Test
    fun serverStuckOnHasNextFailsWithoutDeleting() =
        runBlocking {
            server.titles = (1L..60L).associateWith { "T" }
            sync.sync(accountId)
            server.titles = (1L..10L).associateWith { "T" }
            server.hasNextStuck = true
            sync.sync(accountId)
            assertTrue(state() is SyncState.Failed)
            assertEquals(60, books().size)
        }

    @Test
    fun shelfMembershipComesFromIdsEndpoints() =
        runBlocking {
            server.titles = (1L..5L).associateWith { "T" }
            sync.sync(accountId)
            assertEquals(listOf(1L, 2L), shelfBooks(BookQuery(accountId, shelfId = 10)))
            assertEquals(listOf(3L), shelfBooks(BookQuery(accountId, magicShelfId = 10)))
            server.shelves = mapOf(10L to listOf(4L))
            server.magicShelves = emptyMap()
            sync.sync(accountId)
            assertEquals(listOf(4L), shelfBooks(BookQuery(accountId, shelfId = 10)))
            assertEquals(emptyList<Long>(), shelfBooks(BookQuery(accountId, magicShelfId = 10)))
        }

    @Test
    fun unauthorizedWithFailedRefreshSignsOutAndKeepsRows() =
        runBlocking {
            server.titles = (1L..3L).associateWith { "T" }
            sync.sync(accountId)
            server.unauthorized = true
            sync.sync(accountId)
            assertEquals(SyncState.SignedOut, state())
            assertTrue(account().signedOut)
            assertEquals(3, books().size)
        }

    @Test
    fun meNotFoundIsUnsupportedWithLiveVersion() =
        runBlocking {
            server.meMissing = true
            assertEquals(SyncState.Unsupported("v9.9.9"), sync.sync(accountId))
            assertEquals(SyncState.Unsupported("v9.9.9"), state())
        }

    @Test
    fun meNotFoundWithoutVersionIsUnsupportedNull() =
        runBlocking {
            server.meMissing = true
            server.version = null
            assertEquals(SyncState.Unsupported(null), sync.sync(accountId))
        }

    @Test
    fun notFoundLaterInSweepIsFailedNotUnsupported() =
        runBlocking {
            server.titles = (1L..3L).associateWith { "T" }
            server.shelfIdsMissing = true
            assertTrue(sync.sync(accountId) is SyncState.Failed)
        }

    @Test
    fun canDownloadFromMeIsStored() =
        runBlocking {
            assertFalse(account().canDownload)
            server.canDownload = true
            sync.sync(accountId)
            assertTrue(account().canDownload)
        }

    @Test
    fun mapsSummaryToEntity() {
        val e =
            BookSummaryDto(
                id = 7,
                title = null,
                authors = listOf("A", "B"),
                addedOn = "2026-01-02T03:04:05Z",
                lastReadTime = "not a date",
                coverUpdatedOn = "2026-01-02T03:04:05.5+01:00",
                primaryFileType = "AUDIOBOOK",
            ).toEntity(accountId = 2, generation = 9)
        assertEquals("", e.title)
        assertEquals("A, B", e.authors)
        assertEquals(1_767_323_045_000L, e.addedOn)
        assertNull(e.lastReadTime)
        assertEquals(1_767_319_445_500L, e.coverUpdatedOn)
        assertTrue(e.isAudiobook)
        assertEquals(2L, e.accountId)
        assertEquals(9L, e.syncGeneration)
    }

    @Test
    fun clockGoingBackwardsStillDeletesAbsentBooks() =
        runBlocking {
            server.titles = mapOf(1L to "A", 2L to "B")
            now = 2_000
            sync.sync(accountId)
            server.titles = mapOf(1L to "A")
            now = 1_000
            sync.sync(accountId)
            assertEquals(listOf(1L), books().map { it.id })
        }

    @Test
    fun syncReturnsFinalState() =
        runBlocking {
            server.titles = mapOf(1L to "A")
            assertEquals(SyncState.Idle, sync.sync(accountId))
            server.failOnPage = 0
            assertTrue(sync.sync(accountId) is SyncState.Failed)
        }

    @Test
    fun signedOutAccountSkipsNetwork() =
        runBlocking {
            accounts.tokenSource(accountId).expired()
            assertEquals(SyncState.SignedOut, sync.sync(accountId))
            assertEquals(SyncState.SignedOut, state())
            assertEquals(0, server.requests)
        }

    @Test
    fun shelfIdsFailureFailsWithoutDeleting() =
        runBlocking {
            server.titles = (1L..3L).associateWith { "T" }
            sync.sync(accountId)
            server.titles = emptyMap()
            server.failShelfIds = true
            assertTrue(sync.sync(accountId) is SyncState.Failed)
            assertEquals(3, books().size)
        }
}
