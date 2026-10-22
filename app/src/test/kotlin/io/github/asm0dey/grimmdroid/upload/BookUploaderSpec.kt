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
package io.github.asm0dey.grimmdroid.upload

import io.github.asm0dey.grimmdroid.api.GrimmoryApi
import io.github.asm0dey.grimmdroid.api.TokenSource
import io.github.asm0dey.grimmdroid.api.Tokens
import io.github.asm0dey.grimmdroid.api.authedClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private object Tokens1 : TokenSource {
    override suspend fun current() = Tokens("a", "r")

    override suspend fun save(t: Tokens) = Unit

    override suspend fun expired() = Unit
}

private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

/** A fake server: book ids grow by [newId] once virtual time reaches [appearsAt]; any other request fails the test. */
private class FakeServer(
    private val scope: TestScope,
    private val uploadStatus: HttpStatusCode = HttpStatusCode.NoContent,
    private val newId: Long? = null,
    private val appearsAt: Long = 0,
) {
    var idCalls = 0
    var refreshes = 0
    var uploads = 0

    val api =
        GrimmoryApi(
            authedClient(
                "https://grim.example",
                Tokens1,
                // On the test dispatcher, so virtual time doesn't race past the timeout while a request is in flight.
                MockEngine(
                    MockEngineConfig().apply {
                        dispatcher = StandardTestDispatcher(scope.testScheduler)
                        addHandler { handle(it) }
                    },
                ),
            ),
        )

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.handle(it: io.ktor.client.request.HttpRequestData) =
        when {
            it.method == HttpMethod.Get && it.url.encodedPath == "/api/v1/app/books/ids" -> {
                idCalls++
                val ids = listOf(1L, 2L) + listOfNotNull(newId.takeIf { scope.testScheduler.currentTime >= appearsAt })
                respond(ids.joinToString(",", "[", "]"), HttpStatusCode.OK, json)
            }

            it.method == HttpMethod.Post && it.url.encodedPath == "/api/v1/files/upload" -> {
                uploads++
                respond("", uploadStatus)
            }

            it.method == HttpMethod.Put && it.url.encodedPath == "/api/v1/libraries/3/refresh" -> {
                refreshes++
                respond("", HttpStatusCode.NoContent)
            }

            else -> {
                error("unexpected ${it.method} ${it.url}")
            }
        }
}

private fun titled(title: String): File =
    File.createTempFile("upload", ".epub").apply {
        deleteOnExit()
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("""<container><rootfiles><rootfile full-path="c.opf"/></rootfiles></container>""".toByteArray())
            zip.putNextEntry(ZipEntry("c.opf"))
            zip.write("""<package><metadata><dc:title>$title</dc:title></metadata></package>""".toByteArray())
            zip.closeEntry()
        }
    }

private val watched = UploadTarget(3, 9, watched = true)
private val unwatched = UploadTarget(3, 9, watched = false)
private val noExisting: suspend (String) -> Long? = { null }

class BookUploaderSpec :
    FunSpec({
        test("new book appears after two polls → Found(new id), no refresh when watched") {
            runTest {
                val server = FakeServer(this, newId = 7, appearsAt = 4_000)
                BookUploader(server.api, noExisting).upload(titled("New"), "n.epub", watched) {} shouldBe UploadResult.Found(7)
                server.refreshes shouldBe 0
            }
        }

        test("unwatched library calls refresh and waits up to 180 s") {
            runTest {
                val server = FakeServer(this, newId = 7, appearsAt = 150_000)
                BookUploader(server.api, noExisting).upload(titled("New"), "n.epub", unwatched) {} shouldBe UploadResult.Found(7)
                server.refreshes shouldBe 1
            }
        }

        test("watched library gives up after 30 s → NotPickedUp") {
            runTest {
                val server = FakeServer(this, newId = 7, appearsAt = 31_000)
                BookUploader(server.api, noExisting).upload(titled("New"), "n.epub", watched) {} shouldBe UploadResult.NotPickedUp
            }
        }

        test("known title → Found(existing) right after upload, no polling") {
            runTest {
                val server = FakeServer(this)
                BookUploader(server.api) { if (it == "Dune") 2 else null }.upload(titled("Dune"), "d.epub", watched) {} shouldBe
                    UploadResult.Found(2)
                server.uploads shouldBe 1
                server.idCalls shouldBe 1
            }
        }

        test("upload 500 with known title → Found(existing)") {
            runTest {
                val server = FakeServer(this, uploadStatus = HttpStatusCode.InternalServerError)
                BookUploader(server.api) { if (it == "Dune") 2 else null }.upload(titled("Dune"), "d.epub", watched) {} shouldBe
                    UploadResult.Found(2)
            }
        }

        test("upload 500 with unknown title → Failed") {
            runTest {
                val server = FakeServer(this, uploadStatus = HttpStatusCode.InternalServerError)
                BookUploader(server.api, noExisting).upload(titled("New"), "n.epub", watched) {}.shouldBeInstanceOf<UploadResult.Failed>()
            }
        }

        test("403 → NoPermission") {
            runTest {
                val server = FakeServer(this, uploadStatus = HttpStatusCode.Forbidden)
                BookUploader(server.api, noExisting).upload(titled("New"), "n.epub", watched) {} shouldBe UploadResult.NoPermission
            }
        }
    })
