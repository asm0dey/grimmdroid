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

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.asm0dey.grimmdroid.api.GrimmoryApi
import io.github.asm0dey.grimmdroid.api.TokenSource
import io.github.asm0dey.grimmdroid.api.Tokens
import io.github.asm0dey.grimmdroid.api.authedClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

private const val ACCOUNT = 4L
private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
private val TTF = byteArrayOf(0, 1, 0, 0, 9, 9)
private val OTF = "OTTO".toByteArray() + byteArrayOf(7)

private object FontTokens : TokenSource {
    override suspend fun current() = Tokens("a", "r")

    override suspend fun save(t: Tokens) = Unit

    override suspend fun expired() = Unit
}

/** Grimmory's custom-font endpoints; any other request fails the test. */
private class FontServer {
    var listJson =
        """[{"id":1,"fontName":"Literata","format":"TTF","fileSize":6},{"id":2,"fontName":"Bad/Name","format":"OTF","fileSize":5}]"""
    var forbidden = false
    val requests = mutableListOf<String>()
    private val files = mapOf(1L to TTF, 2L to OTF)

    val api =
        GrimmoryApi(
            authedClient(
                "https://grim.example",
                FontTokens,
                MockEngine { req ->
                    val path = req.url.encodedPath
                    requests += path
                    when {
                        req.method != HttpMethod.Get -> {
                            error("unexpected ${req.method} ${req.url}")
                        }

                        path == "/api/v1/custom-fonts" && forbidden -> {
                            respondError(HttpStatusCode.Forbidden)
                        }

                        path == "/api/v1/custom-fonts" -> {
                            respond(listJson, HttpStatusCode.OK, json)
                        }

                        path.matches(Regex("/api/v1/custom-fonts/\\d+/file")) -> {
                            respond(files.getValue(path.split('/')[4].toLong()), HttpStatusCode.OK)
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
class FontsTest {
    private val filesDir = Files.createTempDirectory("files").toFile()
    private val server = FontServer()
    private val fonts = Fonts(filesDir) { id -> server.api.takeIf { id == ACCOUNT } }
    private val serverDir = filesDir.resolve("fonts/$ACCOUNT")

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    private fun names() = serverDir.list()!!.sorted()

    @Test
    fun syncDownloadsMissingFontsUnderSanitizedNames() =
        runBlocking {
            fonts.syncServer(ACCOUNT)
            assertEquals(listOf("Bad_Name.otf", "Literata.ttf"), names())
            assertTrue(serverDir.resolve("Literata.ttf").readBytes().contentEquals(TTF))
            assertEquals(
                listOf(
                    ReaderFont("Bad_Name", serverDir.resolve("Bad_Name.otf"), true),
                    ReaderFont("Literata", serverDir.resolve("Literata.ttf"), true),
                ),
                fonts.list(ACCOUNT),
            )
        }

    @Test
    fun secondSyncOnlyLists() =
        runBlocking {
            fonts.syncServer(ACCOUNT)
            server.requests.clear()
            fonts.syncServer(ACCOUNT)
            assertEquals(listOf("/api/v1/custom-fonts"), server.requests)
        }

    @Test
    fun fontGoneFromServerIsDeleted() =
        runBlocking {
            fonts.syncServer(ACCOUNT)
            server.listJson = """[{"id":1,"fontName":"Literata","format":"TTF"}]"""
            fonts.syncServer(ACCOUNT)
            assertEquals(listOf("Literata.ttf"), names())
        }

    @Test
    fun forbiddenListKeepsExistingFiles() =
        runBlocking {
            fonts.syncServer(ACCOUNT)
            server.forbidden = true
            fonts.syncServer(ACCOUNT)
            assertEquals(listOf("Bad_Name.otf", "Literata.ttf"), names())
        }

    @Test
    fun missingAccountMakesNoRequests() =
        runBlocking {
            fonts.syncServer(ACCOUNT + 1)
            assertEquals(emptyList<String>(), server.requests)
        }

    @Test
    fun importRejectsNonFontsAndKeepsFonts() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val png =
                Files.createTempFile("pic", ".png").toFile().apply {
                    writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()))
                }
            assertTrue(fonts.import(context, Uri.fromFile(png)).isFailure)
            assertEquals(emptyList<ReaderFont>(), fonts.list(ACCOUNT))

            val ttf =
                Files
                    .createTempDirectory("dl")
                    .toFile()
                    .resolve("My Font.ttf")
                    .apply { writeBytes(TTF) }
            val imported = fonts.import(context, Uri.fromFile(ttf)).getOrThrow()
            assertEquals(ReaderFont("My Font", filesDir.resolve("fonts/local/My Font.ttf"), false), imported)
            assertTrue(imported.file.readBytes().contentEquals(TTF))
            assertEquals(listOf(imported), fonts.list(ACCOUNT))

            fonts.delete(imported)
            assertEquals(emptyList<ReaderFont>(), fonts.list(ACCOUNT))
        }
}
