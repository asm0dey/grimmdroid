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
package io.github.asm0dey.grimmdroid

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import io.github.asm0dey.grimmdroid.auth.OidcRedirects
import io.github.asm0dey.grimmdroid.settings.ThemeMode
import io.github.asm0dey.grimmdroid.ui.nav.AppNav
import io.github.asm0dey.grimmdroid.ui.theme.GrimmdroidTheme
import io.github.asm0dey.grimmdroid.ui.theme.isDark
import io.github.asm0dey.grimmdroid.upload.IncomingBooks
import io.ktor.http.Url

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // On recreation the same Intent comes back; its redirect was already forwarded.
        if (savedInstanceState == null) {
            forwardOidcRedirect(intent)
            forwardIncomingBook(intent)
        }
        enableEdgeToEdge()
        val container = (application as GrimmdroidApp).container
        setContent {
            val mode by container.settings.theme.collectAsState(ThemeMode.SYSTEM)
            val dynamic by container.settings.dynamicColor.collectAsState(true)
            val dark = mode.isDark()
            // System-bar icons follow the app's theme, which can differ from the system's.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            GrimmdroidTheme(mode, dynamic) { AppNav(container) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forwardOidcRedirect(intent)
        forwardIncomingBook(intent)
    }

    private fun forwardIncomingBook(intent: Intent) {
        val uri =
            when (intent.action) {
                Intent.ACTION_VIEW -> intent.data?.takeIf { it.scheme == "content" }
                Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                else -> null
            } ?: return
        IncomingBooks.incoming.value = uri
    }

    private fun forwardOidcRedirect(intent: Intent) {
        val data = intent.data ?: return
        if (data.scheme != "grimmory" || data.host != "oauth2-callback") return
        // Any app can send this Intent; a malformed URI must not crash us.
        runCatching { Url(data.toString()) }.onSuccess { OidcRedirects.incoming.tryEmit(it) }
    }
}
