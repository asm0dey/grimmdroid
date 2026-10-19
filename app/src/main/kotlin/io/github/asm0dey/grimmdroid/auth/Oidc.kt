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

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.asm0dey.grimmdroid.api.GrimmoryJson
import io.github.asm0dey.grimmdroid.api.OidcProviderDetails
import io.github.asm0dey.grimmdroid.api.tokens
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

const val OIDC_REDIRECT_URI = "grimmory://oauth2-callback"
private const val DEFAULT_SCOPES = "openid profile email offline_access"

data class Pkce(
    val verifier: String,
    val challenge: String,
) {
    override fun toString() = "Pkce(verifier=***, challenge=$challenge)"
}

private val base64Url = Base64.getUrlEncoder().withoutPadding()

fun pkceFrom(verifier: String): Pkce =
    Pkce(verifier, base64Url.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))))

private fun randomToken(random: SecureRandom): String = base64Url.encodeToString(ByteArray(32).also(random::nextBytes))

// 32 random bytes → 43 base64url chars, the RFC 7636 minimum length.
fun newPkce(random: SecureRandom = SecureRandom()): Pkce = pkceFrom(randomToken(random))

// Survives process death while the Custom Tab is in front; see PendingOidcStore.
@Serializable
data class PendingOidc(
    val baseUrl: String,
    val serverVersion: String,
    val state: String,
    val nonce: String,
    val verifier: String,
) {
    override fun toString() = "PendingOidc(baseUrl=$baseUrl, serverVersion=$serverVersion, state=***, nonce=***, verifier=***)"
}

// The server refused OIDC_REDIRECT_URI: an admin must add it to the allowed OIDC redirect URIs.
data class RedirectRejected(
    val redirectUri: String,
) : Exception("Server does not allow redirect URI $redirectUri")

class OidcProviderError(
    message: String,
) : Exception(message)

@Serializable
private data class OidcState(
    val state: String,
)

@Serializable
private data class OidcDiscovery(
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
)

// URLs are Ktor Urls; Android callers convert at the edge (Uri.parse(url.toString()) / Url(uri.toString())).
class OidcLogin(
    // Must be the plain client: discovery goes to the IdP and must not carry Grimmory tokens.
    private val client: HttpClient,
    private val random: SecureRandom = SecureRandom(),
) {
    // Throws on network/HTTP failure; the caller shows the error and keeps the sign-in screen.
    suspend fun start(
        baseUrl: String,
        version: String,
        details: OidcProviderDetails,
    ): Pair<Url, PendingOidc> {
        val state =
            client
                .get("$baseUrl/api/v1/auth/oidc/state")
                .ok()
                .body<OidcState>()
                .state
        val discovery = client.get("${details.issuerUri.trimEnd('/')}/.well-known/openid-configuration").ok().body<OidcDiscovery>()
        val pkce = newPkce(random)
        val pending = PendingOidc(baseUrl, version, state, randomToken(random), pkce.verifier)
        val url =
            URLBuilder(discovery.authorizationEndpoint)
                .apply {
                    parameters.append("response_type", "code")
                    parameters.append("client_id", details.clientId)
                    parameters.append("redirect_uri", OIDC_REDIRECT_URI)
                    parameters.append("scope", details.scopes?.trim()?.ifEmpty { null } ?: DEFAULT_SCOPES)
                    parameters.append("state", state)
                    parameters.append("nonce", pending.nonce)
                    parameters.append("code_challenge", pkce.challenge)
                    parameters.append("code_challenge_method", "S256")
                }.build()
        return url to pending
    }

    suspend fun complete(
        redirect: Url,
        pending: PendingOidc,
    ): LoginResult {
        val params = redirect.parameters
        if (params["state"] != pending.state) return LoginResult.Failed(IllegalStateException("OIDC state mismatch"))
        params["error"]?.let { return LoginResult.Failed(OidcProviderError(params["error_description"] ?: it)) }
        val code = params["code"] ?: return LoginResult.Failed(IllegalStateException("OIDC redirect has no code"))
        return try {
            val response = callback(pending, code)
            when {
                (response.status == HttpStatusCode.BadRequest || response.status == HttpStatusCode.Unauthorized) &&
                    response.bodyAsText().contains("redirect", ignoreCase = true) -> {
                    LoginResult.Failed(RedirectRejected(OIDC_REDIRECT_URI))
                }

                !response.status.isSuccess() -> {
                    LoginResult.Failed(httpError(response))
                }

                else -> {
                    client.checkAppApi(pending.baseUrl, pending.serverVersion, response.tokens())
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoginResult.Failed(e)
        }
    }

    private suspend fun callback(
        pending: PendingOidc,
        code: String,
    ): HttpResponse =
        try {
            client.post("${pending.baseUrl}/api/v1/auth/oidc/mobile/callback") {
                parameter("code", code)
                parameter("code_verifier", pending.verifier)
                parameter("redirect_uri", OIDC_REDIRECT_URI)
                parameter("nonce", pending.nonce)
                parameter("state", pending.state)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No cause: transport messages (e.g. request timeout) quote the URL, whose query holds the code and verifier.
            throw IOException("OIDC callback request failed: ${e::class.simpleName}")
        }

    private fun HttpResponse.ok() = also { if (!status.isSuccess()) throw httpError(this) }
}

// The redirect Intent reaches MainActivity; the sign-in UI (Task 11) collects it and calls OidcLogin.complete.
object OidcRedirects {
    val incoming = MutableSharedFlow<Url>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}

// Backed by a DataStore in the default files/datastore/ dir, which backup rules exclude.
class PendingOidcStore(
    private val store: DataStore<Preferences>,
) {
    suspend fun save(pending: PendingOidc) {
        store.edit { it[KEY] = GrimmoryJson.encodeToString(PendingOidc.serializer(), pending) }
    }

    suspend fun load(): PendingOidc? = store.data.first()[KEY]?.let { GrimmoryJson.decodeFromString(PendingOidc.serializer(), it) }

    suspend fun clear() {
        store.edit { it.remove(KEY) }
    }

    private companion object {
        val KEY = stringPreferencesKey("pending_oidc")
    }
}
