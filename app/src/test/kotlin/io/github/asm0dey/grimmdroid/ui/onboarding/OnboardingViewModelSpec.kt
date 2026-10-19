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

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import io.github.asm0dey.grimmdroid.api.AppUserInfo
import io.github.asm0dey.grimmdroid.api.OidcProviderDetails
import io.github.asm0dey.grimmdroid.api.ProbeResult
import io.github.asm0dey.grimmdroid.api.PublicSettings
import io.github.asm0dey.grimmdroid.api.Tokens
import io.github.asm0dey.grimmdroid.auth.LoginResult
import io.github.asm0dey.grimmdroid.auth.PendingOidc
import io.github.asm0dey.grimmdroid.auth.PendingOidcStore
import io.github.asm0dey.grimmdroid.auth.RedirectRejected
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.http.Url
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.IOException
import java.util.Base64

private class MemoryStore : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences) = transform(data.value).also { data.value = it }
}

private val details = OidcProviderDetails(clientId = "grim", issuerUri = "https://idp.example")
private val https = ProbeResult.Ok("https://grim.example", "v3.5.0", PublicSettings(), cleartext = false)
private val sso = https.copy(settings = PublicSettings(oidcEnabled = true, oidcProviderDetails = details))
private val user = AppUserInfo(canDownload = true)

private fun jwtWithPayload(payload: String) =
    "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray()) + ".sig"

private fun jwt(sub: String) = jwtWithPayload("""{"sub":"$sub"}""")

private class Saved(
    val baseUrl: String,
    val username: String,
    val version: String,
)

