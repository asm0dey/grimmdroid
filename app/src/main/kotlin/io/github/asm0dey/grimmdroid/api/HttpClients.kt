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

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.AuthCircuitBreaker
import io.ktor.client.plugins.auth.providers.BearerTokens
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

val GrimmoryJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

fun httpClient(
    engine: HttpClientEngine = OkHttp.create(),
    json: Json = GrimmoryJson,
): HttpClient = HttpClient(engine) { install(ContentNegotiation) { json(json) } }

interface TokenSource {
    suspend fun current(): Tokens?

    suspend fun save(t: Tokens)

    suspend fun expired()
}

class SessionExpiredException : Exception("Session expired")

// Carries no cause: decoder messages quote the response body, which holds the tokens.
class MalformedTokensException : IOException("Malformed token response")

class RefreshFailedException(
    status: HttpStatusCode,
) : IOException("Token refresh failed: HTTP $status")

suspend fun HttpResponse.tokens(): Tokens =
    try {
        body()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw MalformedTokensException()
    }

// Process-wide, so separate clients for one server (UI, download worker) never spend the same rotating refresh token.
private val refreshLocks = ConcurrentHashMap<String, Mutex>()

fun authedClient(
    baseUrl: String,
    tokens: TokenSource,
    engine: HttpClientEngine = OkHttp.create(),
): HttpClient {
    val base = Url(baseUrl)
    val refreshLock = refreshLocks.getOrPut(baseUrl) { Mutex() }

    fun isBase(url: Url) = url.host == base.host && url.port == base.port

    // Installed before Auth so it wraps it: sees the response only after Auth's refresh-and-retry.
    val sessionExpiry =
        createClientPlugin("SessionExpiry") {
            on(Send) { request ->
                val call = proceed(request)
                if (call.response.status == HttpStatusCode.Unauthorized &&
                    !request.attributes.contains(AuthCircuitBreaker) &&
                    isBase(call.request.url)
                ) {
                    tokens.expired()
                    throw SessionExpiredException()
                }
                call
            }
        }

    return HttpClient(engine) {
        install(ContentNegotiation) { json(GrimmoryJson) }
        defaultRequest { url("$baseUrl/") } // trailing slash keeps a sub-path base for relative requests
        expectSuccess = true
        install(sessionExpiry)
        install(Auth) {
            reAuthorizeOnResponse { it.status == HttpStatusCode.Unauthorized && isBase(it.call.request.url) }
            bearer {
                sendWithoutRequest { isBase(it.url.build()) }
                loadTokens { tokens.current()?.let { BearerTokens(it.accessToken, it.refreshToken) } }
                // null = signed out (sessionExpiry then calls expired()); transient failures throw and keep the session.
                refreshTokens {
                    refreshLock.withLock {
                        val current = tokens.current() ?: return@withLock null
                        if (current.accessToken != oldTokens?.accessToken) {
                            // Another client already refreshed; the old refresh token is revoked server-side.
                            return@withLock BearerTokens(current.accessToken, current.refreshToken)
                        }
                        val response =
                            client.post("api/v1/auth/refresh") {
                                markAsRefreshTokenRequest()
                                expectSuccess = false
                                contentType(ContentType.Application.Json)
                                setBody(RefreshRequest(current.refreshToken))
                            }
                        when {
                            response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.BadRequest -> {
                                null
                            }

                            !response.status.isSuccess() -> {
                                throw RefreshFailedException(response.status)
                            }

                            else -> {
                                val fresh = response.tokens()
                                tokens.save(fresh)
                                BearerTokens(fresh.accessToken, fresh.refreshToken)
                            }
                        }
                    }
                }
            }
        }
    }
}
