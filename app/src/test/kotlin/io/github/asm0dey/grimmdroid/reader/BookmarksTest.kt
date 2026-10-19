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

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.asm0dey.grimmdroid.account.AccountEntity
import io.github.asm0dey.grimmdroid.api.GrimmoryApi
import io.github.asm0dey.grimmdroid.api.TokenSource
import io.github.asm0dey.grimmdroid.api.Tokens
import io.github.asm0dey.grimmdroid.api.authedClient
import io.github.asm0dey.grimmdroid.catalog.CatalogDb
import io.github.asm0dey.grimmdroid.reader.cfi.EpubArchive
import io.github.asm0dey.grimmdroid.reader.cfi.tinyEpub
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import java.io.IOException

private const val BOOK = 7L
private const val CFI = "epubcfi(/6/4!/4/2)"
private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

private object StaticTokens : TokenSource {
    override suspend fun current() = Tokens("a", "r")

    override suspend fun save(t: Tokens) = Unit

    override suspend fun expired() = Unit
}

/** Grimmory's bookmark endpoints for book 7; any other request fails the test. */
private class BookmarkServer {
    var listJson = "[]"
    var failWith: Exception? = null
    val requests = mutableListOf<String>()
    var nextId = 5L

    val api =
        GrimmoryApi(
            authedClient(
                "https://grim.example",
                StaticTokens,
                MockEngine { req ->
                    failWith?.let { throw it }
                    val path = req.url.encodedPath
                    when {
                        req.method == HttpMethod.Get && path == "/api/v1/bookmarks/book/$BOOK" -> {
                            requests += "GET $path"
                            respond(listJson, HttpStatusCode.OK, json)
                        }

                        req.method == HttpMethod.Post && path == "/api/v1/bookmarks" -> {
                            val body = String(req.body.toByteArray())
                            requests += "POST $body"
                            val id = nextId++
                            respond("""{"id":$id,"bookId":$BOOK,"cfi":"$CFI"}""", HttpStatusCode.OK, json)
                        }

                        req.method == HttpMethod.Delete && path.startsWith("/api/v1/bookmarks/") -> {
                            requests += "DELETE $path"
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
class BookmarksTest {
    private val db =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CatalogDb::class.java)
            .allowMainThreadQueries()
            .build()
    private val dao = db.readerDao()
    private val server = BookmarkServer()
    private val enqueued = mutableListOf<Long>()
    private val bookmarks = Bookmarks(dao, { db.accountDao().get(it) }, { server.api }) { enqueued += it }
    private val archive = EpubArchive(tinyEpub(c2Linear = true))
    private val account = account()

    @After
    fun tearDown() {
        archive.close()
        db.close()
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

    private val locator =
        Locator(
            Url("c2.xhtml")!!,
            MediaType.XHTML,
            title = "Two",
            locations = Locator.Locations(progression = 0.0, totalProgression = 0.5, otherLocations = mapOf("cssSelector" to "#a")),
        )

    private suspend fun rows(accountId: Long = account) = dao.bookmarks(accountId, BOOK).first()

    @Test
    fun addOnlinePostsAndMarksSynced() =
        runBlocking {
            bookmarks.add(account, BOOK, locator, CFI, "Two — Alpha")
            assertEquals(listOf("""POST {"bookId":7,"cfi":"$CFI","title":"Two — Alpha"}"""), server.requests)
            val row = rows().single()
            assertEquals(BookmarkState.SYNCED, row.state)
            assertEquals(5L, row.serverId)
            assertEquals(0.5, row.totalProgression, 0.0)
            assertTrue(enqueued.isEmpty())
        }

    @Test
    fun addOfflineStaysPendingAndFlushPostsIt() =
        runBlocking {
            server.failWith = IOException("offline")
            bookmarks.add(account, BOOK, locator, CFI, "Two — Alpha")
            assertEquals(BookmarkState.PENDING_CREATE, rows().single().state)
            assertEquals(listOf(account), enqueued)
            server.failWith = null
            assertTrue(bookmarks.flush(account))
            assertEquals(listOf("""POST {"bookId":7,"cfi":"$CFI","title":"Two — Alpha"}"""), server.requests)
            assertEquals(BookmarkState.SYNCED, rows().single().state)
            assertEquals(5L, rows().single().serverId)
        }

    @Test
    fun flushFailureReportsRetryWithoutEnqueueing() =
        runBlocking {
            server.failWith = IOException("offline")
            bookmarks.add(account, BOOK, locator, CFI, "t")
            enqueued.clear()
            assertFalse(bookmarks.flush(account))
            assertTrue(enqueued.isEmpty())
        }

    @Test
    fun removePendingCreateMakesNoRequest() =
        runBlocking {
            server.failWith = IOException("offline")
            bookmarks.add(account, BOOK, locator, CFI, "t")
            server.failWith = null
            bookmarks.remove(rows().single())
            assertTrue(rows().isEmpty())
            assertTrue(dao.pendingBookmarks().isEmpty())
            assertTrue(server.requests.isEmpty())
        }

    @Test
    fun removeSyncedSendsDelete() =
        runBlocking {
            bookmarks.add(account, BOOK, locator, CFI, "t")
            server.requests.clear()
            bookmarks.remove(rows().single())
            assertEquals(listOf("DELETE /api/v1/bookmarks/5"), server.requests)
            assertTrue(rows().isEmpty())
            assertTrue(dao.pendingBookmarks().isEmpty())
        }

    @Test
    fun removeOfflineHidesRowAndFlushDeletesIt() =
        runBlocking {
            bookmarks.add(account, BOOK, locator, CFI, "t")
            server.failWith = IOException("offline")
            bookmarks.remove(rows().single())
            assertTrue(rows().isEmpty())
            assertEquals(BookmarkState.PENDING_DELETE, dao.pendingBookmarks().single().state)
            assertEquals(listOf(account), enqueued)
            server.failWith = null
            server.requests.clear()
            assertTrue(bookmarks.flush(account))
            assertEquals(listOf("DELETE /api/v1/bookmarks/5"), server.requests)
            assertTrue(dao.pendingBookmarks().isEmpty())
        }

    @Test
    fun removeOfAStaleRowObjectFollowsTheCurrentState() =
        runBlocking {
            server.failWith = IOException("offline")
            bookmarks.add(account, BOOK, locator, CFI, "t")
            val stale = rows().single() // PENDING_CREATE
            server.failWith = null
            bookmarks.flush(account) // now SYNCED on the server
            server.requests.clear()
            bookmarks.remove(stale)
            assertEquals(listOf("DELETE /api/v1/bookmarks/5"), server.requests)
        }

    @Test
    fun refreshKeepsPendingRowsAndReplacesSyncedOnes() =
        runBlocking {
            bookmarks.add(account, BOOK, locator, CFI, "old synced") // id 5
            bookmarks.add(account, BOOK, locator, CFI, "deleting") // id 6
            server.failWith = IOException("offline")
            bookmarks.remove(rows().single { it.serverId == 6L })
            bookmarks.add(account, BOOK, locator, CFI, "pending")
            server.failWith = null
            server.requests.clear()
            server.listJson =
                """[{"id":6,"bookId":7,"cfi":"$CFI","title":"deleting"},""" +
                """{"id":9,"bookId":7,"cfi":"epubcfi(/6/4!/4/4)","title":"web"},""" +
                """{"id":10,"bookId":7,"cfi":"garbage","title":"broken"}]"""
            bookmarks.refresh(account, BOOK, archive)
            assertEquals(listOf("GET /api/v1/bookmarks/book/$BOOK"), server.requests)
            val shown = rows()
            assertEquals(setOf("pending", "web", "broken"), shown.map { it.title }.toSet())
            val web = shown.single { it.title == "web" }
            assertEquals(BookmarkState.SYNCED, web.state)
            assertEquals(9L, web.serverId)
            val loc = Locator.fromJSON(JSONObject(web.locatorJson))!!
            assertEquals("c2.xhtml", loc.href.toString())
            assertTrue(loc.locations.progression!! > 0.0) // Beta, after Alpha
            assertTrue(web.totalProgression in 0.5..1.0) // second of two spine items
            // An unresolvable CFI is still listed, with nowhere to go.
            assertEquals(null, Locator.fromJSON(JSONObject(shown.single { it.title == "broken" }.locatorJson)))
            // The pending delete stays pending, not shown.
            assertEquals(BookmarkState.PENDING_DELETE, dao.pendingBookmarks().single { it.serverId == 6L }.state)
        }

    @Test
    fun refreshFailureKeepsRows() =
        runBlocking {
            bookmarks.add(account, BOOK, locator, CFI, "t")
            server.failWith = IOException("offline")
            bookmarks.refresh(account, BOOK, archive)
            assertEquals(1, rows().size)
        }

    @Test
    fun signedOutOrRemovedAccountsSendNothing() =
        runBlocking {
            val signedOut = account(signedOut = true)
            bookmarks.add(signedOut, BOOK, locator, CFI, "t")
            bookmarks.add(99, BOOK, locator, CFI, "t")
            bookmarks.refresh(signedOut, BOOK, archive)
            bookmarks.flush(signedOut)
            assertTrue(server.requests.isEmpty())
            assertTrue(enqueued.isEmpty())
            assertEquals(BookmarkState.PENDING_CREATE, rows(signedOut).single().state)
        }

    @Test
    fun titleJoinsChapterAndText() {
        assertEquals("Two — Alpha beta", bookmarkTitle("Two", Locator.Text(highlight = "  Alpha\n beta ")))
        assertEquals("Two — after", bookmarkTitle("Two", Locator.Text(highlight = " ", after = "after")))
        assertEquals("x".repeat(60), bookmarkTitle(null, Locator.Text(highlight = "x".repeat(80))))
        assertEquals("Two", bookmarkTitle("Two", null))
        assertEquals("Two — Alpha", bookmarkTitle("Two", Locator.Text(highlight = "Two", after = "Alpha")))
    }

    private fun at(
        href: String,
        progression: Double,
    ) = BookmarkRow(
        0,
        1,
        BOOK,
        null,
        null,
        locator
            .copy(href = Url(href)!!)
            .copyWithLocations(progression = progression)
            .toJSON()
            .toString(),
        "t",
        0.9,
        BookmarkState.SYNCED,
    )

    @Test
    fun onPageMeansTheSameResourceFromThisPageToTheNext() {
        val page = PageSpan("c2.xhtml", 0.25, 0.5)
        assertTrue(at("c2.xhtml", 0.25).isOn(page))
        assertTrue(at("c2.xhtml", 0.4).isOn(page))
        assertFalse(at("c2.xhtml", 0.5).isOn(page)) // the next page
        assertFalse(at("c2.xhtml", 0.2).isOn(page))
        assertFalse(at("c1.xhtml", 0.3).isOn(page))
        // A short page (large font, long chapter): the window is that page only.
        val short = PageSpan("c2.xhtml", 0.3, 0.31)
        assertTrue(at("c2.xhtml", 0.305).isOn(short))
        assertFalse(at("c2.xhtml", 0.31).isOn(short))
        // The last page holds the resource's end.
        assertTrue(at("c2.xhtml", 1.0).isOn(PageSpan("c2.xhtml", 0.75, 1.0)))
        // An unresolved server bookmark is on no page.
        assertFalse(at("c2.xhtml", 0.3).copy(locatorJson = "{}").isOn(page))
    }
}
