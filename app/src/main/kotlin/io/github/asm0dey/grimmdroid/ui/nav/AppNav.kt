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
package io.github.asm0dey.grimmdroid.ui.nav

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.asm0dey.grimmdroid.AppContainer
import io.github.asm0dey.grimmdroid.R
import io.github.asm0dey.grimmdroid.sync.SyncState
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.launch

private enum class Tab(
    val route: Any,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
) {
    HOME(Home, R.string.tab_home, R.drawable.ic_home),
    LIBRARY(Library, R.string.tab_library, R.drawable.ic_library_books),
    SHELVES(Shelves, R.string.tab_shelves, R.drawable.ic_shelves),
    DOWNLOADS(Downloads, R.string.tab_downloads, R.drawable.ic_download),
}

// Pops back to Home saving the tab being left, so every tab keeps its own back stack.
private fun NavController.openTab(tab: Tab) =
    navigate(tab.route) {
        popUpTo<Home> { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

// A 404 from the app API means the server lost the mobile API (e.g. downgraded).
private fun SyncState.isUnsupported() =
    this is SyncState.Failed && (cause as? ClientRequestException)?.response?.status == HttpStatusCode.NotFound

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(container: AppContainer) {
    val accounts by container.accounts.accounts.collectAsState(null)
    val loaded = accounts ?: return // the start destination depends on whether any account exists
    val current by container.accounts.current.collectAsState(null)
    val online by container.connectivity.online.collectAsState()
    val syncStates by container.librarySync.state.collectAsState()
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    val start: Any = remember { if (loaded.isEmpty()) Onboarding else Home }

    val destination = nav.currentBackStackEntryAsState().value?.destination
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    LaunchedEffect(destination) { Tab.entries.firstOrNull { destination?.hasRoute(it.route::class) == true }?.let { tab = it } }
    val inShell = destination != null && !destination.hasRoute(Onboarding::class)
    var sheetOpen by rememberSaveable { mutableStateOf(false) }

    RequestNotificationsOnce(inShell)

    Scaffold(
        topBar = {
            if (inShell) {
                Column {
                    TopAppBar(
                        title = { Text(stringResource(tab.label)) },
                        actions = {
                            IconButton(onClick = { sheetOpen = true }) {
                                Icon(painterResource(R.drawable.ic_account_circle), stringResource(R.string.accounts))
                            }
                        },
                    )
                    val account = current
                    val sync = account?.let { syncStates[it.id] }
                    GlobalBanners(
                        BannerState(
                            offline = !online,
                            syncing = sync as? SyncState.Running,
                            signedOutServer = account?.takeIf { it.signedOut }?.let { serverName(it.baseUrl) },
                            unsupportedVersion = account?.takeIf { sync?.isUnsupported() == true }?.serverVersion,
                        ),
                        onSignIn = { nav.navigate(Onboarding) },
                    )
                }
            }
        },
        bottomBar = {
            if (inShell) {
                NavigationBar {
                    Tab.entries.forEach {
                        NavigationBarItem(
                            selected = it == tab,
                            onClick = { nav.openTab(it) },
                            icon = { Icon(painterResource(it.icon), contentDescription = null) },
                            label = { Text(stringResource(it.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // Placeholder bodies; Tasks 11–15 fill these in.
        NavHost(nav, startDestination = start, modifier = Modifier.padding(padding)) {
            composable<Onboarding> { Placeholder("Onboarding") }
            composable<Home> { Placeholder("Home") }
            composable<Library> { Placeholder("Library") }
            composable<Shelves> { Placeholder("Shelves") }
            composable<Downloads> { Placeholder("Downloads") }
            composable<ShelfBooks> { Placeholder("Shelf") }
            composable<Details> { Placeholder("Details") }
            composable<Search> { Placeholder("Search") }
            composable<Settings> { Placeholder("Settings") }
        }
    }

    if (sheetOpen) {
        AccountSheet(
            accounts = loaded,
            currentId = current?.id,
            onSwitch = { id ->
                sheetOpen = false
                scope.launch { container.accounts.switchTo(id) }
            },
            onAddServer = {
                sheetOpen = false
                nav.navigate(Onboarding)
            },
            onSettings = {
                sheetOpen = false
                nav.navigate(Settings)
            },
            onDismiss = { sheetOpen = false },
        )
    }
}

/** Asks for POST_NOTIFICATIONS on API 33+ the first time the shell shows; downloads work without it. */
@Composable
private fun RequestNotificationsOnce(inShell: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(inShell) {
        if (!inShell || asked) return@LaunchedEffect
        asked = true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun Placeholder(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(name) }
}
