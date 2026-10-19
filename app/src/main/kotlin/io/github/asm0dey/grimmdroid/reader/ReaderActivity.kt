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

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.html.cssSelector
import org.readium.r2.shared.publication.services.locateProgression
import java.io.File

// Task 9's reader setting; off by default.
private const val KEEP_STATUS_BAR = false

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

    private var chromeVisible by mutableStateOf(false)
    private var locator by mutableStateOf<Locator?>(null)
    private var publication by mutableStateOf<Publication?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        if (intent.getStringExtra(EXTRA_FILE) == null) {
            super.onCreate(savedInstanceState)
            return finish()
        }
        // The ViewModel keeps the book open across rotation: the restored navigator gets it right away.
        val open =
            vm.publication
                .takeIf { it.isCompleted }
                ?.getCompleted()
                ?.getOrNull()
        supportFragmentManager.fragmentFactory =
            open?.let { navigatorFactory(it, vm.navigatorStart(it, null)) } ?: EpubNavigatorFragment.createDummyFactory()
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
        setContentView(
            FrameLayout(this).apply {
                addView(FragmentContainerView(context).apply { id = R.id.reader_navigator })
                addView(ComposeView(context).apply { setContent { Chrome() } })
            },
        )
        showSystemBars(false)
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
            val saved = vm.initialLocator.await()
            // A commit after onSaveInstanceState throws: wait until the activity is visible again.
            withStarted {
                publication = pub
                supportFragmentManager.fragmentFactory = navigatorFactory(pub, vm.navigatorStart(pub, saved))
                supportFragmentManager.commit { replace(R.id.reader_navigator, EpubNavigatorFragment::class.java, null) }
            }
        }
    }

    private fun navigatorFactory(
        pub: Publication,
        initial: Locator?,
    ) = EpubNavigatorFactory(pub).createFragmentFactory(initialLocator = initial)

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

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        showSystemBars(chromeVisible)
    }

    private fun showSystemBars(show: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // Dark icons over the light Default theme; Task 9 derives this from the reader theme.
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true
        when {
            show -> controller.show(WindowInsetsCompat.Type.systemBars())
            KEEP_STATUS_BAR -> controller.hide(WindowInsetsCompat.Type.navigationBars())
            else -> controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    @androidx.compose.runtime.Composable
    private fun Chrome() {
        val jump by vm.jump.collectAsState()
        val pub = publication
        ReaderChrome(
            visible = chromeVisible,
            title = pub?.metadata?.title.orEmpty(),
            locator = locator,
            toc = androidx.compose.runtime.remember(pub) { pub?.tableOfContents?.flattenToc().orEmpty() },
            jump = jump,
            onBack = ::finish,
            onSyncNow = vm::syncNow,
            onMakeCurrent = vm::makeCurrent,
            onJump = vm::acceptJump,
            onDismissJump = vm::dismissJump,
            onSeek = { progression ->
                lifecycleScope.launch { pub?.locateProgression(progression)?.let { navigator?.go(it) } }
            },
            onTocEntry = { navigator?.go(it) },
        )
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
