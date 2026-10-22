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
package io.github.asm0dey.grimmdroid.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.asm0dey.grimmdroid.R
import io.github.asm0dey.grimmdroid.account.AccountEntity
import io.github.asm0dey.grimmdroid.export.AfterDownloadAction
import io.github.asm0dey.grimmdroid.settings.StartScreen
import io.github.asm0dey.grimmdroid.settings.ThemeMode
import io.github.asm0dey.grimmdroid.ui.nav.serverName
import io.github.asm0dey.grimmdroid.ui.nav.syncedLabel
import kotlinx.coroutines.launch

// Assumed upstream repository; update if the project moves.
private const val SOURCE_URL = "https://github.com/asm0dey/grimmdroid"

private const val APACHE_2 = "Apache License 2.0"

/** The FOSS libraries in gradle/libs.versions.toml; kept by hand (no licence plugin). */
private val LICENCES =
    listOf(
        "AndroidX (Core, Activity, Fragment, Compose, Navigation, Lifecycle, Room, Paging, DataStore, WorkManager, Browser, " +
            "DocumentFile)" to APACHE_2,
        "Kotlin, kotlinx.coroutines, kotlinx.serialization" to APACHE_2,
        "Ktor" to APACHE_2,
        "OkHttp, Okio" to APACHE_2,
        "Coil" to APACHE_2,
        "Readium Kotlin Toolkit" to "BSD 3-Clause License",
        "jsoup" to "MIT License",
        "Android desugar_jdk_libs" to "GPL 2.0 with Classpath Exception",
        "Material Symbols (icons)" to APACHE_2,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    snackbar: SnackbarHostState,
    onSignIn: (baseUrl: String) -> Unit,
    onBack: () -> Unit,
) {
    val accounts by vm.accountList.collectAsState()
    val current by vm.current.collectAsState()
    val afterDownload by vm.afterDownload.collectAsState()
    val theme by vm.theme.collectAsState()
    val dynamic by vm.dynamicColor.collectAsState()
    val animate by vm.animatePageTurns.collectAsState()
    val startScreen by vm.startScreen.collectAsState()
    val folder by vm.folderName.collectAsState()
    val uploadTargetSet by vm.uploadTargetSet.collectAsState()
    val books by vm.booksBytes.collectAsState()
    val covers by vm.coverBytes.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noBrowser = stringResource(R.string.no_browser)
    var confirming by rememberSaveable { mutableStateOf<Long?>(null) }
    var showLicences by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::setFolder) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings)) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back_action)) }
            },
            windowInsets = WindowInsets(0),
        )
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Section(stringResource(R.string.section_accounts))
            accounts.forEach { account ->
                AccountRow(account, onSyncNow = { vm.syncNow(account.id) }, onSignIn = { onSignIn(account.baseUrl) }, onSignOut = {
                    confirming =
                        account.id
                })
            }

            Section(stringResource(R.string.section_start))
            RadioGroup(
                listOf(
                    Triple(StartScreen.HOME, stringResource(R.string.tab_home), null),
                    Triple(StartScreen.LIBRARY, stringResource(R.string.tab_library), null),
                    Triple(StartScreen.LAST_BOOK, stringResource(R.string.start_last_book), stringResource(R.string.start_last_book_hint)),
                    Triple(
                        StartScreen.LAST_PLACE,
                        stringResource(R.string.start_last_place),
                        stringResource(R.string.start_last_place_hint),
                    ),
                ),
                startScreen,
                vm::setStartScreen,
            )

            Section(stringResource(R.string.section_downloads))
            Text(
                stringResource(R.string.after_download_title),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
            val folderLabel = folder ?: stringResource(R.string.your_folder)
            RadioGroup(
                listOf(
                    Triple(
                        AfterDownloadAction.NOTHING,
                        stringResource(R.string.after_nothing),
                        stringResource(R.string.after_nothing_hint),
                    ),
                    Triple(AfterDownloadAction.OPEN, stringResource(R.string.after_open), stringResource(R.string.after_open_hint)),
                    Triple(
                        AfterDownloadAction.SAVE_TO_FOLDER,
                        stringResource(R.string.after_save),
                        stringResource(R.string.after_save_hint, folderLabel),
                    ),
                ),
                afterDownload,
                vm::setAfterDownload,
            )
            if (current != null) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.folder)) },
                    supportingContent = { Text(folder ?: stringResource(R.string.folder_not_chosen)) },
                    trailingContent = { TextButton(onClick = { picker.launch(null) }) { Text(stringResource(R.string.change_action)) } },
                )
            }
            if (current != null) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.upload_to)) },
                    supportingContent = {
                        Text(stringResource(if (uploadTargetSet) R.string.upload_to_set else R.string.upload_to_unset))
                    },
                    trailingContent = {
                        if (uploadTargetSet) TextButton(onClick = vm::clearUploadTarget) { Text(stringResource(R.string.change_action)) }
                    },
                )
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.reader_app)) },
                supportingContent = { Text(stringResource(R.string.ask_every_time)) },
            )

            Section(stringResource(R.string.section_theme))
            RadioGroup(
                listOf(
                    Triple(ThemeMode.SYSTEM, stringResource(R.string.theme_system), null),
                    Triple(ThemeMode.LIGHT, stringResource(R.string.theme_light), null),
                    Triple(ThemeMode.DARK, stringResource(R.string.theme_dark), null),
                ),
                theme,
                vm::setTheme,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.dynamic_colour)) },
                    supportingContent = { Text(stringResource(R.string.dynamic_colour_hint)) },
                    trailingContent = { Switch(checked = dynamic, onCheckedChange = null) },
                    modifier = Modifier.toggleable(dynamic, role = Role.Switch, onValueChange = vm::setDynamicColor),
                )
            }

            Section(stringResource(R.string.section_reading))
            ListItem(
                headlineContent = { Text(stringResource(R.string.animate_page_turns)) },
                supportingContent = { Text(stringResource(R.string.animate_page_turns_hint)) },
                trailingContent = { Switch(checked = animate, onCheckedChange = null) },
                modifier = Modifier.toggleable(animate, role = Role.Switch, onValueChange = vm::setAnimatePageTurns),
            )

            Section(stringResource(R.string.section_storage))
            ListItem(
                headlineContent = { Text(stringResource(R.string.downloaded_books)) },
                supportingContent = { Text(Formatter.formatFileSize(context, books)) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.cover_cache)) },
                supportingContent = { Text(Formatter.formatFileSize(context, covers)) },
                trailingContent = { TextButton(onClick = vm::clearCovers) { Text(stringResource(R.string.clear_cover_cache)) } },
            )

            Section(stringResource(R.string.about))
            ListItem(headlineContent = { Text(stringResource(R.string.version)) }, supportingContent = { Text(appVersion(context)) })
            ListItem(
                headlineContent = { Text(stringResource(R.string.licences)) },
                modifier = Modifier.clickable { showLicences = true },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.source_code)) },
                supportingContent = { Text(SOURCE_URL) },
                modifier = Modifier.clickable { if (!openUrl(context, SOURCE_URL)) scope.launch { snackbar.showSnackbar(noBrowser) } },
            )
        }
    }

    accounts.firstOrNull { it.id == confirming }?.let { account ->
        SignOutDialog(
            account,
            onKeep = {
                confirming = null
                vm.signOut(account.id, removeAccount = false)
            },
            onRemove = {
                confirming = null
                vm.signOut(account.id, removeAccount = true)
            },
            onDismiss = { confirming = null },
        )
    }
    if (showLicences) LicencesDialog { showLicences = false }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.titleSmall,
    )
}

