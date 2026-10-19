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

import android.net.Uri
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.file.FileResource
import org.readium.r2.shared.util.resource.Resource
import java.io.File

private const val FONT_PATH_PREFIX = "/grimmdroid-fonts/"

/** Font files are served under the publication host so the navigator's WebView can load them. */
const val FONT_URL_PREFIX = "https://readium_package$FONT_PATH_PREFIX"

fun fontUrl(file: File): AbsoluteUrl = AbsoluteUrl(FONT_URL_PREFIX + Uri.encode(file.name))!!

/** Serves [FONT_URL_PREFIX] from the first of [dirs] holding that file name; everything else comes from [inner]. */
class FontServingContainer(
    private val inner: Container<Resource>,
    private val dirs: List<File>,
) : Container<Resource> {
    override val sourceUrl: AbsoluteUrl? get() = inner.sourceUrl
    override val entries: Set<Url> get() = inner.entries

    override fun close() = inner.close()

    override fun get(url: Url): Resource? {
        if (url !is AbsoluteUrl || url.host != "readium_package") return inner[url]
        val path = url.path ?: return inner[url]
        if (!path.startsWith(FONT_PATH_PREFIX)) return inner[url]
        val name = path.removePrefix(FONT_PATH_PREFIX)
        // Plain file names only: no traversal out of the font directories.
        if (name.isEmpty() || '/' in name || '\\' in name || name == "." || name == "..") return null
        return dirs.map { it.resolve(name) }.firstOrNull { it.isFile }?.let(::FileResource)
    }
}
