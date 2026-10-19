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
import io.github.asm0dey.grimmdroid.api.LoginRequest
import io.github.asm0dey.grimmdroid.api.Tokens
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

sealed interface LoginResult {
    data class Ok(
        val tokens: Tokens,
        val user: AppUserInfo,
    ) : LoginResult

    data object BadCredentials : LoginResult

    data class Unsupported(
        val serverVersion: String,
    ) : LoginResult

    data class Failed(
        val cause: Throwable,
    ) : LoginResult
}

class PasswordLogin(
    private val client: HttpClient,
) {
    suspend fun login(
        baseUrl: String,
        serverVersion: String,
        username: String,
        password: String,
    ): LoginResult =
        try {
            val login =
                client.post("$baseUrl/api/v1/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody(LoginRequest(username, password))
                }
            when {
                login.status == HttpStatusCode.Unauthorized || login.status == HttpStatusCode.Forbidden -> {
                    LoginResult.BadCredentials
                }

                !login.status.isSuccess() -> {
                    LoginResult.Failed(httpError(login))
                }

                else -> {
                    val tokens = login.body<Tokens>()
                    val me = client.get("$baseUrl/api/v1/app/users/me") { bearerAuth(tokens.accessToken) }
                    when {
                        me.status == HttpStatusCode.NotFound -> LoginResult.Unsupported(serverVersion)
                        !me.status.isSuccess() -> LoginResult.Failed(httpError(me))
                        else -> LoginResult.Ok(tokens, me.body())
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoginResult.Failed(e)
        }

    private fun httpError(response: HttpResponse) = IllegalStateException("HTTP ${response.status} from ${response.call.request.url}")
}
