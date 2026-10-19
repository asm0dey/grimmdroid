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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request

/** Adds the bearer only when [request] goes to [baseUrl]'s own scheme, host and port; [token] is read only then. */
suspend fun authorizeCover(
    request: Request,
    baseUrl: String?,
    token: suspend () -> String?,
): Request {
    val base = baseUrl?.toHttpUrlOrNull() ?: return request
    val url = request.url
    if (url.scheme != base.scheme || url.host != base.host || url.port != base.port) return request
    val access = token() ?: return request
    return request.newBuilder().header("Authorization", "Bearer $access").build()
}

/**
 * Cover requests carry the current account's token. A 401 is not retried: the cover falls back to its placeholder and
 * the next sync renews the tokens.
 */
fun coverAuthInterceptor(accounts: AccountRepository) =
    Interceptor { chain ->
        // OkHttp runs interceptors on its own worker threads, so blocking on the token store is fine here.
        val request =
            runBlocking {
                val account = accounts.current.first()
                authorizeCover(chain.request(), account?.baseUrl) { account?.let { accounts.tokenSource(it.id).current()?.accessToken } }
            }
        chain.proceed(request)
    }
