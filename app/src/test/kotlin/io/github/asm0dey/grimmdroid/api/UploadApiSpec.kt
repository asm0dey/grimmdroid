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
import io.kotest.matchers.string.shouldContain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File

private object UploadTokens : TokenSource {
    override suspend fun current() = Tokens("a", "r")

    override suspend fun save(t: Tokens) = Unit

    override suspend fun expired() = Unit
}

/** Answers only [method] [path] with [respond]; any other request fails the test. */
private fun api(
    method: HttpMethod,
    path: String,
    respond: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) = GrimmoryApi(
    authedClient(
        "https://grim.example",
        UploadTokens,
        MockEngine { if (it.method == method && it.url.encodedPath == path) respond(it) else error("unexpected ${it.method} ${it.url}") },
    ),
)

class UploadApiSpec :
    FunSpec({
        val epub = File.createTempFile("upload", ".epub").apply { writeText("EPUBDATA") }
        afterSpec { epub.delete() }

        test("upload posts multipart file, libraryId and pathId") {
            var body = ""
            api(HttpMethod.Post, "/api/v1/files/upload") {
                body = String(it.body.toByteArray())
                respond("", HttpStatusCode.NoContent)
            }.upload(epub, "b.epub", 3, 9) {}
            body shouldContain "name=\"libraryId\""
            body shouldContain "name=\"pathId\""
            body shouldContain "filename=\"b.epub\""
            body shouldContain "EPUBDATA"
            Regex("name=\"libraryId\".*?\\r\\n\\r\\n3\\r\\n", RegexOption.DOT_MATCHES_ALL).containsMatchIn(body) shouldBe true
            Regex("name=\"pathId\".*?\\r\\n\\r\\n9\\r\\n", RegexOption.DOT_MATCHES_ALL).containsMatchIn(body) shouldBe true
        }

        test("upload maps 403 to NoUploadPermission") {
            shouldThrow<NoUploadPermission> {
                api(HttpMethod.Post, "/api/v1/files/upload") { respond("", HttpStatusCode.Forbidden) }.upload(epub, "b.epub", 3, 9) {}
            }
        }

        test("refreshLibrary ignores 403") {
            api(HttpMethod.Put, "/api/v1/libraries/3/refresh") { respond("", HttpStatusCode.Forbidden) }.refreshLibrary(3)
        }

        test("uploadLibraries reads watch and paths") {
            val libs =
                api(HttpMethod.Get, "/api/v1/libraries") {
                    respond(
                        """[{"id":3,"name":"Books","watch":true,"paths":[{"id":9,"path":"/b"}],"sort":{"field":"x"}}]""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }.uploadLibraries()
            libs.single().watch shouldBe true
            libs.single().paths.single().id shouldBe 9
        }
    })
