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

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

private class CoverTokens(
    var tokens: Tokens? = Tokens("old", "r-old"),
) : TokenSource {
    var expiredCalls = 0

    override suspend fun current() = tokens

    override suspend fun save(t: Tokens) {
        tokens = t
    }

    override suspend fun expired() {
        expiredCalls++
    }
}

private fun unauthorized(
    url: String,
    token: String? = "old",
    prior: Response? = null,
): Response {
    val request =
        Request
            .Builder()
            .url(url)
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()
    return Response
        .Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(401)
        .message("Unauthorized")
        .priorResponse(prior)
        .build()
}

class CoverAuthSpec :
    FunSpec({
        val base = "https://grim.example/sub"
        val cover = "https://grim.example/sub/api/v1/media/book/1/thumbnail"

        fun fixture(refreshStatus: HttpStatusCode = HttpStatusCode.OK): Triple<CoverTokens, MutableList<String>, okhttp3.Authenticator> {
            val tokens = CoverTokens()
            val refreshed = mutableListOf<String>()
            val client =
                httpClient(
                    MockEngine { request ->
                        refreshed += request.url.toString()
                        respond(
                            """{"accessToken":"new","refreshToken":"r-new"}""",
                            refreshStatus,
                            headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                )
            return Triple(tokens, refreshed, coverAuthenticator({ CoverSession(base, tokens) }, client))
        }

        test("a cover 401 refreshes once and retries with the new token") {
            val (tokens, refreshed, auth) = fixture()
            val retry = auth.authenticate(null, unauthorized(cover))
            retry?.header("Authorization") shouldBe "Bearer new"
            retry?.url.toString() shouldBe cover
            refreshed shouldBe listOf("https://grim.example/sub/api/v1/auth/refresh")
            tokens.tokens shouldBe Tokens("new", "r-new")
        }

        test("a second 401 on the retried request does not refresh again") {
            val (_, refreshed, auth) = fixture()
            auth.authenticate(null, unauthorized(cover, "new", prior = unauthorized(cover))).shouldBeNull()
            refreshed shouldBe emptyList()
        }

        test("a 401 from a foreign host is not refreshed") {
            val (_, refreshed, auth) = fixture()
            auth.authenticate(null, unauthorized("https://cdn.example/sub/x.jpg")).shouldBeNull()
            auth.authenticate(null, unauthorized("https://grim.example:8443/sub/x.jpg")).shouldBeNull()
            refreshed shouldBe emptyList()
        }

        test("tokens already rotated by another client are reused without a refresh") {
            val (tokens, refreshed, auth) = fixture()
            tokens.tokens = Tokens("rotated", "r-rotated")
            auth.authenticate(null, unauthorized(cover))?.header("Authorization") shouldBe "Bearer rotated"
            refreshed shouldBe emptyList()
        }

        test("a rejected refresh signs out; a failing server keeps the session") {
            val (rejected, _, rejectAuth) = fixture(HttpStatusCode.Unauthorized)
            rejectAuth.authenticate(null, unauthorized(cover)).shouldBeNull()
            rejected.expiredCalls shouldBe 1
            val (down, _, downAuth) = fixture(HttpStatusCode.ServiceUnavailable)
            downAuth.authenticate(null, unauthorized(cover)).shouldBeNull()
            down.expiredCalls shouldBe 0
        }
    })
