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
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import java.io.IOException
import javax.net.ssl.SSLException

sealed interface ProbeResult {
    data class Ok(
        val baseUrl: String,
        val version: String,
        val settings: PublicSettings,
        val cleartext: Boolean,
    ) : ProbeResult

    data object Unreachable : ProbeResult

    data object NotGrimmory : ProbeResult

    data object InvalidAddress : ProbeResult

    data class TlsUntrusted(
        val host: String,
    ) : ProbeResult
}

fun normalizeBaseUrl(input: String): String {
    val trimmed = input.trim()
    val withScheme =
        if ("://" in trimmed) {
            trimmed.substringBefore("://").lowercase() + "://" + trimmed.substringAfter("://")
        } else {
            "https://$trimmed"
        }
    return withScheme.trimEnd('/')
}

class ServerProbe(
    private val client: HttpClient,
) {
    suspend fun probe(input: String): ProbeResult {
        val baseUrl = normalizeBaseUrl(input)
        if ("://" !in baseUrl) return ProbeResult.InvalidAddress // "", "https://" trim down to "https:"
        val host =
            try {
                Url(baseUrl).takeIf { it.protocol.name in setOf("http", "https") }?.host?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                // Ktor throws URLParserException or IAE depending on the input
                null
            } ?: return ProbeResult.InvalidAddress
        return try {
            val settings = get<PublicSettings>("$baseUrl/api/v1/public-settings")
            val version = settings?.let { get<VersionInfo>("$baseUrl/api/v1/version") }
            if (settings == null || version == null) {
                ProbeResult.NotGrimmory
            } else {
                ProbeResult.Ok(baseUrl, version.current, settings, cleartext = baseUrl.startsWith("http://"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SSLException) {
            ProbeResult.TlsUntrusted(host)
        } catch (e: IOException) {
            ProbeResult.Unreachable
        }
    }

    // Null when the endpoint is not a JSON API that matches our DTO (proxy login page, other app).
    private suspend inline fun <reified T> get(url: String): T? {
        val response: HttpResponse = client.get(url)
        if (!response.status.isSuccess() || response.contentType()?.match(ContentType.Application.Json) != true) {
            return null
        }
        return try {
            response.body<T>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is IOException) throw e else null
        }
    }
}
