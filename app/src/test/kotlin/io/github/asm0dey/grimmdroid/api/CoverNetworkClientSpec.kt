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

import coil3.network.NetworkRequest
import io.github.asm0dey.grimmdroid.account.AccountEntity
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

private class CoverTokens(
    var tokens: Tokens? = Tokens("old", "r-old"),
) : TokenSource {
    override suspend fun current() = tokens

    override suspend fun save(t: Tokens) {
        tokens = t
    }

    override suspend fun expired() = Unit
}

private const val BASE = "https://grim.example:8443/sub"
private const val COVER = "$BASE/api/v1/media/book/1/thumbnail?t=5"

private fun account(signedOut: Boolean = false) =
    AccountEntity(1, BASE, "u", "1", canDownload = true, isAdmin = false, encryptedTokens = null, signedOut = signedOut)

class CoverNetworkClientSpec :
    FunSpec({
        // Records every request; covers answer 200 only to the "new" token when [rotate] is set.
        class Server(
            val rotate: Boolean = false,
        ) {
            val seen = mutableListOf<Pair<String, String?>>()
            val refreshes get() = seen.count { it.first.endsWith("/auth/refresh") }
            val tokens = CoverTokens()
            val engine =
                MockEngine { request ->
                    val auth = request.headers[HttpHeaders.Authorization]
                    seen += request.url.toString() to auth
                    when {
                        request.url.encodedPath.endsWith("/auth/refresh") -> {
                            respond(
                                """{"accessToken":"new","refreshToken":"r-new"}""",
                                HttpStatusCode.OK,
                                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                            )
                        }

                        rotate && auth != "Bearer new" -> {
                            respondError(HttpStatusCode.Unauthorized)
                        }

                        else -> {
                            respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK)
                        }
                    }
                }

            fun client(account: AccountEntity? = account()) =
                CoverNetworkClient({ account }, { authedClient(it.baseUrl, tokens, engine) }, httpClient(engine))

            suspend fun fetch(
                url: String,
                account: AccountEntity? = account(),
            ) = client(account).executeRequest(NetworkRequest(url)) { it.code }
        }

        test("a cover on the account's host carries the bearer") {
            val server = Server()
            server.fetch(COVER) shouldBe 200
            server.seen shouldBe listOf(COVER to "Bearer old")
        }

        test("a cover on another host or port gets no Authorization header") {
            val server = Server()
            server.fetch("https://cdn.example/x.jpg") shouldBe 200
            server.fetch("https://grim.example/x.jpg") shouldBe 200
            server.seen.map { it.second } shouldBe listOf(null, null)
        }

        test("a 401 refreshes exactly once through the authed client, then retries") {
            val server = Server(rotate = true)
            server.fetch(COVER) shouldBe 200
            server.refreshes shouldBe 1
            server.seen.last() shouldBe (COVER to "Bearer new")
            server.tokens.tokens shouldBe Tokens("new", "r-new")
        }

        test("a signed-out account loads covers without a token and never refreshes") {
            val server = Server(rotate = true)
            server.fetch(COVER, account(signedOut = true)) shouldBe 401
            server.fetch(COVER, account = null) shouldBe 401
            server.refreshes shouldBe 0
            server.seen.map { it.second } shouldBe listOf(null, null)
        }
    })
