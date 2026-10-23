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
package io.github.asm0dey.grimmdroid.reader

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ActionMode
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.withStarted
import io.github.asm0dey.grimmdroid.GrimmdroidApp
import io.github.asm0dey.grimmdroid.R
import io.github.asm0dey.grimmdroid.catalog.DownloadState
import io.github.asm0dey.grimmdroid.reader.curl.TurnOverlay
import io.github.asm0dey.grimmdroid.reader.curl.TurnResult
import io.github.asm0dey.grimmdroid.reader.curl.visibleWebView
import io.github.asm0dey.grimmdroid.settings.PageTurn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.Key
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.html.cssSelector
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.util.AbsoluteUrl
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import org.readium.r2.navigator.input.KeyEvent as InputKeyEvent

class ReaderActivity : FragmentActivity() {
    private val vm: ReaderViewModel by viewModels {
        val container = (application as GrimmdroidApp).container
        val accountId = intent.getLongExtra(EXTRA_ACCOUNT_ID, -1)
        val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1)
        val file = File(intent.getStringExtra(EXTRA_FILE).orEmpty())
        val fontsDirs = container.fonts.dirs(accountId)
        viewModelFactory {
            initializer {
                ReaderViewModel(
                    accountId,
                    bookId,
                    {
                        container.downloadRepository
                            .observe(accountId, bookId)
                            .first()
                            ?.takeIf { it.state == DownloadState.DONE }
                            ?.fileId
                    },
                    file,
                    { container.appContext.openPublication(it, fontsDirs) },
                    container.positionSync,
                    container.bookmarks,
                    container.db.readerDao(),
                    {
                        container.accounts
                            .account(accountId)
                            ?.takeUnless { it.signedOut }
                            ?.let(container::api)
                    },
                    container.connectivity.online,
                    container.appScope,
                )
            }
        }
    }

    private val container get() = (application as GrimmdroidApp).container
    private val settingsStore get() = container.readerSettings
    private val fontStore get() = (application as GrimmdroidApp).container.fonts

    private val appSettings get() = (application as GrimmdroidApp).container.settings

    // The app setting, read on every turn.
    private var pageTurn = PageTurn.NONE
    private val accountId get() = intent.getLongExtra(EXTRA_ACCOUNT_ID, -1)

    // The custom fonts on the phone, and those the latest navigator factory declared (null before the first one).
    private var fonts by mutableStateOf(emptyList<ReaderFont>())
    private var declaredFonts: List<ReaderFont>? = null
    private val pickFont =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                fontStore.import(this@ReaderActivity, uri).onFailure {
                    Toast.makeText(this@ReaderActivity, R.string.reader_font_import_failed, Toast.LENGTH_SHORT).show()
                }
                refreshFonts()
            }
        }
    private var settings by mutableStateOf(ReaderSettings())
    private var settingsOpen by mutableStateOf(false)
    private var footnote by mutableStateOf<String?>(null)
    private lateinit var navigatorContainer: FragmentContainerView
    private lateinit var curl: TurnOverlay

    // Columns on screen, from the page's CSS; 0 until a page reports it (the curl stays off until then).
    private var columns = 0
    private var selecting = false // a text selection's action mode is up
    private var repicturing: Job? = null

    // The bookmark span Readium reported mid-turn, shown once the turn settles.
    private var pendingPage: PageSpan? = null

    // The locator the settle handled; its late delivery to the collector is not a second visit.
    private var handledOnSettle: Locator? = null

    // A curl turn from an edge tap that found no page: like Readium's own turn there, the tap toggles the chrome.
    private var tapOpensChrome = false
    private var hitBookEdge = false

    private val systemDark get() =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private val navigatorListener =
        object : EpubNavigatorFragment.Listener {
            override fun shouldFollowInternalLink(
                link: Link,
                context: HyperlinkNavigator.LinkContext?,
            ): Boolean {
                if (!settings.footnotePopup || context !is HyperlinkNavigator.FootnoteContext) return true
                footnote = context.noteContent
                return false
            }

            override fun onExternalLinkActivated(url: AbsoluteUrl) {
                if (!opensOutside(url.toString())) return
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url.toString().toUri()))
                } catch (_: ActivityNotFoundException) {
                    // No app to open the link with: the tap does nothing.
                }
            }
        }

    private var chromeVisible by mutableStateOf(false)
    private var locator by mutableStateOf<Locator?>(null)

    // The page on screen, for "is it bookmarked"; null until the navigator reports one.
    private var page by mutableStateOf<PageSpan?>(null)

    // Readium reports its first page once the start resource has loaded; a go() before that strands it, never
    // reporting a place again (readium/kotlin-toolkit State.Loading): until then, a jump restarts the navigator.
    private var navigatorReady = false
    private val scrolled get() = settings.epub.scroll == true

    // Deprecated in Readium but the only page count it reports; with scroll on it counts the whole resource as one page.
    @Suppress("DEPRECATION")
    private val paginationListener =
        object : EpubNavigatorFragment.PaginationListener {
            override fun onPageChanged(
                pageIndex: Int,
                totalPages: Int,
                locator: Locator,
            ) {
                navigatorReady = true
                if (scrolled) return
                val span = PageSpan.of(locator, 1.0 / totalPages.coerceAtLeast(1))
                if (curl.turning) pendingPage = span else page = span
            }

            override fun onPageLoaded() = repicture() // a chapter, or its neighbour, can now be drawn
        }
    private var publication by mutableStateOf<Publication?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        if (intent.getStringExtra(EXTRA_FILE) == null) {
            super.onCreate(savedInstanceState)
            return finish()
        }
        // The ViewModel keeps the book open across rotation: the restored navigator gets it right away, once the
        // saved place is known too, so the navigator never starts at the book's start over it.
        val open =
            vm.publication
                .takeIf { it.isCompleted && vm.initialLocator.isCompleted }
                ?.getCompleted()
                ?.getOrNull()
        if (open != null) {
            // Only after the book opened once in this process, so the DataStore already holds the settings in memory.
            settings = runBlocking { settingsStore.settings.first() }
            pageTurn =
                runBlocking {
                    appSettings.migratePageTurn() // may still be running from GrimmdroidApp on the first launch after an upgrade
                    appSettings.pageTurn.first()
                }
        }
        fonts = fontStore.list(accountId) // a few file names, declared by the navigator created next
        supportFragmentManager.fragmentFactory =
            open?.let { navigatorFactory(it, vm.navigatorStart(it)) } ?: EpubNavigatorFragment.createDummyFactory()
        supportFragmentManager.registerFragmentLifecycleCallbacks(
            object : FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(
                    fm: FragmentManager,
                    f: Fragment,
                    v: View,
                    savedInstanceState: Bundle?,
                ) {
                    // A navigator restored after process death holds a dummy book (and no publication here yet) until the replace below.
                    if (f is EpubNavigatorFragment && publication != null) bind(f)
                }
            },
            false,
        )
        publication = open
        super.onCreate(savedInstanceState)
        val file = intent.getStringExtra(EXTRA_FILE)!!
        container.appScope.launch { container.settings.setLastBook(accountId, intent.getLongExtra(EXTRA_BOOK_ID, -1), file) }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // The navigator pads its text by the cutout itself.
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        navigatorContainer = FragmentContainerView(this).apply { id = R.id.reader_navigator }
        ViewCompat.setOnApplyWindowInsetsListener(navigatorContainer) { v, insets ->
            // The navigator pads its text by the cutout; a kept status bar needs the rest of its height too.
            val top =
                if (!settings.keepSystemStatusBar) {
                    0
                } else {
                    insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars()).top -
                        insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top
                }
            // Room for the reader's own status bar below the text.
            val bottom = if (settings.statusBarShown) (STATUS_BAR_HEIGHT.value * resources.displayMetrics.density).roundToInt() else 0
            v.updatePadding(top = top.coerceAtLeast(0), bottom = bottom)
            insets
        }
        curl =
            TurnOverlay(
                this,
                navigatorContainer,
                lifecycleScope,
                jump = ::jump,
                awaitPainted = ::awaitPainted,
                onSettled = ::onTurnSettled,
                curlActive = ::curlActive,
                selecting = { selecting },
            )
        setContentView(
            FrameLayout(this).apply {
                addView(curl) // the navigator inside, under the Compose chrome
                addView(ComposeView(context).apply { setContent { Chrome() } })
            },
        )
        applySettings()
        lifecycleScope.launch {
            settingsStore.settings.collect {
                settings = it
                applySettings()
            }
        }
        lifecycleScope.launch {
            appSettings.migratePageTurn()
            appSettings.pageTurn.collect {
                pageTurn = it
                repicture()
            }
        }
        lifecycleScope.launch { appSettings.turnSpeedMs.collect { curl.speedMs = it } }
        lifecycleScope.launch {
            val madeCurrent = getString(R.string.position_made_current)
            vm.madeCurrent.collect { Toast.makeText(this@ReaderActivity, madeCurrent, Toast.LENGTH_SHORT).show() }
        }
        lifecycleScope.launch {
            (application as GrimmdroidApp)
                .container.connectivity.online
                .first { it }
            fontStore.syncServer(accountId)
            refreshFonts()
        }
        if (open != null) return
        lifecycleScope.launch {
            val pub =
                vm.publication.await().getOrElse {
                    Log.w(TAG, "Cannot open the book", it)
                    Toast.makeText(this@ReaderActivity, it.message, Toast.LENGTH_LONG).show()
                    return@launch finish()
                }
            vm.initialLocator.await() // navigatorStart needs the saved place
            settings = settingsStore.settings.first()
            // A commit after onSaveInstanceState throws: wait until the activity is visible again.
            withStarted {
                publication = pub
                restartNavigator(pub)
            }
        }
    }

    /** A new navigator at the view model's place (the one recorded last, else the saved one, else the start). */
    private fun restartNavigator(pub: Publication) {
        curl.abort() // the turn's navigator is going away
        supportFragmentManager.fragmentFactory = navigatorFactory(pub, vm.navigatorStart(pub))
        supportFragmentManager.commit { replace(R.id.reader_navigator, EpubNavigatorFragment::class.java, null) }
    }

    private fun navigatorFactory(
        pub: Publication,
        initial: Locator?,
    ) = EpubNavigatorFactory(pub, readerNavigatorConfiguration(resources.configuration.fontScale))
        .also {
            declaredFonts = fonts
            navigatorReady = false
            columns = 0
        }.createFragmentFactory(
            initialLocator = initial,
            initialPreferences = settings.toEpubPreferences(systemDark),
            listener = navigatorListener,
            paginationListener = paginationListener,
            configuration = readerFragmentConfiguration(fonts),
        )

    /** Re-reads the custom fonts; when they changed, recreates the navigator at the current place so it declares them. */
    private suspend fun refreshFonts() {
        fonts = withContext(Dispatchers.IO) { fontStore.list(accountId) }
        // No factory yet: the first one is built with these fonts. A factory whose commit is still pending counts too.
        val pub = publication ?: return
        if (declaredFonts == null || declaredFonts == fonts) return
        withStarted {
            if (declaredFonts == fonts) return@withStarted // another refresh got here first
            restartNavigator(pub)
        }
    }

    private fun deleteFont(font: ReaderFont) {
        if (!fontStore.delete(font)) return
        if (settings.fontFamily == font.family) updateSettings { it.copy(fontFamily = null) }
        lifecycleScope.launch { refreshFonts() }
    }

    private val navigator get() = supportFragmentManager.findFragmentById(R.id.reader_navigator) as? EpubNavigatorFragment

    private fun bind(navigator: EpubNavigatorFragment) {
        // Edge thirds turn pages (as Readium's DirectionalNavigationAdapter did, but through turnPage); the middle third toggles the chrome.
        navigator.addInputListener(
            object : InputListener {
                override fun onTap(event: TapEvent): Boolean {
                    if (navigator.overflow.value.scroll) return false
                    val width = navigator.publicationView.width
                    val right =
                        when {
                            event.point.x >= width * 2 / 3f -> true
                            event.point.x <= width / 3f -> false
                            else -> return false
                        }
                    return navigatorReady && turnPage(navigator, right != rtl(navigator), opensChrome = !inSystemGestureEdge(event.point.y))
                }

                override fun onKey(event: InputKeyEvent): Boolean {
                    if (event.type != InputKeyEvent.Type.Down || event.modifiers.isNotEmpty()) return false
                    val forward =
                        when (event.key) {
                            Key.ArrowUp -> false
                            Key.ArrowDown, Key.Space -> true
                            Key.ArrowLeft -> rtl(navigator)
                            Key.ArrowRight -> !rtl(navigator)
                            else -> return false
                        }
                    return navigatorReady && turnPage(navigator, forward)
                }
            },
        )
        navigator.addInputListener(
            object : InputListener {
                override fun onTap(event: TapEvent): Boolean {
                    // The navigator reports the system's edge swipe (which reveals the bars briefly) as a tap there.
                    if (!inSystemGestureEdge(event.point.y)) toggleChrome()
                    return true
                }
            },
        )
        val owner = navigator.viewLifecycleOwner
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    navigator.currentLocator.collect { current ->
                        // Mid-turn, Readium is already on the target page while the user still sees the old one: onTurnSettled reports it.
                        if (curl.turning) return@collect
                        val settled = current == handledOnSettle
                        handledOnSettle = null
                        if (settled) return@collect
                        showLocator(navigator, current)
                        repicture() // a jump from the table of contents, a bookmark, a search, or a turn without the curl
                    }
                }
                launch {
                    vm.navigateTo.collect { target ->
                        curl.abort()
                        // The view model recorded the target: a new navigator starts there.
                        if (navigatorReady) navigator.go(target) else publication?.let(::restartNavigator)
                    }
                }
                launch {
                    navigator.settings.collect { repicture() } // after Readium's own reaction to new preferences
                }
            }
        }
    }

    /** The status line, reading pace, position sync and scrolled page span all follow the locator from here. */
    private suspend fun showLocator(
        navigator: EpubNavigatorFragment,
        current: Locator,
    ) {
        locator = current
        current.locations.position?.let(vm.pace::onPosition)
        vm.onLocatorChanged(current.withSelector(navigator.firstVisibleElementLocator()?.locations?.cssSelector))
        // Scrolled, a "page" is the viewport: Readium's progression is scrollY over the content height.
        if (scrolled) {
            page =
                navigator
                    .evaluateJavascript("window.innerHeight / document.scrollingElement.scrollHeight")
                    ?.toDoubleOrNull()
                    ?.let { PageSpan.of(current, it) }
        }
    }

    /**
     * Every turn ends here, turned or not. Cancelling a jump is best effort, so where the reader is comes from
     * Readium; it counts as a visit only when it isn't the page shown before the turn. The overlay redraws its own
     * pictures after a settle.
     */
    private fun onTurnSettled(turned: Boolean) {
        pendingPage?.let { page = it }
        pendingPage = null
        if (!turned && hitBookEdge && tapOpensChrome) toggleChrome()
        tapOpensChrome = false
        hitBookEdge = false
        val navigator = navigator ?: return
        val now = navigator.currentLocator.value
        if (now == locator) return
        handledOnSettle = now
        lifecycleScope.launch { showLocator(navigator, now) }
    }

    private fun rtl(navigator: EpubNavigatorFragment) = navigator.overflow.value.readingProgression == ReadingProgression.RTL

    private fun curlActive(): Boolean {
        if (!navigatorReady || pageTurn != PageTurn.CURL) return false
        val s = navigator?.settings?.value ?: return false
        return curlSupported(s.scroll, s.readingProgression == ReadingProgression.RTL, s.verticalText, columns)
    }

    /** The curl's jump: an instant turn, done once Readium reports the new place (or 1.5 s passed). False at the book's edge. */
    private suspend fun jump(forward: Boolean): Boolean {
        val navigator = navigator ?: return false
        val before = navigator.currentLocator.value
        if (!(if (forward) navigator.goForward(false) else navigator.goBackward(false))) {
            hitBookEdge = true
            return false
        }
        withTimeoutOrNull(1500) { navigator.currentLocator.first { it != before } }
        return true
    }

    /** Returns once the visible WebView has drawn its latest content and a frame has followed. */
    private suspend fun awaitPainted() {
        val web = visibleWebView(navigatorContainer) ?: return
        suspendCancellableCoroutine { done ->
            web.postVisualStateCallback(
                0,
                object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (done.isActive) done.resume(Unit)
                    }
                },
            )
        }
        awaitFrame()
    }

    /**
     * The page on screen changed without the curl (a jump, a reflow, a new chapter loaded): drops the curl's pictures,
     * and with the curl on, re-reads the column count and redraws them once the page has painted. Mid-turn the overlay
     * redraws them itself when the turn settles.
     */
    private fun repicture() {
        if (curl.turning) return
        curl.invalidatePictures()
        repicturing?.cancel()
        if (pageTurn != PageTurn.CURL) return
        repicturing =
            lifecycleScope.launch {
                awaitFrame() // let the navigator lay out and queue its own scripts first
                val navigator = navigator ?: return@launch
                columns = parseColumns(navigator.evaluateJavascript(COLUMNS_JS))
                if (!curlActive()) return@launch
                withTimeoutOrNull(1500) { awaitPainted() }
                curl.refreshPictures()
            }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::curl.isInitialized) repicture()
    }

    override fun onActionModeStarted(mode: ActionMode?) {
        super.onActionModeStarted(mode)
        selecting = true
    }

    override fun onActionModeFinished(mode: ActionMode?) {
        super.onActionModeFinished(mode)
        selecting = false
    }

    private fun inSystemGestureEdge(y: Float): Boolean {
        val decor = window.decorView
        val edges = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()) ?: return false
        return y < edges.top || y > decor.height - edges.bottom
    }

    private fun applySettings() {
        navigator?.submitPreferences(settings.toEpubPreferences(systemDark))
        // Behind a kept status bar, and behind the page while it loads.
        navigatorContainer.setBackgroundColor(settings.palette(systemDark).bg.toArgb())
        ViewCompat.requestApplyInsets(navigatorContainer)
        showSystemBars(chromeVisible)
        repicture()
    }

    private fun updateSettings(f: (ReaderSettings) -> ReaderSettings) {
        lifecycleScope.launch { settingsStore.update(f) }
    }

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        showSystemBars(chromeVisible)
    }

    private fun showSystemBars(show: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        val light = !settings.isDark(systemDark)
        controller.isAppearanceLightStatusBars = light
        controller.isAppearanceLightNavigationBars = light
        when {
            show -> {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }

            settings.keepSystemStatusBar -> {
                controller.show(WindowInsetsCompat.Type.statusBars())
                controller.hide(WindowInsetsCompat.Type.navigationBars())
            }

            else -> {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Chrome() {
        val jump by vm.jump.collectAsState()
        val bookmarks by vm.bookmarkList.collectAsState(emptyList())
        val pub = publication
        val s = settings
        val palette = s.palette(systemDark)
        ReaderColors(palette, s.isDark(systemDark)) {
            if (pub != null && s.statusBarShown) StatusLine(pub, s.statusItems, palette)
            ReaderChrome(
                visible = chromeVisible,
                title = pub?.metadata?.title.orEmpty(),
                locator = locator,
                toc = remember(pub) { pub?.tableOfContents?.flattenToc().orEmpty() },
                jump = jump,
                bookmarked = page?.let { span -> bookmarks.any { it.isOn(span) } } == true,
                bookmarks = bookmarks,
                onBack = ::finish,
                onToggleBookmark = {
                    lifecycleScope.launch {
                        val nav = navigator ?: return@launch
                        val at = locator ?: return@launch
                        val span = page ?: return@launch
                        // The title's text, and the element the CFI names.
                        val first = nav.firstVisibleElementLocator()
                        vm.toggleBookmark(at.withSelector(first?.locations?.cssSelector), span, first?.text)
                    }
                },
                onBookmarksOpened = vm::refreshBookmarks,
                onBookmark = { row -> pub?.locatorFor(row)?.let { navigator?.go(it) } },
                onRemoveBookmark = vm::removeBookmark,
                onSettings = { settingsOpen = true },
                onSyncNow = vm::syncNow,
                onMakeCurrent = vm::makeCurrent,
                onJump = vm::acceptJump,
                onDismissJump = vm::dismissJump,
                onSeek = { progression ->
                    lifecycleScope.launch { pub?.locateProgression(progression)?.let { navigator?.go(it) } }
                },
                onTocEntry = { navigator?.go(it) },
            )
            if (settingsOpen && pub != null) {
                val fontScale = LocalConfiguration.current.fontScale
                val factory = remember(pub, fontScale) { EpubNavigatorFactory(pub, readerNavigatorConfiguration(fontScale)) }
                ReaderSettingsSheet(
                    settings = s,
                    systemDark = systemDark,
                    editor = factory::createPreferencesEditor,
                    onUpdate = ::updateSettings,
                    onEpub = { edit ->
                        updateSettings { it.copy(epub = factory.createPreferencesEditor(it.epub).apply(edit).preferences) }
                    },
                    fonts = fonts,
                    onAddFont = { pickFont.launch(FONT_TYPES) },
                    onDeleteFont = ::deleteFont,
                    onDismiss = { settingsOpen = false },
                )
            }
            footnote?.let { FootnotePopup(it) { footnote = null } }
        }
    }

    /** The status bar at the bottom edge, under the chrome's bottom bar while that shows. */
    @androidx.compose.runtime.Composable
    private fun StatusLine(
        pub: Publication,
        items: List<StatusItem>,
        palette: Palette,
    ) {
        val sizes by produceState(emptyList<Int>(), pub) { value = pub.positionsByReadingOrder().map { it.size } }
        val now = if (StatusItem.CLOCK in items) minuteClock() else 0L
        val battery = if (StatusItem.BATTERY in items) batteryPercent() else null
        val at = locator
        val inputs =
            statusInputs(
                totalProgression = at?.locations?.totalProgression ?: 0.0,
                position = at?.locations?.position,
                resourceSizes = sizes,
                chapterTitle = at?.title,
                nowMillis = now,
                batteryPercent = battery,
                msPerPosition = vm.pace.msPerPosition, // changes only with the locator, which recomposes this
            )
        Box(Modifier.fillMaxSize()) { StatusBar(items, inputs, palette, Modifier.align(Alignment.BottomCenter)) }
    }

    // Volume down turns forward, up back; the reader keeps the keys, so the volume stays put. A held key turns once.
    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        val forward = volumeForward(keyCode) ?: return super.onKeyDown(keyCode, event)
        val navigator = navigator ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0 && navigatorReady) turnPage(navigator, forward)
        return true
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = (volumeForward(keyCode) != null && navigator != null) || super.onKeyUp(keyCode, event)

    private fun volumeForward(keyCode: Int) =
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> true
            KeyEvent.KEYCODE_VOLUME_UP -> false
            else -> null
        }

    /** A turn from a key or an edge tap: a curl when it's on and fits the book, else Readium's own. False when nothing turned. */
    private fun turnPage(
        navigator: EpubNavigatorFragment,
        forward: Boolean,
        opensChrome: Boolean = false,
    ): Boolean {
        if (curl.turning) return true // a held key, or a tap during a turn, turns once
        if (curlActive()) {
            when (curl.turn(forward)) {
                TurnResult.STARTED -> {
                    tapOpensChrome = opensChrome
                    hitBookEdge = false
                    return true
                }

                TurnResult.BUSY -> {
                    return true
                }

                TurnResult.UNSUPPORTED -> {
                    Unit
                }
            }
        }
        val animated = pageTurn != PageTurn.NONE // Curl falls back to Slide

        fun go() = if (forward) navigator.goForward(animated) else navigator.goBackward(animated)
        if (!scrolled) return go()
        // Scrolled, goForward jumps a whole chapter: scroll a screen, and change chapter only at its end.
        lifecycleScope.launch {
            val moved =
                navigator.evaluateJavascript(
                    "(() => { const y = scrollY; scrollBy(0, ${if (forward) "" else "-"}innerHeight); return scrollY !== y })()",
                )
            if (moved != "true") go()
        }
        return true
    }

    override fun onPause() {
        if (::curl.isInitialized) curl.onPause() // ends a running turn where the reader is
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::curl.isInitialized) curl.onResume()
    }

    override fun onStop() {
        vm.onStop()
        // Left with Back (or the book failed to open): "Last place" returns to the tabs, not this book.
        if (isFinishing) container.appScope.launch { container.settings.setReaderClosed() }
        super.onStop()
    }

    companion object {
        private const val TAG = "ReaderActivity"
        private const val EXTRA_ACCOUNT_ID = "accountId"
        private const val EXTRA_BOOK_ID = "bookId"
        private const val EXTRA_FILE = "file"

        // Columns on screen. Readium CSS may leave column-count auto and lay out by column-width: then the count is
        // CSS multicol's own, max(1, floor((width + gap) / (column-width + gap))); no column-width means one column.
        private const val COLUMNS_JS =
            "(() => { const cs = getComputedStyle(document.documentElement);" +
                " if (cs.columnCount !== 'auto') return cs.columnCount;" +
                " const w = parseFloat(cs.columnWidth), g = parseFloat(cs.columnGap) || 0;" +
                " return String(w > 0 ? Math.max(1, Math.floor((innerWidth + g) / (w + g))) : 1) })()"

        // What the picker offers; Fonts.import still checks the file's signature.
        private val FONT_TYPES =
            arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/vnd.ms-opentype", "application/octet-stream")

        fun intent(
            context: Context,
            accountId: Long,
            bookId: Long,
            file: File,
        ): Intent =
            Intent(context, ReaderActivity::class.java)
                .putExtra(EXTRA_ACCOUNT_ID, accountId)
                .putExtra(EXTRA_BOOK_ID, bookId)
                .putExtra(EXTRA_FILE, file.path)
    }
}

/** Whether a page curl fits the layout: paginated, left to right, horizontal, one column ([columns] 0 = not known yet). */
internal fun curlSupported(
    scroll: Boolean,
    rtl: Boolean,
    verticalText: Boolean,
    columns: Int,
): Boolean = !scroll && !rtl && !verticalText && columns == 1

/** The column count the reader's columns script returned (a JSON string from the WebView); 0 when unknown. */
internal fun parseColumns(result: String?): Int =
    result
        ?.trim('"')
        ?.toIntOrNull()
        ?.takeIf { it > 0 } ?: 0

private val EXTERNAL_SCHEMES = setOf("http", "https", "mailto")

/** A book's link the reader hands to other apps: a file: link would crash, other schemes launch any app's deep link. */
internal fun opensOutside(url: String): Boolean = url.substringBefore(':', "").lowercase() in EXTERNAL_SCHEMES

/** [this] with the first visible element's selector, so the upload's CFI names that element. */
private fun Locator.withSelector(selector: String?): Locator =
    if (selector == null) this else copyWithLocations(otherLocations = locations.otherLocations + ("cssSelector" to selector))
