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
import io.github.asm0dey.grimmdroid.download.DownloadHost
import io.github.asm0dey.grimmdroid.sync.SyncHost

class GrimmdroidApp :
    Application(),
    SyncHost,
    DownloadHost {
    // Lazy: Robolectric tests start this Application without touching the container.
    val container by lazy { AppContainer(this) }

    override val librarySync get() = container.librarySync
    override val downloadRepository get() = container.downloadRepository
    override val afterDownload get() = container.afterDownload
}
