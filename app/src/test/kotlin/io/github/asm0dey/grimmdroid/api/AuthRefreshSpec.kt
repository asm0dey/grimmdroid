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
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.Base64
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

/** An unsigned JWT whose payload is [payload]; only the payload is read client-side. */
private fun jwt(payload: String): String {
    val enc = Base64.getUrlEncoder().withoutPadding()
    return listOf("""{"alg":"none"}""", payload).joinToString(".") { enc.encodeToString(it.encodeToByteArray()) } + ".sig"
}

private fun nowSeconds() = System.currentTimeMillis() / 1000

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

        test("refresh IOException surfaces as-is and does not sign out") {
            val tokens = FakeTokens()
            val api =
                api(tokens) { request ->
                    if (request.url.encodedPath == "/api/v1/auth/refresh") throw IOException("offline")
                    respondError(HttpStatusCode.Unauthorized)
                }
            shouldThrow<IOException> { api.me() }.message shouldBe "offline"
            tokens.expiredCalls shouldBe 0
            tokens.tokens shouldBe Tokens("old", "r-old")
        }

        test("refresh 503 does not sign out") {
            val tokens = FakeTokens()
            val api =
                api(tokens) { request ->
                    if (request.url.encodedPath == "/api/v1/auth/refresh") {
                        respondError(HttpStatusCode.ServiceUnavailable)
                    } else {
                        respondError(HttpStatusCode.Unauthorized)
                    }
                }
            shouldThrow<IOException> { api.me() }
            tokens.expiredCalls shouldBe 0
        }

        test("refresh 400 signs out") {
            val tokens = FakeTokens()
            val api =
                api(tokens) { request ->
                    if (request.url.encodedPath == "/api/v1/auth/refresh") {
                        respondError(HttpStatusCode.BadRequest)
                    } else {
                        respondError(HttpStatusCode.Unauthorized)
                    }
                }
            shouldThrow<SessionExpiredException> { api.me() }
            tokens.expiredCalls shouldBe 1
        }

        test("401 that persists after a successful refresh signs out") {
            val tokens = FakeTokens()
            val api =
                api(tokens) { request ->
                    if (request.url.encodedPath == "/api/v1/auth/refresh") {
                        respond("""{"accessToken":"new","refreshToken":"r-new"}""", HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respondError(HttpStatusCode.Unauthorized)
                    }
                }
            shouldThrow<SessionExpiredException> { api.me() }
            tokens.expiredCalls shouldBe 1
        }

        test("two clients sharing one TokenSource refresh once") {
            val tokens = FakeTokens()
            val refreshes = AtomicInteger()
            val handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { request ->
                when {
                    request.url.encodedPath == "/api/v1/auth/refresh" -> {
                        refreshes.incrementAndGet()
                        delay(50)
                        if (request.body.toByteArray().decodeToString() == """{"refreshToken":"r-old"}""") {
                            respond("""{"accessToken":"new","refreshToken":"r-new"}""", HttpStatusCode.OK, jsonHeaders)
                        } else {
                            respondError(HttpStatusCode.Unauthorized) // rotated token reused
                        }
                    }

                    request.headers[HttpHeaders.Authorization] == "Bearer new" -> {
                        respond(ME_JSON, HttpStatusCode.OK, jsonHeaders)
                    }

                    else -> {
                        respondError(HttpStatusCode.Unauthorized)
                    }
                }
            }
            val ui = api(tokens, "https://two.example", handler)
            val worker = api(tokens, "https://two.example", handler)
            val results = coroutineScope { listOf(async { ui.me() }, async { worker.me() }).awaitAll() }
            results.toSet() shouldBe setOf(AppUserInfo(isAdmin = true, canDownload = true))
            refreshes.get() shouldBe 1
            tokens.expiredCalls shouldBe 0
        }

        test("bearer token is not sent to, nor refreshed for, a foreign host") {
            val tokens = FakeTokens()
            val refreshes = AtomicInteger()
            var foreignAuth: String? = "unset"
            val client =
                authedClient(
                    "https://grim.example",
                    tokens,
                    MockEngine { request ->
                        if (request.url.encodedPath == "/api/v1/auth/refresh") refreshes.incrementAndGet()
                        foreignAuth = request.headers[HttpHeaders.Authorization]
                        respondError(HttpStatusCode.Unauthorized)
                    },
                )
            shouldThrow<ClientRequestException> { client.get("https://cdn.example/cover.jpg") }
            foreignAuth shouldBe null
            refreshes.get() shouldBe 0
            tokens.expiredCalls shouldBe 0
        }

        test("secrets are redacted from toString") {
            val t = Tokens("acc-SECRET-1", "ref-SECRET-2")
            t.toString() shouldNotContain "SECRET"
            LoginRequest("pavel", "pw-SECRET-3").toString() shouldNotContain "SECRET"
            RefreshRequest("ref-SECRET-2").toString() shouldNotContain "SECRET"
        }

        // Grimmory's refresh tokens carry nbf = iat + 2 min; refreshing earlier answers the same 401 as a revoked token.
        test("refresh token not valid yet fails transiently without calling the server") {
            val tokens = FakeTokens(Tokens("old", jwt("""{"nbf":${nowSeconds() + 120}}""")))
            val refreshes = AtomicInteger()
            val api =
                api(tokens) { request ->
                    if (request.url.encodedPath == "/api/v1/auth/refresh") refreshes.incrementAndGet()
                    respondError(HttpStatusCode.Unauthorized)
                }
            shouldThrow<RefreshFailedException> { api.me() }
            refreshes.get() shouldBe 0
            tokens.expiredCalls shouldBe 0
        }

        test("refresh token within the server's clock skew, past nbf, or unreadable is sent") {
            for (refresh in listOf(jwt("""{"nbf":${nowSeconds() + 30}}"""), jwt("""{"nbf":${nowSeconds() - 5}}"""), "opaque", jwt("[]"))) {
                val tokens = FakeTokens(Tokens("old", refresh))
                val refreshes = AtomicInteger()
                val api =
                    api(tokens) { request ->
                        if (request.url.encodedPath == "/api/v1/auth/refresh") refreshes.incrementAndGet()
                        respondError(HttpStatusCode.Unauthorized)
                    }
                shouldThrow<SessionExpiredException> { api.me() }
                refreshes.get() shouldBe 1
            }
        }
    })
