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

import android.app.Application
import android.webkit.WebView
import coil3.PlatformContext
import coil3.SingletonImageLoader
import io.github.asm0dey.grimmdroid.download.DownloadHost
import io.github.asm0dey.grimmdroid.reader.PositionUploadHost
import io.github.asm0dey.grimmdroid.sync.SyncHost
import kotlinx.coroutines.launch

class GrimmdroidApp :
    Application(),
    SyncHost,
    DownloadHost,
    PositionUploadHost,
    SingletonImageLoader.Factory {
    // Lazy: Robolectric tests start this Application without touching the container.
    val container by lazy { AppContainer(this) }

    override fun onCreate() {
        // Before any WebView exists: lets page pictures draw any page of a chapter, not only the visible one.
        WebView.enableSlowWholeDocumentDraw()
        super.onCreate()
        container.appScope.launch { container.settings.migratePageTurn() }
    }

    override val librarySync get() = container.librarySync
    override val downloadRepository get() = container.downloadRepository
    override val afterDownload get() = container.afterDownload
    override val positionSync get() = container.positionSync
    override val bookmarks get() = container.bookmarks
    override val readerDao get() = container.db.readerDao()
    override val accounts get() = container.accounts

    override fun newImageLoader(context: PlatformContext) = container.imageLoader
}
