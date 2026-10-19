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

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.asm0dey.grimmdroid.AppContainer
import io.github.asm0dey.grimmdroid.R
import io.github.asm0dey.grimmdroid.ui.nav.serverName

/** First launch, "Add server" and re-sign-in. [onDone] gets whether the server reported the default password. */
@Composable
fun OnboardingRoute(
    container: AppContainer,
    server: String?,
    onDone: (defaultPassword: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val vm: OnboardingViewModel = viewModel(factory = OnboardingViewModel.factory(container, context, server))
    val state by vm.state.collectAsState()
    var address by rememberSaveable { mutableStateOf(vm.initialAddress) }
    var lastSignIn by remember { mutableStateOf<OnboardingState.SignIn?>(null) }

    LaunchedEffect(vm) {
        vm.openBrowser.collect { url ->
            try {
                CustomTabsIntent.Builder().build().launchUrl(context, url.toString().toUri())
            } catch (e: ActivityNotFoundException) {
                // shortcut: no browser installed means no SSO; the sign-in screen stays as it was.
            }
        }
    }
    LaunchedEffect(state) {
        when (val s = state) {
            is OnboardingState.SignIn -> {
                lastSignIn = s
            }

            is OnboardingState.Done -> {
                onDone(s.defaultPassword)
            }

            else -> {}
        }
    }
    BackHandler(state is OnboardingState.SignIn || state is OnboardingState.CleartextWarning) { vm.back() }

    when (val s = state) {
        is OnboardingState.EnterServer, OnboardingState.Probing -> {
            ServerScreen(
                address = address,
                onAddressChange = { address = it },
                error = (s as? OnboardingState.EnterServer)?.error,
                probing = s == OnboardingState.Probing,
                onContinue = { vm.submitServer(address) },
            )
        }

        is OnboardingState.CleartextWarning -> {
            CleartextWarningScreen(serverName(s.probe.baseUrl), onContinue = vm::acceptCleartext, onBack = vm::back)
        }

        // One call site for SignIn, SigningIn and Done, so the typed username and password survive the state changes.
        is OnboardingState.SignIn, OnboardingState.SigningIn, is OnboardingState.Done -> {
            val signingIn = s !is OnboardingState.SignIn
            val form = (s as? OnboardingState.SignIn) ?: lastSignIn?.copy(error = null)
            if (form != null) {
                SignInScreen(form, signingIn = signingIn, onSso = vm::signInWithSso, onSignIn = vm::signIn)
            } else {
                // A sign-in finishing after process death has no form to show.
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
internal fun OnboardingColumn(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

@Composable
private fun ServerScreen(
    address: String,
    onAddressChange: (String) -> Unit,
    error: String?,
    probing: Boolean,
    onContinue: () -> Unit,
) {
    OnboardingColumn {
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = address,
            onValueChange = onAddressChange,
            label = { Text(stringResource(R.string.server_address)) },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            singleLine = true,
            enabled = !probing,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { onContinue() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onContinue, enabled = !probing, modifier = Modifier.fillMaxWidth()) {
            if (probing) CircularProgressIndicator(Modifier.padding(2.dp)) else Text(stringResource(R.string.continue_action))
        }
    }
}

@Composable
private fun CleartextWarningScreen(
    host: String,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    OnboardingColumn {
        Text(stringResource(R.string.cleartext_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.cleartext_body, host))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back_action)) }
            Button(onClick = onContinue) { Text(stringResource(R.string.continue_anyway)) }
        }
    }
}
