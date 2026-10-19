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
package io.github.asm0dey.grimmdroid.ui.onboarding

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.asm0dey.grimmdroid.AppContainer
import io.github.asm0dey.grimmdroid.api.AppUserInfo
import io.github.asm0dey.grimmdroid.api.GrimmoryJson
import io.github.asm0dey.grimmdroid.api.OidcProviderDetails
import io.github.asm0dey.grimmdroid.api.ProbeResult
import io.github.asm0dey.grimmdroid.api.Tokens
import io.github.asm0dey.grimmdroid.auth.LoginResult
import io.github.asm0dey.grimmdroid.auth.OidcRedirects
import io.github.asm0dey.grimmdroid.auth.PendingOidc
import io.github.asm0dey.grimmdroid.auth.PendingOidcStore
import io.github.asm0dey.grimmdroid.auth.RedirectRejected
import io.github.asm0dey.grimmdroid.sync.scheduleSync
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.Base64

sealed interface OnboardingState {
    data class EnterServer(
        val error: String? = null,
    ) : OnboardingState

    data object Probing : OnboardingState

    data class CleartextWarning(
        val probe: ProbeResult.Ok,
    ) : OnboardingState

    data class SignIn(
        val probe: ProbeResult.Ok,
        val passwordAllowed: Boolean,
        val ssoAllowed: Boolean,
        val error: String? = null,
    ) : OnboardingState

    data object SigningIn : OnboardingState

    data class Done(
        val accountId: Long,
        val defaultPassword: Boolean = false,
    ) : OnboardingState
}

