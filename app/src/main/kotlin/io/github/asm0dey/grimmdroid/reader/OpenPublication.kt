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
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.ErrorException
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

/** Opens a local book, with the files in [fontsDirs] reachable through [fontUrl]. */
suspend fun Context.openPublication(
    file: File,
    fontsDirs: List<File>,
): Result<Publication> {
    val httpClient = DefaultHttpClient()
    val assetRetriever = AssetRetriever(contentResolver, httpClient)
    val opener = PublicationOpener(DefaultPublicationParser(this, httpClient, assetRetriever, pdfFactory = null))
    val asset = assetRetriever.retrieve(file).getOrElse { return Result.failure(ErrorException(it)) }
    return opener
        // Passed to open(): in 3.4.0 the constructor's onCreatePublication is shadowed and never runs.
        .open(asset, allowUserInteraction = false, onCreatePublication = {
            container = FontServingContainer(container, fontsDirs)
        })
        .getOrElse {
            asset.close()
            return Result.failure(ErrorException(it))
        }.let { Result.success(it) }
}
