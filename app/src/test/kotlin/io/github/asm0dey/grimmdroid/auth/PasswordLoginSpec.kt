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
package io.github.asm0dey.grimmdroid.auth

import io.github.asm0dey.grimmdroid.api.AppUserInfo
import io.github.asm0dey.grimmdroid.api.Tokens
import io.github.asm0dey.grimmdroid.api.httpClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf

private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
private const val TOKENS_JSON = """{"accessToken":"a1","refreshToken":"r1","expires":900}"""
private const val ME_JSON = """{"admin":false,"canUpload":false,"canDownload":true,"maxFileUploadSizeMb":100}"""

class PasswordLoginSpec :
    FunSpec({
        test("login 200 + me 200 is Ok and sends username/password json") {
            var loginBody = ""
            var meAuth: String? = null
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/api/v1/auth/login" -> {
                            loginBody = request.body.toByteArray().decodeToString()
                            respond(TOKENS_JSON, HttpStatusCode.OK, jsonHeaders)
                        }

                        "/api/v1/app/users/me" -> {
                            meAuth = request.headers[HttpHeaders.Authorization]
                            respond(ME_JSON, HttpStatusCode.OK, jsonHeaders)
                        }

                        else -> {
                            respondError(HttpStatusCode.NotFound)
                        }
                    }
                }
            val result = PasswordLogin(httpClient(engine)).login("https://grim.example", "v3.5.0", "pavel", "pw")
            result shouldBe LoginResult.Ok(Tokens("a1", "r1", 900), AppUserInfo(canDownload = true))
            loginBody shouldBe """{"username":"pavel","password":"pw"}"""
            meAuth shouldBe "Bearer a1"
        }

        test("login 401 is BadCredentials") {
            val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
            PasswordLogin(httpClient(engine)).login("https://grim.example", "v3.5.0", "pavel", "bad") shouldBe
                LoginResult.BadCredentials
        }

        test("404 on app api classifies as Unsupported") {
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath == "/api/v1/auth/login") {
                        respond(TOKENS_JSON, HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respondError(HttpStatusCode.NotFound)
                    }
                }
            PasswordLogin(httpClient(engine)).login("https://grim.example", "v3.0.0", "pavel", "pw") shouldBe
                LoginResult.Unsupported("v3.0.0")
        }

        test("malformed token json does not leak the token into Failed") {
            val engine = MockEngine { respond("""{"accessToken":"acc-SECRET-1","refreshToken":""", HttpStatusCode.OK, jsonHeaders) }
            val result = PasswordLogin(httpClient(engine)).login("https://grim.example", "v3.5.0", "pavel", "pw")
            val failed = result.shouldBeInstanceOf<LoginResult.Failed>()
            generateSequence(failed.cause) { it.cause }.forEach { it.toString() shouldNotContain "SECRET" }
            result.toString() shouldNotContain "SECRET"
        }

        test("Ok does not print tokens") {
            LoginResult.Ok(Tokens("acc-SECRET-1", "ref-SECRET-2"), AppUserInfo()).toString() shouldNotContain "SECRET"
        }
    })
