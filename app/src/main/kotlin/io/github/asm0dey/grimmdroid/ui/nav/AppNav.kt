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
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import io.github.asm0dey.grimmdroid.export.ExportResult
import io.github.asm0dey.grimmdroid.reader.ReaderActivity
import io.github.asm0dey.grimmdroid.sync.SyncState
import io.github.asm0dey.grimmdroid.sync.scheduleSync
import io.github.asm0dey.grimmdroid.ui.details.DetailsEvent
import io.github.asm0dey.grimmdroid.ui.details.DetailsScreen
import io.github.asm0dey.grimmdroid.ui.details.DetailsViewModel
import io.github.asm0dey.grimmdroid.ui.downloads.DownloadsScreen
import io.github.asm0dey.grimmdroid.ui.downloads.DownloadsViewModel
import io.github.asm0dey.grimmdroid.ui.group.GroupScreen
import io.github.asm0dey.grimmdroid.ui.group.GroupViewModel
import io.github.asm0dey.grimmdroid.ui.home.HomeScreen
import io.github.asm0dey.grimmdroid.ui.home.HomeViewModel
import io.github.asm0dey.grimmdroid.ui.library.GroupsViewModel
import io.github.asm0dey.grimmdroid.ui.library.LibraryContent
import io.github.asm0dey.grimmdroid.ui.library.LibraryViewModel
import io.github.asm0dey.grimmdroid.ui.library.SearchLauncher
import io.github.asm0dey.grimmdroid.ui.library.ShelfScope
import io.github.asm0dey.grimmdroid.ui.onboarding.OnboardingRoute
import io.github.asm0dey.grimmdroid.ui.search.SearchScreen
import io.github.asm0dey.grimmdroid.ui.search.SearchViewModel
import io.github.asm0dey.grimmdroid.ui.settings.SettingsScreen
import io.github.asm0dey.grimmdroid.ui.settings.SettingsViewModel
import io.github.asm0dey.grimmdroid.ui.shelves.ShelvesScreen
import io.github.asm0dey.grimmdroid.upload.IncomingBooks
import io.github.asm0dey.grimmdroid.upload.UploadScreen
import io.github.asm0dey.grimmdroid.upload.UploadViewModel
import io.github.asm0dey.grimmdroid.upload.uploadViewModelFactory
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
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
// Reselecting the current tab returns to its root; navigating would just restore the stack it is already showing.
internal fun NavController.openTab(
    route: Any,
    reselected: Boolean,
) {
    if (reselected) {
        popBackStack(route, inclusive = false)
        return
    }
    navigate(route) {
        popUpTo<Home> { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
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
    val start: Any = remember { if (loaded.isEmpty()) Onboarding() else Home }

    val backStackEntry = nav.currentBackStackEntryAsState().value
    val destination = backStackEntry?.destination
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    LaunchedEffect(destination) { Tab.entries.firstOrNull { destination?.hasRoute(it.route::class) == true }?.let { tab = it } }
    val inShell = destination != null && !destination.hasRoute(Onboarding::class)
    val onLibrary = destination?.hasRoute(Library::class) == true
    // Library, Home, Details, Settings and the series/author pages draw their own header under the banners instead of the app bar.
    val ownHeader =
        onLibrary ||
            listOf(Home::class, Details::class, Settings::class, Series::class, Author::class, Upload::class).any {
                destination?.hasRoute(it) ==
                    true
            }
    val onSearch = destination?.hasRoute(Search::class) == true
    val sync = current?.let { syncStates[it.id] }
    val lastSynced = current?.lastSyncedAt
    val syncStatus =
        when {
            sync is SyncState.Running -> stringResource(R.string.sync_running)
            lastSynced != null -> syncedLabel(LocalContext.current, lastSynced)
            else -> null
        }
    val baseUrl = current?.baseUrl
    val openBook: (Long) -> Unit = { id -> current?.id?.let { nav.navigate(Details(id, it)) } }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val defaultPassword = stringResource(R.string.default_password)
    val signOutFailed = stringResource(R.string.sign_out_failed)
    LaunchedEffect(Unit) { container.signOutFailures.receiveAsFlow().collect { snackbar.showSnackbar(signOutFailed) } }
    // Removing the last account leaves nothing to show: start over at Onboarding (the start destination is frozen).
    LaunchedEffect(loaded.isEmpty()) {
        if (loaded.isEmpty() && nav.currentDestination?.hasRoute(Onboarding::class) == false) {
            nav.navigate(Onboarding()) { popUpTo(nav.graph.id) { inclusive = true } }
        }
    }
    RequestNotificationsOnce(inShell)
    val context = LocalContext.current
    LaunchedEffect(current?.id) {
        current?.takeIf { !it.signedOut }?.let {
            if (container.db.catalogDao().needsAuthorBackfill(it.id)) scheduleSync(context, it.id, now = true)
        }
    }
    OpenReadyBooks(container, snackbar)
    // A book handed in before any account is signed in waits here until one is.
    val incoming by IncomingBooks.incoming.collectAsState()
    // Waits for the shell too: Onboarding's finish pops everything above it, an Upload pushed on top of it included.
    LaunchedEffect(incoming, current?.id, current?.signedOut, inShell) {
        val uri = incoming ?: return@LaunchedEffect
        if (!inShell) return@LaunchedEffect
        val account = current?.takeIf { !it.signedOut } ?: return@LaunchedEffect
        IncomingBooks.incoming.value = null
        nav.navigate(Upload(uri.toString(), account.id))
    }
    val pickBook =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                IncomingBooks.incoming.value =
                    it
            }
        }
    ResetTabsOnAccountSwitch(nav, current?.id)
    FinishSsoRedirects(container, nav)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (inShell && !onSearch) {
                Column(if (ownHeader) Modifier.statusBarsPadding() else Modifier) {
                    if (!ownHeader) {
                        val shelf = backStackEntry.takeIf { destination.hasRoute(ShelfBooks::class) }?.toRoute<ShelfBooks>()
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
                        onSignIn = { nav.navigate(Onboarding(account?.baseUrl)) },
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
                            onClick = { nav.openTab(it.route, reselected = it == tab) },
                            icon = { Icon(painterResource(it.icon), contentDescription = null) },
                            // One line even at the largest font: a wrapped label breaks mid-word and lifts the icon.
                            label = { Text(stringResource(it.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = start, modifier = Modifier.padding(padding)) {
            composable<Onboarding> { entry ->
                OnboardingRoute(container, entry.toRoute<Onboarding>().server) { isDefault ->
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
                    nav.openTab(if (it == Downloads) Downloads else Library, reselected = false)
                }
            }
            composable<Library> {
                val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container, null))
                val groups: GroupsViewModel = viewModel(factory = GroupsViewModel.factory(container))
                LibraryContent(
                    vm,
                    baseUrl,
                    syncStatus,
                    openBook,
                    groups,
                    { name -> current?.id?.let { nav.navigate(Author(name, it)) } },
                    { name -> current?.id?.let { nav.navigate(Series(name, it)) } },
                ) {
                    SearchLauncher(
                        onSearch = { nav.navigate(Search) },
                        onAccount = { sheetOpen = true },
                        onUpload = { pickBook.launch(arrayOf("application/epub+zip")) },
                    )
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
            composable<Details> { entry ->
                val route = entry.toRoute<Details>()
                val vm: DetailsViewModel = viewModel(factory = DetailsViewModel.factory(container, route.accountId, route.bookId))
                DetailsScreen(
                    vm,
                    baseUrl,
                    snackbar,
                    container.exporter,
                    { uri -> container.folderExport.setFolder(route.accountId, uri) },
                    { nav.navigate(Author(it, route.accountId)) },
                    { nav.navigate(Series(it, route.accountId)) },
                ) { nav.popBackStack() }
            }
            composable<Series> { entry ->
                val route = entry.toRoute<Series>()
                val vm: GroupViewModel =
                    viewModel(factory = GroupViewModel.factory(container, route.accountId, route.name, byAuthor = false))
                GroupScreen(vm, baseUrl, { nav.navigate(Details(it, route.accountId)) }) { nav.popBackStack() }
            }
            composable<Author> { entry ->
                val route = entry.toRoute<Author>()
                val vm: GroupViewModel =
                    viewModel(factory = GroupViewModel.factory(container, route.accountId, route.name, byAuthor = true))
                GroupScreen(vm, baseUrl, { nav.navigate(Details(it, route.accountId)) }) { nav.popBackStack() }
            }
            composable<Search> {
                val vm: SearchViewModel = viewModel(factory = SearchViewModel.factory(container))
                SearchScreen(vm, baseUrl, openBook) { nav.popBackStack() }
            }
            composable<Upload> { entry ->
                val route = entry.toRoute<Upload>()
                val vm: UploadViewModel = viewModel(factory = uploadViewModelFactory(container, route.accountId, Uri.parse(route.uri)))
                UploadScreen(vm, baseUrl?.let(::serverName).orEmpty()) { nav.popBackStack() }
            }
            composable<Settings> {
                val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
                SettingsScreen(vm, snackbar, onSignIn = { nav.navigate(Onboarding(it)) }) { nav.popBackStack() }
            }
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
                nav.navigate(Onboarding())
            },
            onSettings = {
                sheetOpen = false
                nav.navigate(Settings)
            },
            onDismiss = { sheetOpen = false },
        )
    }
}

/** Opens a book downloaded for reading: here rather than on Details, so it opens even after the user left that screen. */
@Composable
private fun OpenReadyBooks(
    container: AppContainer,
    snackbar: SnackbarHostState,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val noApp = stringResource(R.string.no_app_to_open)
    LaunchedEffect(Unit) {
        container.bookOpens.collect { event ->
            // In the background the download's notification carries the read instead.
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@collect
            when (event) {
                is DetailsEvent.Read -> {
                    context.startActivity(ReaderActivity.intent(context, event.accountId, event.bookId, event.file))
                }

                is DetailsEvent.OpenWith -> {
                    if (container.exporter.openWith(event.file) == ExportResult.NoApp) snackbar.showSnackbar(noApp)
                }

                else -> {
                    Unit
                }
            }
        }
    }
}

/** An SSO redirect can arrive while Onboarding isn't showing (process death during "Add server"); its ViewModel finishes it. */
@Composable
private fun FinishSsoRedirects(
    container: AppContainer,
    nav: NavController,
) {
    LaunchedEffect(Unit) {
        OidcRedirects.incoming.collect {
            if (container.pendingOidc.load() != null && nav.currentDestination?.hasRoute(Onboarding::class) != true) {
                nav.navigate(Onboarding())
            }
        }
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