@OptIn(ExperimentalCoroutinesApi::class)
private class Fixture(
    var probeResult: ProbeResult = https,
    var loginResult: LoginResult = LoginResult.Ok(Tokens(jwt("pavel"), "r"), user),
    var completeResult: LoginResult = LoginResult.Ok(Tokens(jwt("oidc-user"), "r"), user),
) {
    val store = PendingOidcStore(MemoryStore())
    val redirects = MutableSharedFlow<Url>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val saved = mutableListOf<Saved>()
    val synced = mutableListOf<Long>()
    var completed = 0
    var startError: Exception? = null
    var saveError: Exception? = null

    fun vm() =
        OnboardingViewModel(
            probe = { probeResult },
            passwordLogin = { _, _, _, _ -> loginResult },
            oidcStart = { baseUrl, version, _ ->
                startError?.let { throw it }
                Url("https://idp.example/authorize?state=s1") to PendingOidc(baseUrl, version, "s1", "n1", "v1")
            },
            oidcComplete = { _, _ -> completeResult.also { completed++ } },
            pending = store,
            redirects = redirects,
            saveAccount = { baseUrl, username, version, _, _ ->
                saveError?.let { throw it }
                saved += Saved(baseUrl, username, version)
                42L
            },
            scheduleSync = { synced += it },
        )
}

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelSpec :
    FunSpec({
        val main = StandardTestDispatcher()
        beforeTest { Dispatchers.setMain(main) }
        afterTest { Dispatchers.resetMain() }

        fun probeError(result: ProbeResult): String? =
            Fixture(probeResult = result).vm().let { vm ->
                var error: String? = null
                runTest(main) {
                    vm.submitServer("whatever")
                    advanceUntilIdle()
                    error = (vm.state.value as OnboardingState.EnterServer).error
                }
                error
            }

        test("each probe failure maps to its copy") {
            probeError(ProbeResult.Unreachable) shouldBe "Can't reach this server"
            probeError(ProbeResult.NotGrimmory) shouldBe "This isn't a Grimmory server"
            probeError(ProbeResult.InvalidAddress) shouldBe "That doesn't look like a server address"
            probeError(ProbeResult.TlsUntrusted("grim.example")) shouldBe
                "This server's certificate isn't trusted. Install it in Android Settings › Security › Encryption & credentials, then try again."
        }

        test("http server warns first, then signs in after acceptCleartext") {
            val http = https.copy(baseUrl = "http://grim.example", cleartext = true)
            val vm = Fixture(probeResult = http).vm()
            runTest(main) {
                vm.submitServer("http://grim.example")
                advanceUntilIdle()
                vm.state.value shouldBe OnboardingState.CleartextWarning(http)
                vm.acceptCleartext()
                vm.state.value shouldBe OnboardingState.SignIn(http, passwordAllowed = true, ssoAllowed = false, error = null)
            }
        }

        test("OIDC-only server hides password login") {
            val only = sso.copy(settings = sso.settings.copy(oidcForceOnlyMode = true))
            val vm = Fixture(probeResult = only).vm()
            runTest(main) {
                vm.submitServer("grim.example")
                advanceUntilIdle()
                vm.state.value shouldBe OnboardingState.SignIn(only, passwordAllowed = false, ssoAllowed = true, error = null)
            }
        }

        test("successful password login stores the account, schedules sync and is Done") {
            val f = Fixture(loginResult = LoginResult.Ok(Tokens(jwt("pavel"), "r", isDefaultPassword = true), user))
            val vm = f.vm()
            runTest(main) {
                vm.submitServer("grim.example")
                advanceUntilIdle()
                vm.signIn("Pavel", "pw") // the token's subject, not the typed spelling, names the account
                advanceUntilIdle()
                vm.state.value shouldBe OnboardingState.Done(42, defaultPassword = true)
                f.saved.single().let { listOf(it.baseUrl, it.username, it.version) } shouldBe
                    listOf("https://grim.example", "pavel", "v3.5.0")
                f.synced shouldBe listOf(42L)
            }
        }

        test("login failures map to copy on the sign-in screen") {
            fun loginError(result: LoginResult): String? {
                val vm = Fixture(loginResult = result).vm()
                var error: String? = null
                runTest(main) {
                    vm.submitServer("grim.example")
                    advanceUntilIdle()
                    vm.signIn("pavel", "pw")
                    advanceUntilIdle()
                    error = (vm.state.value as OnboardingState.SignIn).error
                }
                return error
            }
            loginError(LoginResult.BadCredentials) shouldBe "Wrong username or password"
            loginError(LoginResult.OidcOnly) shouldBe "This server only allows SSO sign-in"
            loginError(LoginResult.Unsupported("v2.0.0")) shouldBe "This server version isn't supported: v2.0.0"
            loginError(LoginResult.Failed(IOException("boom"))) shouldBe "Can't reach this server"
            loginError(LoginResult.Failed(IllegalStateException("boom"))) shouldBe "Sign-in didn't work. Try again."
        }

        test("a token without a usable subject fails the sign-in and saves nothing") {
            for (payload in listOf("{}", """{"sub":null}""", """{"sub":"  "}""", """{"sub":7}""")) {
                val f = Fixture(loginResult = LoginResult.Ok(Tokens(jwtWithPayload(payload), "r"), user))
                val vm = f.vm()
                runTest(main) {
                    vm.submitServer("grim.example")
                    advanceUntilIdle()
                    vm.signIn("pavel", "pw")
                    advanceUntilIdle()
                    (vm.state.value as OnboardingState.SignIn).error shouldBe "Sign-in didn't work. Try again."
                    f.saved shouldBe emptyList()
                    f.synced shouldBe emptyList()
                }
            }
        }

        test("OidcOnly from login hides the password form") {
            val vm = Fixture(probeResult = sso, loginResult = LoginResult.OidcOnly).vm()
            runTest(main) {
                vm.submitServer("grim.example")
                advanceUntilIdle()
                vm.signIn("pavel", "pw")
                advanceUntilIdle()
                (vm.state.value as OnboardingState.SignIn).passwordAllowed shouldBe false
            }
        }

        test("a failing oidcStart stays on the sign-in screen with an error and saves no pending sign-in") {
            val f = Fixture(probeResult = sso).apply { startError = IllegalStateException("HTTP 500") }
            val vm = f.vm()
            runTest(main) {
                vm.submitServer("grim.example")
                advanceUntilIdle()
                vm.signInWithSso()
                advanceUntilIdle()
                (vm.state.value as OnboardingState.SignIn).error shouldBe "Sign-in didn't work. Try again."
                f.store.load().shouldBeNull()
            }
        }

        test("a failing account save shows the generic error and the redirect collector keeps running") {
            val f = Fixture(probeResult = sso).apply { saveError = IllegalStateException("disk full") }
            val vm = f.vm()
            runTest(main) {
                vm.submitServer("grim.example")
                advanceUntilIdle()
                vm.signInWithSso()
                advanceUntilIdle()
                f.redirects.emit(Url("grimmory://oauth2-callback?state=s1&code=c"))
                advanceUntilIdle()
                (vm.state.value as OnboardingState.SignIn).error shouldBe "Sign-in didn't work. Try again."
                f.saveError = null
                vm.signInWithSso()
                advanceUntilIdle()
                f.redirects.emit(Url("grimmory://oauth2-callback?state=s1&code=c"))
                advanceUntilIdle()
                vm.state.value shouldBe OnboardingState.Done(42, defaultPassword = false)
            }
        }

        test("back and a new server address abandon a pending SSO sign-in") {
            val f = Fixture(probeResult = sso)
            val vm = f.vm()
            runTest(main) {
                vm.submitServer("grim.example")
                advanceUntilIdle()
                vm.signInWithSso()
                advanceUntilIdle()
                vm.back()
                advanceUntilIdle()
                f.store.load().shouldBeNull()
                vm.submitServer("grim.example")
                advanceUntilIdle()
                vm.signInWithSso()
                advanceUntilIdle()
                vm.submitServer("other.example")
                advanceUntilIdle()
                f.store.load().shouldBeNull()
            }
        }

        test("SSO: opens the browser, completes on the redirect, names the account after the token subject") {
            val f = Fixture(probeResult = sso)
            val vm = f.vm()
            runTest(main) {
                vm.submitServer("grim.example")
                advanceUntilIdle()
                vm.signInWithSso()
                advanceUntilIdle()
                vm.openBrowser.first() shouldBe Url("https://idp.example/authorize?state=s1")
                f.store.load()?.state shouldBe "s1"
                f.redirects.emit(Url("grimmory://oauth2-callback?state=s1&code=c"))
                advanceUntilIdle()
                vm.state.value shouldBe OnboardingState.Done(42, defaultPassword = false)
                f.saved.single().username shouldBe "oidc-user"
                f.synced shouldBe listOf(42L)
                f.store.load().shouldBeNull()
                f.redirects.replayCache shouldBe emptyList()
            }
        }

        test("a redirect with the wrong state keeps the pending sign-in and changes nothing") {
            val f = Fixture(probeResult = sso)
            val vm = f.vm()
            runTest(main) {
                vm.submitServer("grim.example")
                advanceUntilIdle()
                vm.signInWithSso()
                advanceUntilIdle()
                val before = vm.state.value
                f.redirects.emit(Url("grimmory://oauth2-callback?state=evil&code=c"))
                advanceUntilIdle()
                vm.state.value shouldBe before
                f.completed shouldBe 0
                f.store.load()?.state shouldBe "s1"
            }
        }

        test("server refusing the redirect URI shows the admin copy and clears the pending sign-in") {
            val f = Fixture(probeResult = sso, completeResult = LoginResult.Failed(RedirectRejected("grimmory://oauth2-callback")))
            val vm = f.vm()
            runTest(main) {
                vm.submitServer("grim.example")
                advanceUntilIdle()
                vm.signInWithSso()
                advanceUntilIdle()
                f.redirects.emit(Url("grimmory://oauth2-callback?state=s1&code=c"))
                advanceUntilIdle()
                (vm.state.value as OnboardingState.SignIn).error shouldBe
                    "Your server's admin needs to allow grimmory://oauth2-callback as a redirect URI"
                f.store.load().shouldBeNull()
            }
        }

        test("after process death a fresh ViewModel finishes the pending SSO sign-in") {
            val f = Fixture()
            runTest(main) {
                f.store.save(PendingOidc("https://grim.example", "v3.5.0", "s1", "n1", "v1"))
                f.redirects.emit(Url("grimmory://oauth2-callback?state=s1&code=c"))
                val vm = f.vm()
                advanceUntilIdle()
                vm.state.value shouldBe OnboardingState.Done(42, defaultPassword = false)
                f.saved.single().baseUrl shouldBe "https://grim.example"
            }
        }
    })
