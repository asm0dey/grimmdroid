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
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.nio.file.Files

private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

private object FixedTokens : TokenSource {
    override suspend fun current() = Tokens("a", "r")

    override suspend fun save(t: Tokens) = Unit

    override suspend fun expired() = Unit
}

/** Answers only [method] [path] with [respond]; any other request fails the test. */
private fun api(
    method: HttpMethod,
    path: String,
    respond: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(
        io.ktor.client.request.HttpRequestData,
    ) -> io.ktor.client.request.HttpResponseData,
) = GrimmoryApi(
    authedClient(
        "https://grim.example",
        FixedTokens,
        MockEngine { if (it.method == method && it.url.encodedPath == path) respond(it) else error("unexpected ${it.method} ${it.url}") },
    ),
)

class ReaderApiSpec :
    FunSpec({
        test("progress reads epub and koreader progress, ignoring extras") {
            val p =
                api(HttpMethod.Get, "/api/v1/app/books/7/progress") {
                    respond(
                        """{"epubProgress":{"cfi":"epubcfi(/6/4)","href":"c1.xhtml","percentage":12.5,"updatedAt":"t"},""" +
                            """"koreaderProgress":{"percentage":3.0,"device":"d","deviceId":"i","lastSyncTime":"s"},"readStatus":"READING"}""",
                        HttpStatusCode.OK,
                        json,
                    )
                }.progress(7)
            p.epubProgress?.cfi shouldBe "epubcfi(/6/4)"
            p.epubProgress?.percentage shouldBe 12.5f
            p.koreaderProgress?.lastSyncTime shouldBe "s"
        }

        test("putProgress PUTs the exact JSON body") {
            var body = ""
            api(HttpMethod.Put, "/api/v1/app/books/7/progress") {
                body = String(it.body.toByteArray())
                respond("", HttpStatusCode.NoContent)
            }.putProgress(7, UpdateProgressDto(FileProgressDto(42, "epubcfi(/6/4!/4/2)", "c2.xhtml", 61.5f)))
            body shouldBe
                """{"fileProgress":{"bookFileId":42,"positionData":"epubcfi(/6/4!/4/2)","positionHref":"c2.xhtml","progressPercent":61.5}}"""
        }

        test("bookmarks lists a book's bookmarks") {
            val list =
                api(HttpMethod.Get, "/api/v1/bookmarks/book/7") {
                    respond(
                        """[{"id":1,"userId":2,"bookId":7,"cfi":"c","title":"T","color":"red","createdAt":"x"}]""",
                        HttpStatusCode.OK,
                        json,
                    )
                }.bookmarks(7)
            list.single().title shouldBe "T"
        }

        test("createBookmark POSTs and returns the created bookmark") {
            var body = ""
            val b =
                api(HttpMethod.Post, "/api/v1/bookmarks") {
                    body = String(it.body.toByteArray())
                    respond("""{"id":9,"bookId":7,"cfi":"c","title":"T"}""", HttpStatusCode.OK, json)
                }.createBookmark(CreateBookmarkDto(7, "c", "T"))
            body shouldBe """{"bookId":7,"cfi":"c","title":"T"}"""
            b.id shouldBe 9
        }

        test("deleteBookmark DELETEs by id") {
            api(HttpMethod.Delete, "/api/v1/bookmarks/9") { respond("", HttpStatusCode.NoContent) }.deleteBookmark(9)
        }

        test("customFonts lists fonts") {
            val fonts =
                api(HttpMethod.Get, "/api/v1/custom-fonts") {
                    respond(
                        """[{"id":3,"fontName":"Lora","originalFileName":"l.ttf","format":"TTF","fileSize":10,"uploadedAt":"x"}]""",
                        HttpStatusCode.OK,
                        json,
                    )
                }.customFonts()
            fonts!!.single().format shouldBe "TTF"
        }

        test("customFonts maps 403 to null") {
            api(HttpMethod.Get, "/api/v1/custom-fonts") { respondError(HttpStatusCode.Forbidden) }.customFonts().shouldBeNull()
        }

        test("downloadFont streams to the target and leaves no temp file") {
            val dir = Files.createTempDirectory("fonts").toFile()
            val target = dir.resolve("3.ttf")
            api(HttpMethod.Get, "/api/v1/custom-fonts/3/file") { respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK) }.downloadFont(3, target)
            target.readBytes().toList() shouldBe listOf<Byte>(1, 2, 3)
            dir.list()!!.toList() shouldBe listOf("3.ttf")
            dir.deleteRecursively()
        }
    })
