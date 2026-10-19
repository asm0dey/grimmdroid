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

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.asm0dey.grimmdroid.api.AppUserInfo
import io.github.asm0dey.grimmdroid.api.OidcProviderDetails
import io.github.asm0dey.grimmdroid.api.Tokens
import io.github.asm0dey.grimmdroid.api.httpClient
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.security.SecureRandom

private val jsonHeaders = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
private const val TOKENS_JSON = """{"accessToken":"a1","refreshToken":"r1","expires":900}"""
private const val ME_JSON = """{"admin":false,"canDownload":true}"""
private const val BASE = "https://grim.example"
private val pending = PendingOidc(BASE, "v3.5.0", "st-1", "n-1", "ver-1")

class OidcSpec :
    FunSpec({
        test("RFC 7636 appendix B challenge") {
            pkceFrom("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk").challenge shouldBe
                "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        }

        test("newPkce verifier is 43..128 unreserved chars") {
            val random = SecureRandom()
            repeat(500) {
                val pkce = newPkce(random)
                pkce.verifier.length shouldBeInRange 43..128
                pkce.verifier shouldMatch Regex("[A-Za-z0-9._~-]+")
                pkce.challenge shouldBe pkceFrom(pkce.verifier).challenge
            }
        }

        listOf("https://idp.example/realms/x", "https://idp.example/realms/x/").forEach { issuer ->
            test("start builds the authorize url (issuer $issuer)") {
                val idpAuth = mutableListOf<String>()
                val engine =
                    MockEngine { request ->
                        when (request.url.toString()) {
                            "$BASE/api/v1/auth/oidc/state" -> {
                                respond("""{"state":"st-1"}""", HttpStatusCode.OK, jsonHeaders)
                            }

                            "https://idp.example/realms/x/.well-known/openid-configuration" -> {
                                request.headers[HttpHeaders.Authorization]?.let { idpAuth += it }
                                respond(
                                    """{"issuer":"x","authorization_endpoint":"https://idp.example/auth?kc=1"}""",
                                    HttpStatusCode.OK,
                                    jsonHeaders,
                                )
                            }

                            else -> {
                                respondError(HttpStatusCode.NotFound)
                            }
                        }
                    }
                val (url, p) =
                    OidcLogin(
                        httpClient(engine),
                    ).start(BASE, "v3.5.0", OidcProviderDetails(clientId = "grim", issuerUri = issuer))
                val s = url.toString()
                s shouldMatch Regex("https://idp\\.example/auth\\?kc=1&.*")
                s.contains("redirect_uri=grimmory%3A%2F%2Foauth2-callback") shouldBe true
                url.parameters["response_type"] shouldBe "code"
                url.parameters["client_id"] shouldBe "grim"
                url.parameters["redirect_uri"] shouldBe "grimmory://oauth2-callback"
                url.parameters["scope"] shouldBe "openid profile email offline_access"
                url.parameters["state"] shouldBe "st-1"
                url.parameters["nonce"] shouldBe p.nonce
                url.parameters["code_challenge"] shouldBe pkceFrom(p.verifier).challenge
                url.parameters["code_challenge_method"] shouldBe "S256"
                p.baseUrl shouldBe BASE
                p.serverVersion shouldBe "v3.5.0"
                p.state shouldBe "st-1"
                idpAuth.shouldBeEmpty()
            }
        }

        test("start uses the configured scopes") {
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath.endsWith("/state")) {
                        respond("""{"state":"st-1"}""", HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respond("""{"authorization_endpoint":"https://idp.example/auth"}""", HttpStatusCode.OK, jsonHeaders)
                    }
                }
            val details = OidcProviderDetails(clientId = "grim", issuerUri = "https://idp.example", scopes = " openid groups ")
            OidcLogin(httpClient(engine)).start(BASE, "v3.5.0", details).first.parameters["scope"] shouldBe "openid groups"
        }

        test("complete with mismatched state fails without any http call") {
            var calls = 0
            val engine =
                MockEngine {
                    calls++
                    respondError(HttpStatusCode.InternalServerError)
                }
            val result = OidcLogin(httpClient(engine)).complete(Url("grimmory://oauth2-callback?code=c&state=other"), pending)
            result.shouldBeInstanceOf<LoginResult.Failed>()
            calls shouldBe 0
        }

        test("complete with an IdP error reports its description") {
            val engine = MockEngine { respondError(HttpStatusCode.InternalServerError) }
            val result =
                OidcLogin(httpClient(engine)).complete(
                    Url("grimmory://oauth2-callback?error=access_denied&error_description=User%20cancelled&state=st-1"),
                    pending,
                )
            result.shouldBeInstanceOf<LoginResult.Failed>().cause.message shouldBe "User cancelled"
        }

        test("complete happy path posts the five query params and checks me") {
            var callback: Url? = null
            var method: HttpMethod? = null
            var meAuth: String? = null
            val engine =
                MockEngine { request ->
                    when (request.url.encodedPath) {
                        "/api/v1/auth/oidc/mobile/callback" -> {
                            callback = request.url
                            method = request.method
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
            val result = OidcLogin(httpClient(engine)).complete(Url("grimmory://oauth2-callback?code=c-1&state=st-1"), pending)
            result shouldBe LoginResult.Ok(Tokens("a1", "r1", 900), AppUserInfo(canDownload = true))
            method shouldBe HttpMethod.Post
            callback!!.parameters.names() shouldBe setOf("code", "code_verifier", "redirect_uri", "nonce", "state")
            callback!!.parameters["code"] shouldBe "c-1"
            callback!!.parameters["code_verifier"] shouldBe "ver-1"
            callback!!.parameters["redirect_uri"] shouldBe "grimmory://oauth2-callback"
            callback!!.parameters["nonce"] shouldBe "n-1"
            callback!!.parameters["state"] shouldBe "st-1"
            meAuth shouldBe "Bearer a1"
        }

        test("complete on a pre-app-api server is Unsupported") {
            val engine =
                MockEngine { request ->
                    if (request.url.encodedPath == "/api/v1/auth/oidc/mobile/callback") {
                        respond(TOKENS_JSON, HttpStatusCode.OK, jsonHeaders)
                    } else {
                        respondError(HttpStatusCode.NotFound)
                    }
                }
            OidcLogin(httpClient(engine)).complete(Url("grimmory://oauth2-callback?code=c&state=st-1"), pending) shouldBe
                LoginResult.Unsupported("v3.5.0")
        }

        test("server rejecting the redirect uri is RedirectRejected") {
            // Body shape of Grimmory's ErrorResponse for ApiError.OIDC_INVALID_REDIRECT_URI.
            val engine =
                MockEngine {
                    respond(
                        """{"status":400,"message":"Invalid redirect URI","timestamp":"2026-10-19T10:00:00"}""",
                        HttpStatusCode.BadRequest,
                        jsonHeaders,
                    )
                }
            val result = OidcLogin(httpClient(engine)).complete(Url("grimmory://oauth2-callback?code=c&state=st-1"), pending)
            result.shouldBeInstanceOf<LoginResult.Failed>().cause shouldBe RedirectRejected("grimmory://oauth2-callback")
        }

        test("other 400 is a plain failure") {
            val engine =
                MockEngine {
                    respond(
                        """{"status":400,"message":"Invalid or expired OIDC state parameter"}""",
                        HttpStatusCode.BadRequest,
                        jsonHeaders,
                    )
                }
            val result = OidcLogin(httpClient(engine)).complete(Url("grimmory://oauth2-callback?code=c&state=st-1"), pending)
            (result.shouldBeInstanceOf<LoginResult.Failed>().cause is RedirectRejected) shouldBe false
        }

        test("callback failures do not leak the code or verifier") {
            val secretPending = pending.copy(verifier = "ver-SECRET")
            val redirect = Url("grimmory://oauth2-callback?code=code-SECRET&state=st-1")
            val throwing = MockEngine { request -> throw java.io.IOException("timeout [url=${request.url}]") }
            val erroring = MockEngine { respondError(HttpStatusCode.InternalServerError) }
            listOf(throwing, erroring).forEach { engine ->
                val failed = OidcLogin(httpClient(engine)).complete(redirect, secretPending).shouldBeInstanceOf<LoginResult.Failed>()
                generateSequence(failed.cause) { it.cause }.forEach { it.toString() shouldNotContain "SECRET" }
            }
        }

        test("PendingOidc does not print secrets") {
            PendingOidc(BASE, "v", "st-SECRET", "n-SECRET", "v-SECRET").toString() shouldNotContain "SECRET"
        }

        test("PendingOidcStore round-trips and clears") {
            val dir = Files.createTempDirectory("oidc").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val store = PendingOidcStore(PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("oidc.preferences_pb") })
                store.load() shouldBe null
                store.save(pending)
                store.load() shouldBe pending
                store.clear()
                store.load() shouldBe null
            } finally {
                scope.cancel()
                dir.deleteRecursively()
            }
        }
    })
