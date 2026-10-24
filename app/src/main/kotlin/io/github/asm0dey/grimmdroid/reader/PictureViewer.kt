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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.asm0dey.grimmdroid.R
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.saket.telephoto.ExperimentalTelephotoApi
import me.saket.telephoto.flick.FlickToDismiss
import me.saket.telephoto.flick.FlickToDismissState
import me.saket.telephoto.flick.rememberFlickToDismissState
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url

/** A picture of the book, read from the book file, and its alt text. */
class Picture(
    val bytes: ByteArray,
    val alt: String?,
)

/** A book's picture over the page, on black: pinch or double-tap to zoom, drag to pan; Back or a flick down closes it. */
@OptIn(ExperimentalTelephotoApi::class)
@Composable
fun PictureViewer(
    picture: Picture,
    onDismiss: () -> Unit,
) {
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val flick = rememberFlickToDismissState()
        if (flick.gestureState is FlickToDismissState.GestureState.Dismissed) LaunchedEffect(Unit) { onDismiss() }
        // The page shows through as the picture is flicked away.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1 - flick.offsetFraction))) {
            FlickToDismiss(flick) {
                ZoomableAsyncImage(model = picture.bytes, contentDescription = picture.alt, modifier = Modifier.fillMaxSize())
            }
            var hint by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                delay(HINT_MS)
                hint = false
            }
            AnimatedVisibility(
                hint && flick.offsetFraction == 0f,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Text(
                    stringResource(R.string.picture_flick_hint),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// How long the "drag down to close" hint shows after the picture opens.
private const val HINT_MS = 2500L

/**
 * The picture at ([x], [y]), fractions of the page's width and height: an `<img>` or an SVG `<image>`. Returns
 * `{src, alt}` or null.
 */
internal fun pictureAtJs(
    x: Float,
    y: Float,
): String =
    "(() => { const e = document.elementsFromPoint(innerWidth * $x, innerHeight * $y)" +
        ".find(e => e instanceof HTMLImageElement || e instanceof SVGImageElement);" +
        " if (!e) return null;" +
        " const src = e instanceof HTMLImageElement ? e.currentSrc || e.src : new URL(e.href.baseVal, document.baseURI).href;" +
        " return { src, alt: e.getAttribute('alt') } })()"

@Serializable
private class PictureHit(
    val src: String,
    val alt: String? = null,
)

private val json = Json { ignoreUnknownKeys = true }

// Readium serves the book's own files from this host (WebViewServer.PACKAGE_HOSTNAME, internal there).
private val packageBase = AbsoluteUrl("https://readium_package/")!!

/** The book path and alt text of [pictureAtJs]'s result; null for no picture, or one from outside the book. */
internal fun parsePictureHit(result: String?): Pair<Url, String?>? {
    if (result == null || result == "null") return null
    val hit = runCatching { json.decodeFromString<PictureHit>(result) }.getOrNull() ?: return null
    val url = AbsoluteUrl(hit.src)?.takeIf { it.host == packageBase.host } ?: return null
    return packageBase.relativize(url) to hit.alt?.takeIf { it.isNotBlank() }
}
