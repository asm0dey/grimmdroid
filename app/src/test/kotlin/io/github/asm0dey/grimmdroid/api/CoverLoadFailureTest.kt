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

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.network.NetworkFetcher
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import io.github.asm0dey.grimmdroid.account.AccountEntity
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoverLoadFailureTest {
    @Test
    fun persistentUnauthorizedIsAnErrorResultNotACrash() {
        val base = "https://grim.example"
        val account = AccountEntity(1, base, "u", "1", canDownload = true, isAdmin = false, encryptedTokens = null)
        var expired = 0
        val tokens =
            object : TokenSource {
                override suspend fun current() = Tokens("old", "r-old")

                override suspend fun save(t: Tokens) = Unit

                override suspend fun expired() {
                    expired++
                }
            }
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader =
            ImageLoader
                .Builder(context)
                .components {
                    add(
                        NetworkFetcher.Factory(
                            { CoverNetworkClient({ account }, { authedClient(base, tokens, engine) }, httpClient(engine)) },
                        ),
                    )
                }.diskCache(null)
                .build()
        val result = runBlocking { loader.execute(ImageRequest.Builder(context).data("$base/api/v1/media/book/1/thumbnail").build()) }
        assertTrue(result is ErrorResult)
        assertTrue((result as ErrorResult).throwable is SessionExpiredException)
        assertEquals(1, expired)
    }
}
