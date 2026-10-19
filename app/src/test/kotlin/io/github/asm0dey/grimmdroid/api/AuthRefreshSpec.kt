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
package io.github.asm0dey.grimmdroid.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
private const val ME_JSON = """{"isAdmin":true,"canDownload":true}"""

private class FakeTokens(
    var tokens: Tokens? = Tokens("old", "r-old"),
) : TokenSource {
    val saved = mutableListOf<Tokens>()
    var expiredCalls = 0

    override suspend fun current() = tokens

    override suspend fun save(t: Tokens) {
        saved += t
        tokens = t
    }

    override suspend fun expired() {
        expiredCalls++
        tokens = null
    }
}

private fun api(
    tokens: TokenSource,
    baseUrl: String = "https://grim.example",
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) = GrimmoryApi(authedClient(baseUrl, tokens, MockEngine(handler)))

class AuthRefreshSpec :
    FunSpec({
        test("401 mid-request triggers one refresh then retries") {
            val tokens = FakeTokens()
            val refreshes = AtomicInteger()
            var refreshBody = ""
            val me =
                api(tokens) { request ->
                    when (request.url.encodedPath) {
                        "/api/v1/auth/refresh" -> {
                            refreshes.incrementAndGet()
                            refreshBody = request.body.toByteArray().decodeToString()
                            respond("""{"accessToken":"new","refreshToken":"r-new"}""", HttpStatusCode.OK, jsonHeaders)
                        }

                        "/api/v1/app/users/me" -> {
                            if (request.headers[HttpHeaders.Authorization] == "Bearer new") {
                                respond(ME_JSON, HttpStatusCode.OK, jsonHeaders)
                            } else {
                                respondError(HttpStatusCode.Unauthorized)
                            }
                        }

                        else -> {
                            respondError(HttpStatusCode.NotFound)
                        }
                    }
                }.me()
            me shouldBe AppUserInfo(isAdmin = true, canDownload = true)
            refreshes.get() shouldBe 1
            refreshBody shouldBe """{"refreshToken":"r-old"}"""
            tokens.saved shouldBe listOf(Tokens("new", "r-new"))
        }

        test("refresh 401 is SessionExpiredException after exactly one refresh") {
            val tokens = FakeTokens()
            val refreshes = AtomicInteger()
            val api =
                api(tokens) { request ->
                    if (request.url.encodedPath == "/api/v1/auth/refresh") refreshes.incrementAndGet()
                    respondError(HttpStatusCode.Unauthorized)
                }
            shouldThrow<SessionExpiredException> { api.me() }
            refreshes.get() shouldBe 1
            tokens.expiredCalls shouldBe 1
            tokens.saved shouldBe emptyList()
        }

        test("concurrent 401s share a single refresh") {
            val tokens = FakeTokens()
            val refreshes = AtomicInteger()
            val api =
                api(tokens) { request ->
                    when {
                        request.url.encodedPath == "/api/v1/auth/refresh" -> {
                            refreshes.incrementAndGet()
                            delay(50)
                            respond("""{"accessToken":"new","refreshToken":"r-new"}""", HttpStatusCode.OK, jsonHeaders)
                        }

                        request.headers[HttpHeaders.Authorization] == "Bearer new" -> {
                            respond(ME_JSON, HttpStatusCode.OK, jsonHeaders)
                        }

                        else -> {
                            respondError(HttpStatusCode.Unauthorized)
                        }
                    }
                }
            val results = coroutineScope { List(5) { async { api.me() } }.awaitAll() }
            results.toSet() shouldBe setOf(AppUserInfo(isAdmin = true, canDownload = true))
            refreshes.get() shouldBe 1
            tokens.saved.size shouldBe 1
        }

        test("books(0) sends page, size, sort, dir and parses AppPageResponse") {
            var query = ""
            var path = ""
            val page =
                api(FakeTokens(), baseUrl = "https://grim.example/lib") { request ->
                    path = request.url.encodedPath
                    query = request.url.encodedQuery
                    respond(
                        """
                        {"content":[{"id":7,"title":"Dune","authors":["Frank Herbert"],"seriesName":"Dune","seriesNumber":1.0,
                        "libraryId":2,"addedOn":"2026-01-02T03:04:05Z","readProgress":0.25,"readStatus":"READING",
                        "primaryFileId":70,"primaryFileType":"EPUB","primaryFileName":"dune.epub","fileSizeKb":1234,
                        "pageCount":600,"language":"en","publishedDate":"1965-08-01","categories":["SF"],"tags":["x"]}],
                        "page":0,"size":50,"totalElements":51,"totalPages":2,"hasNext":true,"hasPrevious":false}
                        """.trimIndent(),
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }.books(0)
            path shouldBe "/lib/api/v1/app/books"
            query shouldBe "page=0&size=50&sort=addedOn&dir=asc"
            page.hasNext shouldBe true
            page.totalElements shouldBe 51
            page.content.single() shouldBe
                BookSummaryDto(
                    id = 7,
                    title = "Dune",
                    authors = listOf("Frank Herbert"),
                    seriesName = "Dune",
                    seriesNumber = 1.0f,
                    libraryId = 2,
                    addedOn = "2026-01-02T03:04:05Z",
                    readProgress = 0.25f,
                    readStatus = "READING",
                    primaryFileId = 70,
                    primaryFileType = "EPUB",
                    primaryFileName = "dune.epub",
                    fileSizeKb = 1234,
                    pageCount = 600,
                    language = "en",
                    publishedDate = "1965-08-01",
                    categories = listOf("SF"),
                )
        }
    })
