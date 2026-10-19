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
package io.github.asm0dey.grimmdroid.ui.details

import io.github.asm0dey.grimmdroid.catalog.BookEntity
import io.github.asm0dey.grimmdroid.catalog.DownloadEntity
import io.github.asm0dey.grimmdroid.catalog.DownloadState
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.Locale

private fun book(
    audiobook: Boolean = false,
    type: String = "EPUB",
) = BookEntity(1, 7, "Moby Dick", "Herman Melville", null, null, null, null, null, null, null, type, null, null, null, audiobook, 1)

private fun dl(
    state: DownloadState,
    path: String = "",
    size: Long = 1_200_000,
) = DownloadEntity(1, 7, 0, "moby.epub", path, size, state, 0.4f)

private data class Case(
    val name: String,
    val audiobook: Boolean = false,
    val canDownload: Boolean = true,
    val isAdmin: Boolean = false,
    val download: DownloadEntity? = null,
    val fileExists: Boolean = false,
    val online: Boolean = true,
    val type: String = "EPUB",
    val expected: (File) -> PrimaryAction,
) {
    override fun toString() = name
}

class DetailsStateSpec :
    FunSpec({
        val existing = File.createTempFile("moby", ".epub").apply { deleteOnExit() }
        val missing = File(existing.parentFile, "gone-${System.nanoTime()}.epub")

        context("primaryAction") {
            withData(
                Case("audiobook wins over a missing permission", audiobook = true, canDownload = false) { PrimaryAction.Audiobook },
                Case("audiobook", audiobook = true) { PrimaryAction.Audiobook },
                Case("no permission", canDownload = false) { PrimaryAction.NoPermission },
                Case("no permission even offline", canDownload = false, online = false) { PrimaryAction.NoPermission },
                Case(
                    "admin without canDownload may download",
                    canDownload = false,
                    isAdmin = true,
                ) { PrimaryAction.Download(readable = true) },
                Case(
                    "done and on disk",
                    download = dl(DownloadState.DONE),
                    fileExists = true,
                ) { PrimaryAction.Downloaded(it, sizeLabel(1_200_000), readable = true) },
                Case("done offline is still downloaded", download = dl(DownloadState.DONE), fileExists = true, online = false) {
                    PrimaryAction.Downloaded(it, sizeLabel(1_200_000), readable = true)
                },
                Case(
                    "done but deleted from disk",
                    download = dl(DownloadState.DONE),
                    fileExists = false,
                ) { PrimaryAction.Download(readable = true) },
                Case("queued", download = dl(DownloadState.QUEUED)) { PrimaryAction.Downloading(0.4f) },
                Case("running", download = dl(DownloadState.RUNNING)) { PrimaryAction.Downloading(0.4f) },
                Case(
                    "running offline keeps progress",
                    download = dl(DownloadState.RUNNING),
                    online = false,
                ) { PrimaryAction.Downloading(0.4f) },
                Case("offline", online = false) { PrimaryAction.Offline },
                Case("failed offline", download = dl(DownloadState.FAILED), online = false) { PrimaryAction.Offline },
                Case("failed retries", download = dl(DownloadState.FAILED)) { PrimaryAction.Download(readable = true) },
                Case("not downloaded") { PrimaryAction.Download(readable = true) },
                Case("epub type is matched case-insensitively", type = "epub") { PrimaryAction.Download(readable = true) },
                Case("a pdf is not readable", type = "PDF") { PrimaryAction.Download(readable = false) },
            ) { c ->
                val file = if (c.fileExists) existing else missing
                val d = c.download?.copy(path = file.path)
                primaryAction(book(c.audiobook, c.type), c.canDownload, c.isAdmin, d, d?.let { File(it.path) }, c.online) shouldBe
                    c.expected(file)
            }
        }

        test("a downloaded pdf is not readable") {
            val pdf = File.createTempFile("moby", ".pdf").apply { deleteOnExit() }
            val d = dl(DownloadState.DONE, pdf.path)
            primaryAction(book(type = "PDF"), true, false, d, pdf, true) shouldBe PrimaryAction.Downloaded(pdf, sizeLabel(1_200_000), false)
        }

        test("sizeLabel uses decimal units with one decimal") {
            sizeLabel(0, Locale.US) shouldBe "0 B"
            sizeLabel(999, Locale.US) shouldBe "999 B"
            sizeLabel(1_200_000, Locale.US) shouldBe "1.2 MB"
            sizeLabel(15_300, Locale.US) shouldBe "15.3 kB"
            sizeLabel(2_000_000_000, Locale.US) shouldBe "2.0 GB"
        }
    })
