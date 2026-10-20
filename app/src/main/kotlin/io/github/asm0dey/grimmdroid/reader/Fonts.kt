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
import android.net.Uri
import android.provider.OpenableColumns
import io.github.asm0dey.grimmdroid.api.GrimmoryApi
import io.github.asm0dey.grimmdroid.api.callServer
import io.github.asm0dey.grimmdroid.api.valueOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** A font file the reader can declare; [family] is the file name without its extension. */
data class ReaderFont(
    val family: String,
    val file: File,
    val fromServer: Boolean,
)

/** Grimmory's font formats and the extension the file is kept under. */
private val EXTENSIONS = mapOf("TTF" to "ttf", "OTF" to "otf", "WOFF" to "woff", "WOFF2" to "woff2")

/** First four bytes of a TrueType font, OpenType (CFF) font, Apple TrueType font and TrueType collection. */
private val SIGNATURES = listOf(byteArrayOf(0, 1, 0, 0), "OTTO".toByteArray(), "true".toByteArray(), "ttcf".toByteArray())

private const val MAX_IMPORT_BYTES = 50L * 1024 * 1024

/** A server-given or picked file name made safe as a plain file name and a CSS family name. */
private fun sanitize(name: String): String = name.trim().replace(Regex("[^\\p{L}\\p{N} _-]"), "_").ifBlank { "font" }

/**
 * Custom fonts: each account's Grimmory fonts in `fonts/<accountId>/`, fonts imported from files (for every account)
 * in `fonts/local/`. [api] is null for a missing or signed-out account, which then makes no requests.
 */
class Fonts(
    filesDir: File,
    private val api: suspend (accountId: Long) -> GrimmoryApi?,
) {
    private val root = filesDir.resolve("fonts")
    private val local = root.resolve("local")

    private fun serverDir(accountId: Long) = root.resolve("$accountId")

    /** Where [FontServingContainer] looks, server fonts first. */
    fun dirs(accountId: Long): List<File> = listOf(serverDir(accountId), local)

    /** The fonts on the phone for [accountId]; a family in both directories is the server's. */
    fun list(accountId: Long): List<ReaderFont> =
        dirs(accountId)
            .flatMapIndexed { i, dir ->
                dir
                    .listFiles { f -> f.isFile && f.extension in EXTENSIONS.values }
                    .orEmpty()
                    .sortedBy { it.name }
                    .map { ReaderFont(it.nameWithoutExtension, it, fromServer = i == 0) }
            }.distinctBy { it.family }

    /** Downloads the account's server fonts not yet here and deletes the ones gone from the server; a failed list keeps all. */
    suspend fun syncServer(accountId: Long) {
        val api = api(accountId) ?: return
        val fonts = callServer { api.customFonts() }.valueOrNull() ?: return
        val wanted =
            fonts
                .mapNotNull { dto ->
                    EXTENSIONS[dto.format.uppercase()]?.let { "${sanitize(dto.fontName)}.$it" to dto.id }
                }.toMap()
        val dir = serverDir(accountId)
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            dir
                .listFiles()
                .orEmpty()
                .filter { it.name !in wanted }
                .forEach { it.delete() }
        }
        wanted.filterKeys { !dir.resolve(it).exists() }.forEach { (name, id) ->
            callServer { api.downloadFont(id, dir.resolve(name)) } // a failure is tried again on the next sync
        }
    }

    /** Copies a TrueType/OpenType font of at most 50 MB picked by the user into the imported fonts; anything else fails. */
    suspend fun import(
        context: Context,
        uri: Uri,
    ): Result<ReaderFont> =
        withContext(Dispatchers.IO) {
            try {
                val name = displayName(context, uri)?.substringBeforeLast('.') ?: "font"
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
                local.mkdirs()
                val tmp = File.createTempFile("import", ".part", local)
                try {
                    val header = DataInputStream(input).use { copyFont(it, tmp) }
                    val file = local.resolve("${sanitize(name)}.${if (header.contentEquals(SIGNATURES[1])) "otf" else "ttf"}")
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    Result.success(ReaderFont(file.nameWithoutExtension, file, fromServer = false))
                } finally {
                    Files.deleteIfExists(tmp.toPath())
                }
            } catch (e: IOException) {
                Result.failure(e)
            } catch (e: IllegalArgumentException) {
                Result.failure(e)
            } catch (e: SecurityException) {
                Result.failure(e)
            }
        }

    /**
     * Deletes an imported font and says whether it is gone; the server's are only removed by a sync, and nothing outside
     * `fonts/local/` is touched.
     */
    fun delete(font: ReaderFont): Boolean =
        // Only a file directly in the imported dir, whatever the caller's flags or path say.
        font.file.canonicalFile.parentFile == local.canonicalFile && font.file.delete()

    /** Copies a font from [input] to [tmp]; returns its 4 header bytes. Fails on anything but TrueType/OpenType up to 50 MB. */
    private fun copyFont(
        input: DataInputStream,
        tmp: File,
    ): ByteArray {
        val header = ByteArray(4)
        input.readFully(header) // shorter than 4 bytes: EOFException, not a font
        require(SIGNATURES.any { it.contentEquals(header) }) { "Not a TrueType or OpenType font" }
        tmp.outputStream().use { out ->
            out.write(header)
            // Counted while copying: a mis-picked multi-GB file must not fill the storage first.
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            var size = header.size.toLong()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                size += n
                require(size <= MAX_IMPORT_BYTES) { "Larger than 50 MB" }
                out.write(buf, 0, n)
            }
        }
        return header
    }

    private fun displayName(
        context: Context,
        uri: Uri,
    ): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment
}
