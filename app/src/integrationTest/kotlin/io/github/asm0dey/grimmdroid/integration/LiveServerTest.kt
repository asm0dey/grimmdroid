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
package io.github.asm0dey.grimmdroid.integration

import io.github.asm0dey.grimmdroid.api.CreateBookmarkDto
import io.github.asm0dey.grimmdroid.api.FileProgressDto
import io.github.asm0dey.grimmdroid.api.GrimmoryApi
import io.github.asm0dey.grimmdroid.api.ProbeResult
import io.github.asm0dey.grimmdroid.api.ServerProbe
import io.github.asm0dey.grimmdroid.api.TokenSource
import io.github.asm0dey.grimmdroid.api.Tokens
import io.github.asm0dey.grimmdroid.api.UpdateProgressDto
import io.github.asm0dey.grimmdroid.api.authedClient
import io.github.asm0dey.grimmdroid.api.httpClient
import io.github.asm0dey.grimmdroid.auth.LoginResult
import io.github.asm0dey.grimmdroid.auth.PasswordLogin
import io.github.asm0dey.grimmdroid.download.parseContentDisposition
import io.github.asm0dey.grimmdroid.reader.cfi.EpubArchive
import io.github.asm0dey.grimmdroid.reader.cfi.cfiFor
import io.github.asm0dey.grimmdroid.sync.AUDIOBOOK_FILE_TYPE
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val base = System.getenv("GRIMMORY_URL") ?: "http://localhost:6060"
private val fixtures = File(System.getProperty("grimmdroid.fixtures") ?: "../integration/fixtures")
private const val USER = "admin"
private const val PASSWORD = "integration-pass"

private class MemoryTokens(
    private var tokens: Tokens?,
) : TokenSource {
    private val lock = Any()
    private val saved = mutableListOf<Tokens>()

    fun savedSnapshot(): List<Tokens> = synchronized(lock) { saved.toList() }

    override suspend fun current() = synchronized(lock) { tokens }

    override suspend fun save(t: Tokens) =
        synchronized(lock) {
            saved += t
            tokens = t
        }

    override suspend fun expired() = synchronized(lock) { tokens = null }
}

private suspend fun until(
    what: String,
    timeoutSeconds: Int,
    check: suspend () -> Boolean,
) {
    repeat(timeoutSeconds) {
        if (runCatching { check() }.getOrDefault(false)) return
        delay(1.seconds)
    }
    error("Timed out after ${timeoutSeconds}s waiting for $what at $base")
}

private fun jwtClaim(
    token: String,
    claim: String,
): Long =
    Json
        .parseToJsonElement(String(Base64.getUrlDecoder().decode(token.split('.')[1])))
        .jsonObject[claim]!!
        .jsonPrimitive.long

private fun HttpResponse.ok() = also { check(status.isSuccess()) { "HTTP $status from ${call.request.url}" } }

