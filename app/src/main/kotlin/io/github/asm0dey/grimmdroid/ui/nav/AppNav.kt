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
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.asm0dey.grimmdroid.AppContainer
import io.github.asm0dey.grimmdroid.R
import io.github.asm0dey.grimmdroid.auth.OidcRedirects
import io.github.asm0dey.grimmdroid.sync.SyncState
import io.github.asm0dey.grimmdroid.ui.downloads.DownloadsScreen
import io.github.asm0dey.grimmdroid.ui.downloads.DownloadsViewModel
import io.github.asm0dey.grimmdroid.ui.home.HomeScreen
import io.github.asm0dey.grimmdroid.ui.home.HomeViewModel
import io.github.asm0dey.grimmdroid.ui.library.LibraryContent
import io.github.asm0dey.grimmdroid.ui.library.LibraryViewModel
import io.github.asm0dey.grimmdroid.ui.library.SearchLauncher
import io.github.asm0dey.grimmdroid.ui.library.ShelfScope
import io.github.asm0dey.grimmdroid.ui.onboarding.OnboardingRoute
import io.github.asm0dey.grimmdroid.ui.search.SearchScreen
import io.github.asm0dey.grimmdroid.ui.search.SearchViewModel
import io.github.asm0dey.grimmdroid.ui.shelves.ShelvesScreen
import kotlinx.coroutines.flow.flowOf
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

    val backStackEntry = nav.currentBackStackEntryAsState().value
    val destination = backStackEntry?.destination
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    LaunchedEffect(destination) { Tab.entries.firstOrNull { destination?.hasRoute(it.route::class) == true }?.let { tab = it } }
    val inShell = destination != null && !destination.hasRoute(Onboarding::class)
    val onLibrary = destination?.hasRoute(Library::class) == true
    val ownHeader = onLibrary || destination?.hasRoute(Home::class) == true
    val onSearch = destination?.hasRoute(Search::class) == true
    val sync = current?.let { syncStates[it.id] }
    val lastSynced = current?.lastSyncedAt
    val syncStatus =
        when {
            sync is SyncState.Running -> stringResource(R.string.sync_running)
            lastSynced != null -> stringResource(R.string.synced_at, DateUtils.getRelativeTimeSpanString(lastSynced))
            else -> null
        }
    val baseUrl = current?.baseUrl
    val openBook: (Long) -> Unit = { nav.navigate(Details(it)) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val defaultPassword = stringResource(R.string.default_password)

    RequestNotificationsOnce(inShell)
    ResetTabsOnAccountSwitch(nav, current?.id)
    // An SSO redirect can arrive while Onboarding isn't showing (process death during "Add server"); its ViewModel finishes it.
    LaunchedEffect(Unit) {
        OidcRedirects.incoming.collect {
            if (container.pendingOidc.load() != null &&
                nav.currentDestination?.hasRoute(Onboarding::class) != true
            ) {
                nav.navigate(Onboarding)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (inShell && !onSearch) {
                // Library and Home draw their own search bar and account button instead of the app bar.
                Column(if (ownHeader) Modifier.statusBarsPadding() else Modifier) {
                    if (!ownHeader) {
                        val shelf = backStackEntry?.takeIf { destination.hasRoute(ShelfBooks::class) }?.toRoute<ShelfBooks>()
                        TopAppBar(
                            title = { Text(shelf?.name ?: stringResource(tab.label)) },
                            navigationIcon = {
                                if (shelf != null) {
                                    IconButton(onClick = { nav.popBackStack() }) {
                                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back_action))
                                    }
                                }
                            },
                            actions = {
                                IconButton(onClick = { sheetOpen = true }) {
                                    Icon(painterResource(R.drawable.ic_account_circle), stringResource(R.string.accounts))
                                }
                            },
                        )
                    }
                    val account = current
                    GlobalBanners(
                        BannerState(
                            offline = !online,
                            syncing = sync as? SyncState.Running,
                            signedOutServer = account?.takeIf { it.signedOut }?.let { serverName(it.baseUrl) },
                            unsupportedVersion =
                                (sync as? SyncState.Unsupported)?.let { it.serverVersion ?: stringResource(R.string.unknown_version) },
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
        // Placeholder bodies; Tasks 14–15 fill these in.
        NavHost(nav, startDestination = start, modifier = Modifier.padding(padding)) {
            composable<Onboarding> {
                OnboardingRoute(container) { isDefault ->
                    // Pops Onboarding even when it was the start destination; AppNav's start is frozen at first load.
                    nav.navigate(Home) {
                        popUpTo<Onboarding> { inclusive = true }
                        launchSingleTop = true
                    }
                    if (isDefault) scope.launch { snackbar.showSnackbar(defaultPassword) }
                }
            }
            composable<Home> {
                val vm: HomeViewModel = viewModel(factory = HomeViewModel.factory(container))
                HomeScreen(vm, baseUrl, onSearch = { nav.navigate(Search) }, onAccount = { sheetOpen = true }, onBook = openBook) {
                    nav.openTab(if (it == Downloads) Tab.DOWNLOADS else Tab.LIBRARY)
                }
            }
            composable<Library> {
                val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container, null))
                LibraryContent(vm, baseUrl, syncStatus, openBook) {
                    SearchLauncher(onSearch = { nav.navigate(Search) }, onAccount = { sheetOpen = true })
                }
            }
            composable<Shelves> {
                val accountId = current?.id
                val dao = container.db.catalogDao()
                val shelves by remember(accountId) { accountId?.let(dao::shelves) ?: flowOf(emptyList()) }.collectAsState(emptyList())
                ShelvesScreen(shelves) { nav.navigate(ShelfBooks(it.id, it.magic, it.name)) }
            }
            composable<Downloads> {
                val vm: DownloadsViewModel = viewModel(factory = DownloadsViewModel.factory(container))
                val accountId = current?.id
                DownloadsScreen(vm, baseUrl, snackbar, { uri -> accountId?.let { container.folderExport.setFolder(it, uri) } }, openBook)
            }
            composable<ShelfBooks> { entry ->
                val route = entry.toRoute<ShelfBooks>()
                val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container, ShelfScope(route.shelfId, route.magic)))
                LibraryContent(vm, baseUrl, syncStatus, openBook)
            }
            composable<Details> { Placeholder("Details") }
            composable<Search> {
                val vm: SearchViewModel = viewModel(factory = SearchViewModel.factory(container))
                SearchScreen(vm, baseUrl, openBook) { nav.popBackStack() }
            }
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

/** Account-scoped destinations (details, shelves) must not survive a switch, so every tab starts over at its root. */
@Composable
private fun ResetTabsOnAccountSwitch(
    nav: NavController,
    accountId: Long?,
) {
    var shownId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(accountId) {
        if (accountId == null) return@LaunchedEffect
        if (shownId != null && shownId != accountId) {
            Tab.entries.forEach { nav.clearBackStack(it.route) }
            nav.navigate(Home) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = false }
                launchSingleTop = true
            }
        }
        shownId = accountId
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
