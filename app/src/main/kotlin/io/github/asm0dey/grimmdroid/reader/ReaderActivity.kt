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
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.readium.r2.navigator.HyperlinkNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.html.cssSelector
import org.readium.r2.shared.publication.services.locateProgression
import org.readium.r2.shared.publication.services.positionsByReadingOrder
import org.readium.r2.shared.util.AbsoluteUrl
import java.io.File
import kotlin.math.roundToInt

class ReaderActivity : FragmentActivity() {
    private val vm: ReaderViewModel by viewModels {
        val container = (application as GrimmdroidApp).container
        val accountId = intent.getLongExtra(EXTRA_ACCOUNT_ID, -1)
        val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1)
        val file = File(intent.getStringExtra(EXTRA_FILE).orEmpty())
        val fontsDirs = listOf(filesDir.resolve("fonts/$accountId"), filesDir.resolve("fonts/local"))
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

    private val settingsStore get() = (application as GrimmdroidApp).container.readerSettings
    private var settings by mutableStateOf(ReaderSettings())
    private var settingsOpen by mutableStateOf(false)
    private var footnote by mutableStateOf<String?>(null)
    private lateinit var navigatorContainer: FragmentContainerView

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
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url.toString().toUri()))
                } catch (_: ActivityNotFoundException) {
                    // No app to open the link with: the tap does nothing.
                }
            }
        }

    private var chromeVisible by mutableStateOf(false)
    private var locator by mutableStateOf<Locator?>(null)
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
        }
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
        setContentView(
            FrameLayout(this).apply {
                addView(navigatorContainer)
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
            val madeCurrent = getString(R.string.position_made_current)
            vm.madeCurrent.collect { Toast.makeText(this@ReaderActivity, madeCurrent, Toast.LENGTH_SHORT).show() }
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
                supportFragmentManager.fragmentFactory = navigatorFactory(pub, vm.navigatorStart(pub))
                supportFragmentManager.commit { replace(R.id.reader_navigator, EpubNavigatorFragment::class.java, null) }
            }
        }
    }

    private fun navigatorFactory(
        pub: Publication,
        initial: Locator?,
    ) = EpubNavigatorFactory(pub, readerNavigatorConfiguration(resources.configuration.fontScale)).createFragmentFactory(
        initialLocator = initial,
        initialPreferences = settings.toEpubPreferences(systemDark),
        listener = navigatorListener,
    )

    private val navigator get() = supportFragmentManager.findFragmentById(R.id.reader_navigator) as? EpubNavigatorFragment

    private fun bind(navigator: EpubNavigatorFragment) {
        // Edge taps turn pages; the middle third toggles the chrome.
        navigator.addInputListener(DirectionalNavigationAdapter(navigator, horizontalEdgeThresholdPercent = 1.0 / 3))
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
                        locator = current
                        current.locations.position?.let(vm.pace::onPosition)
                        vm.onLocatorChanged(current.withSelector(navigator.firstVisibleElementLocator()?.locations?.cssSelector))
                    }
                }
                launch { vm.navigateTo.collect { navigator.go(it) } }
            }
        }
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
                onBack = ::finish,
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

    override fun onStop() {
        vm.onStop()
        super.onStop()
    }

    companion object {
        private const val TAG = "ReaderActivity"
        private const val EXTRA_ACCOUNT_ID = "accountId"
        private const val EXTRA_BOOK_ID = "bookId"
        private const val EXTRA_FILE = "file"

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

/** [this] with the first visible element's selector, so the upload's CFI names that element. */
private fun Locator.withSelector(selector: String?): Locator =
    if (selector == null) this else copyWithLocations(otherLocations = locations.otherLocations + ("cssSelector" to selector))
