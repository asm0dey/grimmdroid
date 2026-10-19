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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.asm0dey.grimmdroid.R
import io.github.asm0dey.grimmdroid.ui.nav.serverName

@Composable
internal fun SignInScreen(
    state: OnboardingState.SignIn,
    signingIn: Boolean,
    onSso: () -> Unit,
    onSignIn: (username: String, password: String) -> Unit,
) {
    var username by rememberSaveable { mutableStateOf("") }
    // Not saveable: the password must not land in the saved-instance-state bundle.
    var password by remember { mutableStateOf("") }
    val submit = { if (!signingIn && username.isNotBlank()) onSignIn(username.trim(), password) }

    OnboardingColumn {
        Text(stringResource(R.string.sign_in_title, serverName(state.probe.baseUrl)), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.connection_checked, state.probe.version),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.probe.cleartext) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    stringResource(R.string.cleartext_short),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        if (state.ssoAllowed) {
            Button(onClick = onSso, enabled = !signingIn, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sign_in_sso))
            }
            if (!state.passwordAllowed) state.error?.let { ErrorText(it) }
        }
        if (state.ssoAllowed && state.passwordAllowed) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HorizontalDivider(Modifier.weight(1f))
                Text(stringResource(R.string.or_divider), style = MaterialTheme.typography.labelLarge)
                HorizontalDivider(Modifier.weight(1f))
            }
        }
        if (state.passwordAllowed) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(stringResource(R.string.username)) },
                singleLine = true,
                enabled = !signingIn,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.password)) },
                isError = state.error != null,
                supportingText = state.error?.let { { Text(it) } },
                singleLine = true,
                enabled = !signingIn,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            val signIn = @Composable { Text(stringResource(R.string.sign_in)) }
            if (state.ssoAllowed) {
                OutlinedButton(onClick = submit, enabled = !signingIn, modifier = Modifier.fillMaxWidth()) { signIn() }
            } else {
                Button(onClick = submit, enabled = !signingIn, modifier = Modifier.fillMaxWidth()) { signIn() }
            }
        }
        if (!state.ssoAllowed && !state.passwordAllowed) ErrorText(stringResource(R.string.no_sign_in_method))
        Text(
            stringResource(R.string.sign_in_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