class OnboardingViewModel(
    private val probe: suspend (String) -> ProbeResult,
    private val passwordLogin: suspend (baseUrl: String, version: String, username: String, password: String) -> LoginResult,
    private val oidcStart: suspend (baseUrl: String, version: String, OidcProviderDetails) -> Pair<Url, PendingOidc>,
    private val oidcComplete: suspend (Url, PendingOidc) -> LoginResult,
    private val pending: PendingOidcStore,
    private val redirects: MutableSharedFlow<Url>,
    private val saveAccount: suspend (baseUrl: String, username: String, version: String, AppUserInfo, Tokens) -> Long,
    private val scheduleSync: (accountId: Long) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow<OnboardingState>(OnboardingState.EnterServer())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    private val browser = Channel<Url>(Channel.BUFFERED)

    /** Authorize URLs to open in a Custom Tab. */
    val openBrowser: Flow<Url> = browser.receiveAsFlow()

    private var signIn: OnboardingState.SignIn? = null

    init {
        // Also finishes a sign-in whose Custom Tab outlived our process: the redirect is replayed and the PendingOidc persisted.
        viewModelScope.launch {
            redirects.collect {
                try {
                    onRedirect(it)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // e.g. an unreadable PendingOidc; the collector must outlive it to take the next redirect.
                    _state.value = signIn?.copy(error = GENERIC) ?: OnboardingState.EnterServer(GENERIC)
                }
            }
        }
    }

    fun submitServer(address: String) {
        _state.value = OnboardingState.Probing
        viewModelScope.launch {
            pending.clear() // a new server abandons any SSO sign-in still waiting for its redirect
            _state.value =
                when (val result = probe(address)) {
                    is ProbeResult.Ok -> {
                        val s = result.settings
                        val next = OnboardingState.SignIn(result, !s.oidcForceOnlyMode, s.oidcEnabled && s.oidcProviderDetails != null)
                        signIn = next
                        if (result.cleartext) OnboardingState.CleartextWarning(result) else next
                    }

                    ProbeResult.Unreachable -> {
                        OnboardingState.EnterServer(UNREACHABLE)
                    }

                    ProbeResult.NotGrimmory -> {
                        OnboardingState.EnterServer("This isn't a Grimmory server")
                    }

                    ProbeResult.InvalidAddress -> {
                        OnboardingState.EnterServer("That doesn't look like a server address")
                    }

                    is ProbeResult.TlsUntrusted -> {
                        OnboardingState.EnterServer(
                            "This server's certificate isn't trusted. Install it in Android Settings › Security › " +
                                "Encryption & credentials, then try again.",
                        )
                    }
                }
        }
    }

    fun acceptCleartext() {
        signIn?.let { _state.value = it }
    }

    fun back() {
        signIn = null
        _state.value = OnboardingState.EnterServer()
        viewModelScope.launch { pending.clear() }
    }

    fun signIn(
        username: String,
        password: String,
    ) {
        val form = signIn ?: return
        _state.value = OnboardingState.SigningIn
        viewModelScope.launch {
            finish(passwordLogin(form.probe.baseUrl, form.probe.version, username, password), form.probe.baseUrl, form.probe.version)
        }
    }

    fun signInWithSso() {
        val form = signIn ?: return
        val details = form.probe.settings.oidcProviderDetails ?: return
        _state.value = OnboardingState.SigningIn
        viewModelScope.launch {
            try {
                val (url, request) = oidcStart(form.probe.baseUrl, form.probe.version, details)
                pending.save(request)
                browser.send(url)
                _state.value = form.copy(error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = form.copy(error = errorCopy(LoginResult.Failed(e)))
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun onRedirect(url: Url) {
        redirects.resetReplayCache()
        val request = pending.load() ?: return
        // Any app can fire the redirect Intent: a forged one must not cancel the real sign-in.
        if (url.parameters["state"] != request.state) return
        _state.value = OnboardingState.SigningIn
        val result = oidcComplete(url, request)
        pending.clear()
        finish(result, request.baseUrl, request.serverVersion)
    }

    private suspend fun finish(
        result: LoginResult,
        baseUrl: String,
        version: String,
    ) {
        if (result is LoginResult.OidcOnly) signIn = signIn?.copy(passwordAllowed = false)
        // Both paths name the account after the token's subject (Grimmory's username), so one user is one row.
        val username = (result as? LoginResult.Ok)?.let { jwtSubject(it.tokens.accessToken) }
        if (result !is LoginResult.Ok || username == null) {
            showError(errorCopy(result))
            return
        }
        val id =
            try {
                saveAccount(baseUrl, username, version, result.user, result.tokens)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(GENERIC)
                return
            }
        scheduleSync(id)
        _state.value = OnboardingState.Done(id, defaultPassword = result.tokens.isDefaultPassword == true)
    }

    private fun showError(error: String) {
        _state.value = signIn?.copy(error = error) ?: OnboardingState.EnterServer(error)
    }

    private fun errorCopy(result: LoginResult): String =
        when (result) {
            LoginResult.BadCredentials -> {
                "Wrong username or password"
            }

            LoginResult.OidcOnly -> {
                "This server only allows SSO sign-in"
            }

            is LoginResult.Unsupported -> {
                "This server version isn't supported: ${result.serverVersion}"
            }

            is LoginResult.Failed if result.cause is RedirectRejected -> {
                "Your server's admin needs to allow grimmory://oauth2-callback as a redirect URI"
            }

            is LoginResult.Failed if result.cause is IOException -> {
                UNREACHABLE
            }

            is LoginResult.Failed, is LoginResult.Ok -> {
                GENERIC
            }
        }

    companion object {
        private const val UNREACHABLE = "Can't reach this server"

        private const val GENERIC = "Sign-in didn't work. Try again."

        fun factory(
            container: AppContainer,
            context: Context,
        ) = viewModelFactory {
            initializer {
                val app = context.applicationContext
                OnboardingViewModel(
                    probe = container.serverProbe::probe,
                    passwordLogin = container.passwordLogin::login,
                    oidcStart = container.oidcLogin::start,
                    oidcComplete = container.oidcLogin::complete,
                    pending = container.pendingOidc,
                    redirects = OidcRedirects.incoming,
                    saveAccount = container.accounts::addOrUpdate,
                    scheduleSync = { scheduleSync(app, it, now = true) },
                )
            }
        }
    }
}

// Grimmory puts the username in the access token's subject (JwtUtils); the token came from our server, so no signature check.
// Null when missing, blank or not a string: the sign-in then fails rather than saving an account under a made-up name.
private fun jwtSubject(token: String): String? =
    runCatching {
        val payload = Base64.getUrlDecoder().decode(token.split('.')[1])
        GrimmoryJson
            .parseToJsonElement(payload.decodeToString())
            .jsonObject["sub"]
            ?.jsonPrimitive
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
