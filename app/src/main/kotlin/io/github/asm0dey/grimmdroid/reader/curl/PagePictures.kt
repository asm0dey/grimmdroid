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
package io.github.asm0dey.grimmdroid.reader.curl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.core.view.children
import androidx.viewpager.widget.ViewPager

/** Which page a picture shows, relative to the one on screen. */
enum class Side { PREVIOUS, CURRENT, NEXT }

/**
 * Pictures of the current page and its two neighbours, drawn from the Readium navigator under
 * [container] so a page curl can start without waiting for Readium to paint.
 *
 * Main thread only: it draws views.
 *
 * Bitmaps returned by [get] belong to this class. They stay valid until the next [refresh] or
 * [invalidate], which may draw over them (same size) or drop them; they are never recycled.
 *
 * Relies on `WebView.enableSlowWholeDocumentDraw()` (see `GrimmdroidApp.onCreate`): with it, a direct
 * `WebView.draw` renders the chapter from its origin and ignores scrollX, so page i is a translation.
 */
class PagePictures(
    private val container: View,
) {
    private val pictures = arrayOfNulls<Bitmap>(Side.entries.size)

    // Reused across refreshes while sizes stay the same.
    private val buffers = arrayOfNulls<Bitmap>(Side.entries.size)
    private var scratch: Bitmap? = null

    /** Bumped by every [refresh] and [invalidate]. */
    var generation = 0
        private set

    /** The picture for [side], or null when it is missing (no such page, not painted, blank). */
    operator fun get(side: Side): Bitmap? = pictures[side.ordinal]

    /** Drops all pictures. */
    fun invalidate() {
        pictures.fill(null)
        generation++
    }

    /** Redraws all three pictures. Call after the navigator has laid out. */
    fun refresh() {
        invalidate()
        if (container.width <= 0 || container.height <= 0) return
        val web = visibleWebView(container) ?: return
        if (web.width <= 0) return
        val page = pageIndex(web.scrollX, web.width)
        val pages = pageCount(web)
        draw(Side.CURRENT, web, page, 0)
        if (page + 1 < pages) draw(Side.NEXT, web, page + 1, 0) else neighbour(+1)?.let { draw(Side.NEXT, it, 0, +1) }
        if (page > 0) {
            draw(Side.PREVIOUS, web, page - 1, 0)
        } else {
            neighbour(-1)?.let { draw(Side.PREVIOUS, it, pageCount(it) - 1, -1) }
        }
    }

    /** The WebView of the chapter laid out one pager width to the [direction] of the visible one. */
    private fun neighbour(direction: Int): WebView? {
        val pager = descendants(container).filterIsInstance<ViewPager>().firstOrNull() ?: return null
        val child = pager.children.firstOrNull { it.left == pager.scrollX + direction * pager.width } ?: return null
        return webViews(child).firstOrNull()?.takeIf { it.width > 0 }
    }

    /** Draws page [page] of [web], placed as if its chapter were [chapterShift] pager widths onto the screen. */
    private fun draw(
        side: Side,
        web: WebView,
        page: Int,
        chapterShift: Int,
    ) {
        if (page < 0) return
        val shot = reuse(scratch, web.width, web.height).also { scratch = it }
        shot.eraseColor(Color.TRANSPARENT)
        Canvas(shot).run {
            translate(-page * web.width.toFloat(), 0f)
            web.draw(this)
        }
        if (shot.isBlank()) return

        val picture = reuse(buffers[side.ordinal], container.width, container.height).also { buffers[side.ordinal] = it }
        picture.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(picture)
        container.background?.let { background ->
            val bounds = background.copyBounds()
            background.setBounds(0, 0, container.width, container.height)
            background.draw(canvas)
            background.bounds = bounds
        }
        val webAt = IntArray(2).also(web::getLocationInWindow)
        val containerAt = IntArray(2).also(container::getLocationInWindow)
        val pagerWidth = descendants(container).filterIsInstance<ViewPager>().firstOrNull()?.width ?: 0
        val x = webAt[0] - containerAt[0] - chapterShift * pagerWidth
        canvas.drawBitmap(shot, x.toFloat(), (webAt[1] - containerAt[1]).toFloat(), null)
        pictures[side.ordinal] = picture
    }

    private fun reuse(
        bitmap: Bitmap?,
        width: Int,
        height: Int,
    ): Bitmap =
        bitmap?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private fun pageCount(web: WebView): Int = pageCount(computeHorizontalScrollRange.invoke(web) as Int, web.width)

    private companion object {
        // shortcut: protected View API, reached by reflection; R2WebView's own numPages is internal to Readium.
        val computeHorizontalScrollRange =
            View::class.java.getDeclaredMethod("computeHorizontalScrollRange").apply { isAccessible = true }
    }
}

private fun descendants(view: View): Sequence<View> =
    sequence {
        yield(view)
        if (view is ViewGroup) view.children.forEach { yieldAll(descendants(it)) }
    }

private fun webViews(view: View) = descendants(view).filterIsInstance<WebView>()

/** The navigator's WebView on screen under [container], or null while none is. */
internal fun visibleWebView(container: View): WebView? = webViews(container).firstOrNull { it.getGlobalVisibleRect(Rect()) }

/** The page whose scroll offset is nearest [scrollX]. */
internal fun pageIndex(
    scrollX: Int,
    pageWidth: Int,
): Int = (scrollX + pageWidth / 2) / pageWidth

/** Pages in a chapter [range] pixels wide, rounded like Readium's `R2WebView.numPages`: the width isn't always a multiple of a page. */
internal fun pageCount(
    range: Int,
    width: Int,
): Int = (range + width / 2) / width

/** True when every pixel sampled on a 16×16 grid equals the first: nothing was painted. */
internal fun Bitmap.isBlank(): Boolean {
    val first = getPixel(0, 0)
    for (i in 0 until GRID) {
        for (j in 0 until GRID) {
            if (getPixel(i * width / GRID, j * height / GRID) != first) return false
        }
    }
    return true
}

private const val GRID = 16