class LiveServerTest :
    FunSpec({
        val plain = httpClient()
        lateinit var version: String
        lateinit var tokens: Tokens
        lateinit var api: GrimmoryApi

        beforeSpec {
            until("healthcheck", 300) { plain.get("$base/api/v1/healthcheck").status.isSuccess() }
            val setUp =
                plain
                    .get("$base/api/v1/setup/status")
                    .ok()
                    .body<JsonObject>()["data"]!!
                    .jsonPrimitive.boolean
            if (!setUp) {
                plain
                    .post("$base/api/v1/setup") {
                        contentType(ContentType.Application.Json)
                        setBody(mapOf("username" to USER, "email" to "admin@example.com", "name" to "Admin", "password" to PASSWORD))
                    }.ok()
            }
            // Display-only for login; the probe itself is asserted below.
            version = (ServerProbe(plain).probe(base) as? ProbeResult.Ok)?.version ?: "unknown"
            tokens = (PasswordLogin(plain).login(base, version, USER, PASSWORD) as LoginResult.Ok).tokens
            val libraries = plain.get("$base/api/v1/libraries") { bearerAuth(tokens.accessToken) }.ok().body<List<JsonObject>>()
            if (libraries.isEmpty()) {
                plain
                    .post("$base/api/v1/libraries") {
                        bearerAuth(tokens.accessToken)
                        contentType(ContentType.Application.Json)
                        setBody(
                            buildJsonObject {
                                put("name", "Fixtures")
                                putJsonArray("paths") { addJsonObject { put("path", "/books") } }
                                put("watch", false)
                                put("organizationMode", "BOOK_PER_FILE")
                            },
                        )
                    }.ok()
            }
            api = GrimmoryApi(authedClient(base, MemoryTokens(tokens)))
            until("the 5 fixture books to be scanned", 180) { api.books(0).totalElements == 5L }
        }

        test("probe is Ok with the server version") {
            val probe = ServerProbe(plain).probe(base)
            probe.shouldBeInstanceOf<ProbeResult.Ok>()
            probe.version shouldStartWith "v"
            probe.cleartext shouldBe base.startsWith("http://")
            api.version().current shouldBe probe.version
        }

        test("password login returns a user who may download") {
            val login = PasswordLogin(plain).login(base, version, USER, PASSWORD)
            login.shouldBeInstanceOf<LoginResult.Ok>()
            // The setup user is an admin: proves the server's "admin" key decodes into isAdmin.
            login.user.isAdmin shouldBe true
            login.user.canDownload shouldBe true
        }

        test("books page decodes, with the audiobook primaryFileType") {
            val page = api.books(0)
            page.content.size shouldBe 5
            page.hasNext shouldBe false
            page.content.map { it.primaryFileName } shouldContainExactlyInAnyOrder
                listOf("alice.epub", "pride.epub", "moby.epub", "time-machine.pdf", "flatland.mp3")
            page.content.associate { it.primaryFileName to it.primaryFileType } shouldBe
                mapOf(
                    "alice.epub" to "EPUB",
                    "pride.epub" to "EPUB",
                    "moby.epub" to "EPUB",
                    "time-machine.pdf" to "PDF",
                    "flatland.mp3" to AUDIOBOOK_FILE_TYPE,
                )
            page.content.forEach { book ->
                withClue(book.primaryFileName) {
                    Instant.parse(book.addedOn.shouldNotBeNull())
                    book.coverUpdatedOn?.let(Instant::parse)
                    book.publishedDate?.let(LocalDate::parse)
                    book.primaryFileId.shouldNotBeNull()
                }
            }
            page.content.single { it.primaryFileName == "alice.epub" }.authors shouldBe listOf("Lewis Carroll")
        }

        test("book detail decodes its files with the primary flag") {
            val id =
                api
                    .books(0)
                    .content
                    .single { it.primaryFileName == "alice.epub" }
                    .id
            val detail = api.book(id)
            detail.title shouldBe "Alice's Adventures in Wonderland"
            detail.primaryFileType shouldBe "EPUB"
            val file = detail.files.single()
            file.fileName shouldBe "alice.epub"
            file.isPrimary shouldBe true
            Instant.parse(detail.addedOn.shouldNotBeNull())
        }

        test("libraries list the fixture library") {
            api.libraries().map { it.name } shouldBe listOf("Fixtures")
        }

        test("bookIds returns every book id") {
            api.bookIds() shouldContainExactlyInAnyOrder api.books(0).content.map { it.id }
        }

        test("primary file download through authedClient returns the bytes and a filename") {
            val client = authedClient(base, MemoryTokens(tokens))
            val id =
                api
                    .books(0)
                    .content
                    .single { it.primaryFileName == "alice.epub" }
                    .id
            val response = client.get("api/v1/books/$id/download")
            parseContentDisposition(response.headers[HttpHeaders.ContentDisposition]) shouldBe "alice.epub"
            response.bodyAsBytes() shouldBe File(fixtures, "alice.epub").readBytes()
        }

        test("thumbnail through authedClient is an image") {
            val client = authedClient(base, MemoryTokens(tokens))
            // The generated PDF has a cover; the fixture EPUBs carry none, so the server answers 404 for them.
            val book = api.books(0).content.single { it.primaryFileName == "time-machine.pdf" }
            book.coverUpdatedOn.shouldNotBeNull()
            // Same URL as ui/library/Covers.kt coverUrl(), which can't be loaded here (Compose).
            val response = client.get("$base/api/v1/media/book/${book.id}/thumbnail")
            response.contentType()?.contentType shouldBe "image"
            response.bodyAsBytes().size shouldNotBe 0
        }

        test("a rejected access token is refreshed once and the request retried").config(timeout = 5.minutes) {
            // Refresh tokens carry nbf = iat + 2 min: the server rejects them before that.
            val fresh = (PasswordLogin(plain).login(base, version, USER, PASSWORD) as LoginResult.Ok).tokens
            val wait = jwtClaim(fresh.refreshToken, "nbf") * 1000 - System.currentTimeMillis() + 2000
            if (wait > 0) delay(wait)
            val store = MemoryTokens(fresh.copy(accessToken = "expired.${fresh.accessToken.substringAfter('.')}"))
            GrimmoryApi(authedClient(base, store)).me().isAdmin shouldBe true
            store.savedSnapshot().size shouldBe 1
            store.savedSnapshot().single().refreshToken shouldNotBe fresh.refreshToken
        }

        test("progress PUT is read back as the same CFI and percentage, and as a 0-1 KOReader fraction") {
            // KOReader progress is only written while the user's KOReader account syncs with the web reader.
            val koreader = authedClient(base, MemoryTokens(tokens))
            koreader
                .put("api/v1/koreader-users/me") {
                    contentType(ContentType.Application.Json)
                    setBody(mapOf("username" to "koreader", "password" to PASSWORD))
                }.ok()
            koreader.patch("api/v1/koreader-users/me/sync") { parameter("enabled", true) }.ok()
            koreader.patch("api/v1/koreader-users/me/sync-progress-with-grimmory") { parameter("enabled", true) }.ok()

            val book = api.books(0).content.single { it.primaryFileName == "moby.epub" }
            val cfi =
                EpubArchive(File(fixtures, "moby.epub")).use { epub ->
                    val at = 0.375 * epub.spine.size
                    epub.cfiFor(epub.spine[at.toInt()], null, at - at.toInt()).shouldNotBeNull()
                }
            api.putProgress(
                book.id,
                UpdateProgressDto(FileProgressDto(book.primaryFileId.shouldNotBeNull(), positionData = cfi, progressPercent = 37.5f)),
            )

            val progress = api.progress(book.id)
            // Also pins that the server returns the percentage together with the CFI (PositionSync relies on it).
            progress.epubProgress?.cfi shouldBe cfi
            progress.epubProgress?.percentage shouldBe 37.5f
            progress.koreaderProgress?.percentage shouldBe 0.375f
        }

        test("bookmark create, list and delete") {
            val book = api.books(0).content.single { it.primaryFileName == "pride.epub" }
            val created = api.createBookmark(CreateBookmarkDto(book.id, "epubcfi(/6/2!/4/2/1:0)", "Start"))
            created.bookId shouldBe book.id
            created.cfi shouldBe "epubcfi(/6/2!/4/2/1:0)"
            api.bookmarks(book.id).map { it.id } shouldContain created.id
            api.deleteBookmark(created.id)
            api.bookmarks(book.id).map { it.id } shouldNotContain created.id
        }

        test("custom fonts list for the admin user") {
            // Null would mean 403; the admin holds every permission, so the server answers a (possibly empty) list.
            api.customFonts().shouldNotBeNull()
        }

        test("OIDC state endpoint returns a state") {
            plain
                .get("$base/api/v1/auth/oidc/state")
                .ok()
                .body<JsonObject>()["state"]!!
                .jsonPrimitive.content
                .shouldNotBeBlank()
        }
    })
