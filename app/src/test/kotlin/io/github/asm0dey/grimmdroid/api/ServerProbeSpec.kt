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
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

private val jsonHeaders = headersOf("Content-Type", ContentType.Application.Json.toString())
private val settingsJson = """{"oidcEnabled":true,"remoteAuthEnabled":false,"extra":1}"""

// Verbatim shape from Grimmory v3.5.0, which keeps /api/v1/version behind auth.
private val healthJson =
    """{"data":{"status":"UP","message":"Application is running smoothly.","timestamp":"2026-10-19T16:08:40.282572465",""" +
        """"version":"v3.5.0"},"message":"Pong","status":200,"timestamp":"2026-10-19T16:08:40.282592233"}"""

private fun probe(
    input: String,
    handler: suspend (String) -> Nothing? = { null },
    respondTo: io.ktor.client.engine.mock.MockRequestHandleScope.(String) -> io.ktor.client.request.HttpResponseData,
) = MockEngine { request ->
    handler(request.url.encodedPath)
    respondTo(request.url.encodedPath)
}.let { engine -> kotlinx.coroutines.runBlocking { ServerProbe(httpClient(engine)).probe(input) } }

private fun io.ktor.client.engine.mock.MockRequestHandleScope.ok(path: String) =
    when (path) {
        "/api/v1/public-settings" -> respond(settingsJson, HttpStatusCode.OK, jsonHeaders)
        "/api/v1/healthcheck" -> respond(healthJson, HttpStatusCode.OK, jsonHeaders)
        "/api/v1/version" -> respondError(HttpStatusCode.Unauthorized)
        else -> respondError(HttpStatusCode.NotFound)
    }

class ServerProbeSpec :
    FunSpec({
        test("normalizeBaseUrl adds https and strips trailing slash") {
            normalizeBaseUrl(" books.example.net/ ") shouldBe "https://books.example.net"
            normalizeBaseUrl("http://10.0.0.2:6060") shouldBe "http://10.0.0.2:6060"
        }

        test("normalizeBaseUrl lower-cases the scheme and probe flags cleartext") {
            normalizeBaseUrl("HTTP://10.0.0.2:6060/") shouldBe "http://10.0.0.2:6060"
            (probe("HTTP://10.0.0.2:6060/") { ok(it) } as ProbeResult.Ok).cleartext shouldBe true
        }

        test("unparseable input is InvalidAddress") {
            for (bad in listOf("", "   ", "ht!tp://??")) {
                probe(bad) { ok(it) } shouldBe ProbeResult.InvalidAddress
            }
        }

        test("version is not requested when public-settings is not Grimmory") {
            val paths = mutableListOf<String>()
            probe("books.example.net", {
                paths += it
                null
            }) { respondError(HttpStatusCode.NotFound) }
            paths shouldBe listOf("/api/v1/public-settings")
        }

        test("grimmory server is Ok over https") {
            val result = probe("books.example.net/") { ok(it) }
            result shouldBe
                ProbeResult.Ok(
                    "https://books.example.net",
                    "v3.5.0",
                    PublicSettings(oidcEnabled = true),
                    cleartext = false,
                )
        }

        test("http url is flagged cleartext") {
            (probe("http://10.0.0.2:6060") { ok(it) } as ProbeResult.Ok).cleartext shouldBe true
        }

        test("html response classifies as NotGrimmory") {
            probe("books.example.net") {
                respond("<html>login</html>", HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
            } shouldBe ProbeResult.NotGrimmory
        }

        test("json that is not PublicSettings classifies as NotGrimmory") {
            probe("books.example.net") {
                respond("""{"oidcEnabled":"maybe"}""", HttpStatusCode.OK, jsonHeaders)
            } shouldBe ProbeResult.NotGrimmory
        }

        test("404 on public-settings is NotGrimmory") {
            probe("books.example.net") { respondError(HttpStatusCode.NotFound) } shouldBe ProbeResult.NotGrimmory
        }

        test("public-settings OK but healthcheck not Grimmory's is NotGrimmory") {
            for (health in listOf(null, """{"status":"UP"}""")) {
                probe("books.example.net") { path ->
                    when {
                        path == "/api/v1/public-settings" -> respond(settingsJson, HttpStatusCode.OK, jsonHeaders)
                        health != null && path == "/api/v1/healthcheck" -> respond(health, HttpStatusCode.OK, jsonHeaders)
                        else -> respondError(HttpStatusCode.NotFound)
                    }
                } shouldBe ProbeResult.NotGrimmory
            }
        }

        test("IOException and UnknownHostException are Unreachable") {
            probe("books.example.net", { throw IOException("boom") }) { ok(it) } shouldBe ProbeResult.Unreachable
            probe("books.example.net", { throw UnknownHostException("x") }) { ok(it) } shouldBe ProbeResult.Unreachable
        }

        test("SSLHandshakeException is TlsUntrusted with host only") {
            probe("https://books.example.net:8443", { throw SSLHandshakeException("bad cert") }) { ok(it) } shouldBe
                ProbeResult.TlsUntrusted("books.example.net")
        }
    })
