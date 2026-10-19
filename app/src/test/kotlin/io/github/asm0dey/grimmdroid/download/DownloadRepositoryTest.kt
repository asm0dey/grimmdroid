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
package io.github.asm0dey.grimmdroid.download

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.asm0dey.grimmdroid.api.TokenSource
import io.github.asm0dey.grimmdroid.api.Tokens
import io.github.asm0dey.grimmdroid.api.authedClient
import io.github.asm0dey.grimmdroid.catalog.BookEntity
import io.github.asm0dey.grimmdroid.catalog.CatalogDb
import io.github.asm0dey.grimmdroid.catalog.DownloadState
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files

private class FakeTokens : TokenSource {
    var tokens: Tokens? = Tokens("old", "r-old")

    override suspend fun current() = tokens

    override suspend fun save(t: Tokens) {
        tokens = t
    }

    override suspend fun expired() {
        tokens = null
    }
}

private const val ACCOUNT = 3L
private const val BOOK = 12L

@RunWith(AndroidJUnit4::class)
class DownloadRepositoryTest {
    private lateinit var db: CatalogDb
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, CatalogDb::class.java).allowMainThreadQueries().build()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        filesDir = Files.createTempDirectory("dl").toFile()
    }

    @After
    fun tearDown() {
        db.close()
        filesDir.deleteRecursively()
    }

    private fun repo(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        DownloadRepository(db.catalogDao(), { authedClient("https://grim.example", FakeTokens(), MockEngine(handler)) }, filesDir)

    private fun bookDir() = File(filesDir, "books/$ACCOUNT/$BOOK")

    private val epubHeaders =
        headersOf(
            HttpHeaders.ContentDisposition to listOf("""attachment; filename="moby.epub"; filename*=UTF-8''Moby%20Dick.epub"""),
            HttpHeaders.ContentLength to listOf("5242880"),
        )

    @Test
    fun downloadOf5MbWritesFileAndMarksDone() =
        runBlocking {
            var path = ""
            val progress = mutableListOf<Float>()
            val repo =
                repo { req ->
                    path = req.url.encodedPath
                    respond(ByteArray(5_242_880) { it.toByte() }, HttpStatusCode.OK, epubHeaders)
                }
            val file = repo.download(ACCOUNT, BOOK, 70, isPrimary = true) { progress += it }
            assertEquals("/api/v1/books/12/download", path)
            assertEquals(File(bookDir(), "Moby Dick.epub"), file)
            assertEquals(5_242_880L, file.length())
            val row = repo.observe(ACCOUNT, BOOK).first()!!
            assertEquals(DownloadState.DONE, row.state)
            assertEquals(5_242_880L, row.sizeBytes)
            assertEquals(file.path, row.path)
            assertEquals(1f, progress.last())
            assertEquals(listOf("Moby Dick.epub"), bookDir().list()!!.toList())
        }

    @Test
    fun nonPrimaryFileUsesFilesEndpoint() =
        runBlocking {
            var path = ""
            repo { req ->
                path = req.url.encodedPath
                respond("x", HttpStatusCode.OK)
            }.download(ACCOUNT, BOOK, 71, isPrimary = false) {}
            assertEquals("/api/v1/books/12/files/71/download", path)
        }

    @Test
    fun droppedConnectionMarksFailedAndDeletesPart() =
        runBlocking {
            val repo =
                repo {
                    val body =
                        CoroutineScope(Dispatchers.IO)
                            .writer {
                                channel.writeFully(ByteArray(1000))
                                channel.flush()
                                throw IOException("connection reset")
                            }.channel
                    respond(body, HttpStatusCode.OK, epubHeaders)
                }
            try {
                repo.download(ACCOUNT, BOOK, 70, isPrimary = true) {}
                fail("expected IOException")
            } catch (e: IOException) {
                // expected
            }
            assertEquals(DownloadState.FAILED, repo.observe(ACCOUNT, BOOK).first()!!.state)
            assertTrue(bookDir().list().orEmpty().isEmpty())
        }

    @Test
    fun cancelMidDownloadDeletesPartAndRow() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val repo =
                repo {
                    val body =
                        CoroutineScope(Dispatchers.IO)
                            .writer {
                                channel.writeFully(ByteArray(1000))
                                channel.flush()
                                awaitCancellation()
                            }.channel
                    respond(body, HttpStatusCode.OK, epubHeaders)
                }
            val job = launch(Dispatchers.IO) { repo.download(ACCOUNT, BOOK, 70, isPrimary = true) { started.complete(Unit) } }
            started.await()
            job.cancelAndJoin()
            assertNull(repo.observe(ACCOUNT, BOOK).first())
            assertTrue(bookDir().list().orEmpty().isEmpty())
        }

    @Test
    fun truncatedBodyIsAFailure() =
        runBlocking {
            val repo = repo { respond(ByteArray(10), HttpStatusCode.OK, epubHeaders) }
            try {
                repo.download(ACCOUNT, BOOK, 70, isPrimary = true) {}
                fail("expected IOException")
            } catch (e: IOException) {
                // expected
            }
            assertEquals(DownloadState.FAILED, repo.observe(ACCOUNT, BOOK).first()!!.state)
            assertTrue(bookDir().list().orEmpty().isEmpty())
        }

    @Test
    fun downloadResumesAfterTokenRefresh() =
        runBlocking {
            var downloads = 0
            val repo =
                repo { req ->
                    when {
                        req.url.encodedPath == "/api/v1/auth/refresh" -> {
                            respond(
                                """{"accessToken":"new","refreshToken":"r-new"}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        }

                        req.headers[HttpHeaders.Authorization] == "Bearer new" -> {
                            downloads++
                            respond("book", HttpStatusCode.OK)
                        }

                        else -> {
                            downloads++
                            respondError(HttpStatusCode.Unauthorized)
                        }
                    }
                }
            val file = repo.download(ACCOUNT, BOOK, 70, isPrimary = true) {}
            assertEquals(2, downloads)
            assertEquals("book", file.readText())
            assertEquals(DownloadState.DONE, repo.observe(ACCOUNT, BOOK).first()!!.state)
        }

    @Test
    fun forbiddenIsNoDownloadPermission() =
        runBlocking {
            val repo = repo { respondError(HttpStatusCode.Forbidden) }
            try {
                repo.download(ACCOUNT, BOOK, 70, isPrimary = true) {}
                fail("expected NoDownloadPermission")
            } catch (e: NoDownloadPermission) {
                // expected
            }
            assertEquals(DownloadState.FAILED, repo.observe(ACCOUNT, BOOK).first()!!.state)
        }

    @Test
    fun deleteRemovesFileAndRow() =
        runBlocking {
            val repo = repo { respond("book", HttpStatusCode.OK) }
            val file = repo.download(ACCOUNT, BOOK, 70, isPrimary = true) {}
            repo.delete(ApplicationProvider.getApplicationContext(), ACCOUNT, BOOK)
            assertFalse(file.exists())
            assertNull(repo.observe(ACCOUNT, BOOK).first())
        }

    @Test
    fun deleteStopsARunningDownloadFirst() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val repo = repo { respond(stalledBody(), HttpStatusCode.OK, epubHeaders) }
            val job = launch(Dispatchers.IO) { repo.download(ACCOUNT, BOOK, 70, isPrimary = true) { started.complete(Unit) } }
            started.await()
            repo.delete(ApplicationProvider.getApplicationContext(), ACCOUNT, BOOK)
            assertTrue(job.isCancelled)
            assertFalse(bookDir().exists())
            assertNull(repo.observe(ACCOUNT, BOOK).first())
        }

    @Test
    fun serverPrimaryFileNameCannotEscapeTheBookDir() =
        runBlocking {
            db.catalogDao().upsertBooks(
                listOf(
                    BookEntity(
                        ACCOUNT,
                        BOOK,
                        "T",
                        "A",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        70,
                        "EPUB",
                        "../../../x.epub",
                        null,
                        null,
                        false,
                        1,
                    ),
                ),
            )
            val file = repo { respond("book", HttpStatusCode.OK) }.download(ACCOUNT, BOOK, 70, isPrimary = true) {}
            assertEquals(bookDir().canonicalFile, file.canonicalFile.parentFile)
            assertEquals("___x.epub", file.name)
            assertEquals("___x.epub", repo { respondError(HttpStatusCode.NotFound) }.observe(ACCOUNT, BOOK).first()!!.fileName)
        }

    @Test
    fun failedReDownloadKeepsTheEarlierCopy() =
        runBlocking {
            val old =
                repo {
                    respond(
                        "old",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentDisposition, "attachment; filename*=UTF-8''Moby%20Dick.epub"),
                    )
                }.download(ACCOUNT, BOOK, 70, isPrimary = true) {}
            val repo =
                repo {
                    val body =
                        CoroutineScope(Dispatchers.IO)
                            .writer {
                                channel.writeFully(ByteArray(1000))
                                channel.flush()
                                throw IOException("connection reset")
                            }.channel
                    respond(body, HttpStatusCode.OK, epubHeaders)
                }
            try {
                repo.download(ACCOUNT, BOOK, 70, isPrimary = true) {}
                fail("expected IOException")
            } catch (e: IOException) {
                // expected
            }
            assertEquals("old", old.readText())
            assertEquals(listOf(old.name), bookDir().list()!!.toList())
            val row = repo.observe(ACCOUNT, BOOK).first()!!
            assertEquals(DownloadState.DONE, row.state)
            assertEquals(old.path, row.path)
        }

    @Test
    fun errorCodesTellPermissionApartFromNetwork() {
        assertEquals("no_permission", downloadErrorCode(NoDownloadPermission()))
        assertEquals("network", downloadErrorCode(IOException("reset")))
        assertEquals("other", downloadErrorCode(IllegalStateException()))
    }

    private fun stalledBody() =
        CoroutineScope(Dispatchers.IO)
            .writer {
                channel.writeFully(ByteArray(1000))
                channel.flush()
                awaitCancellation()
            }.channel

    @Test
    fun enqueueIsUniquePerBookAndCancelRemovesIt() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx)
        val repo = repo { respondError(HttpStatusCode.NotFound) }
        repo.enqueue(ctx, ACCOUNT, BOOK, 70, isPrimary = true)
        repo.enqueue(ctx, ACCOUNT, BOOK, 70, isPrimary = true)
        val wm = WorkManager.getInstance(ctx)
        val info = wm.getWorkInfosForUniqueWork("dl-$ACCOUNT-$BOOK").get().single()
        repo.cancel(ctx, ACCOUNT, BOOK)
        assertTrue(
            wm
                .getWorkInfoById(info.id)
                .get()!!
                .state.isFinished,
        )
    }
}