@Composable
private fun AccountRow(
    account: AccountEntity,
    onSyncNow: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
) {
    val synced = account.lastSyncedAt
    val status =
        when {
            account.signedOut -> stringResource(R.string.signed_out)
            synced != null -> syncedLabel(LocalContext.current, synced)
            else -> stringResource(R.string.never_synced)
        }
    ListItem(
        headlineContent = { Text(serverName(account.baseUrl)) },
        supportingContent = { Text("${account.username} · $status") },
    )
    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (account.signedOut) {
            TextButton(onClick = onSignIn) { Text(stringResource(R.string.sign_in)) }
            TextButton(onClick = onSignOut) { Text(stringResource(R.string.remove_action)) }
        } else {
            TextButton(onClick = onSyncNow) { Text(stringResource(R.string.sync_now)) }
            TextButton(onClick = onSignOut) { Text(stringResource(R.string.sign_out)) }
        }
    }
}

@Composable
private fun <T> RadioGroup(
    options: List<Triple<T, String, String?>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        options.forEach { (value, title, hint) ->
            ListItem(
                headlineContent = { Text(title) },
                supportingContent = hint?.let { { Text(it) } },
                leadingContent = { RadioButton(selected = value == selected, onClick = null) },
                modifier = Modifier.selectable(value == selected, role = Role.RadioButton) { onSelect(value) },
            )
        }
    }
}

/** A signed-in account offers Keep (sign out only) or full removal; a signed-out one only removal. */
@Composable
private fun SignOutDialog(
    account: AccountEntity,
    onKeep: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val server = serverName(account.baseUrl)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (account.signedOut) R.string.remove_title else R.string.sign_out_title, server)) },
        text = { Text(stringResource(if (account.signedOut) R.string.remove_body else R.string.sign_out_body)) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (!account.signedOut) TextButton(onClick = onKeep) { Text(stringResource(R.string.keep_books)) }
                TextButton(onClick = onRemove) {
                    Text(stringResource(R.string.remove_account_and_books), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel_action)) }
            }
        },
    )
}

@Composable
private fun LicencesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.licences)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LICENCES.forEach { (name, licence) ->
                    Column {
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                        Text(licence, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_action)) } },
    )
}

private fun appVersion(context: Context): String =
    context.packageManager
        .getPackageInfo(context.packageName, 0)
        .versionName
        .orEmpty()

private fun openUrl(
    context: Context,
    url: String,
): Boolean =
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
