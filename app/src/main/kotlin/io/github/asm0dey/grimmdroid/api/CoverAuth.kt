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

import io.github.asm0dey.grimmdroid.account.AccountRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request

/** The current account, as cover requests see it. */
class CoverSession(
    val baseUrl: String,
    val tokens: TokenSource,
)

fun AccountRepository.coverSession(): suspend () -> CoverSession? =
    { current.first()?.let { CoverSession(it.baseUrl, tokenSource(it.id)) } }

private fun HttpUrl.isOn(baseUrl: String): Boolean {
    val base = baseUrl.toHttpUrlOrNull() ?: return false
    return scheme == base.scheme && host == base.host && port == base.port
}

/** Adds the bearer only when [request] goes to [baseUrl]'s own scheme, host and port; [token] is read only then. */
suspend fun authorizeCover(
    request: Request,
    baseUrl: String?,
    token: suspend () -> String?,
): Request {
    if (baseUrl == null || !request.url.isOn(baseUrl)) return request
    val access = token() ?: return request
    return request.newBuilder().header("Authorization", "Bearer $access").build()
}

/** Cover requests carry the current account's token. */
fun coverAuthInterceptor(session: suspend () -> CoverSession?) =
    Interceptor { chain ->
        // OkHttp runs interceptors on its own worker threads, so blocking on the token store is fine here.
        val request =
            runBlocking {
                val s = session()
                authorizeCover(chain.request(), s?.baseUrl) { s?.tokens?.current()?.accessToken }
            }
        chain.proceed(request)
    }

/**
 * On a 401 from the account's own server, refreshes once through [refreshSession] (shared with the API clients) and
 * retries. A second 401, a foreign host or a failed refresh gives up, so the cover falls back to its placeholder.
 * [refreshClient] must carry no tokens.
 */
fun coverAuthenticator(
    session: suspend () -> CoverSession?,
    refreshClient: HttpClient,
) = Authenticator { _, response ->
    if (response.priorResponse != null) return@Authenticator null
    val request = response.request
    runBlocking {
        val s = session()?.takeIf { request.url.isOn(it.baseUrl) } ?: return@runBlocking null
        val stale = request.header("Authorization")?.removePrefix("Bearer ")
        val fresh =
            try {
                refreshSession(s.baseUrl, s.tokens, stale) { body ->
                    refreshClient.post("${s.baseUrl.trimEnd('/')}/api/v1/auth/refresh") {
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@runBlocking null // transient: keep the session, show the placeholder
            }
        if (fresh == null) {
            s.tokens.expired()
            null
        } else {
            request.newBuilder().header("Authorization", "Bearer ${fresh.accessToken}").build()
        }
    }
}
