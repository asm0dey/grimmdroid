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
package io.github.asm0dey.grimmdroid.upload

import io.github.asm0dey.grimmdroid.api.LibraryDto
import io.github.asm0dey.grimmdroid.api.LibraryPathDto
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.io.File

private val oneLib = listOf(LibraryDto(3, "Books", watch = true, paths = listOf(LibraryPathDto(9, "/b"))))
private val twoLibs = oneLib + LibraryDto(4, "Comics", paths = listOf(LibraryPathDto(10, "/c"), LibraryPathDto(11, "/d")))

/** A fake world for the ViewModel: records what it saved, uploaded and opened. */
private class Fakes(
    val name: String = "b.epub",
    val libraries: List<LibraryDto> = oneLib,
    var saved: Pair<Long, Long>? = null,
    val result: UploadResult = UploadResult.Found(7),
    val openWith: suspend (Long) -> Unit = {},
) {
    val file: File = File.createTempFile("upload", ".epub").apply { deleteOnExit() }
    val uploads = mutableListOf<UploadTarget>()
    val opened = mutableListOf<Long>()

    fun vm() =
        UploadViewModel(
            copy = { file to name },
            libraries = { libraries },
            savedTarget = { saved },
            saveTarget = { saved = it },
            upload = { _, _, target, _ ->
                uploads += target
                result
            },
            open = {
                opened += it
                openWith(it)
            },
        )
}

@OptIn(ExperimentalCoroutinesApi::class)
class UploadViewModelSpec :
    FunSpec({
        beforeTest { Dispatchers.setMain(UnconfinedTestDispatcher()) }
        afterTest { Dispatchers.resetMain() }

        test("non-epub name → NotEpub and no upload") {
            val f = Fakes(name = "b.pdf")
            f.vm().state.value shouldBe UploadState.NotEpub
            f.uploads shouldBe emptyList()
        }

        test("single library with single path is used and remembered") {
            val f = Fakes()
            f.vm()
            f.uploads shouldBe listOf(UploadTarget(3, 9, watched = true))
            f.saved shouldBe (3L to 9L)
        }

        test("two options without a saved one → ChooseTarget; choose() saves and uploads") {
            val f = Fakes(libraries = twoLibs)
            val vm = f.vm()
            val options = (vm.state.value as UploadState.ChooseTarget).options
            options.map { it.label } shouldBe listOf("Books", "Comics — /c", "Comics — /d")
            vm.choose(options[2])
            f.saved shouldBe (4L to 11L)
            f.uploads shouldBe listOf(UploadTarget(4, 11, watched = false))
        }

        test("saved target that no longer exists → ChooseTarget") {
            val f = Fakes(libraries = twoLibs, saved = 5L to 99L)
            (f.vm().state.value is UploadState.ChooseTarget) shouldBe true
            f.uploads shouldBe emptyList()
        }

        test("saved target that still exists is used without asking") {
            val f = Fakes(libraries = twoLibs, saved = 4L to 10L)
            f.vm()
            f.uploads shouldBe listOf(UploadTarget(4, 10, watched = false))
        }

        test("Opening is shown while the book is fetched, Opened after") {
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val vm = Fakes(openWith = { gate.await() }).vm()
            vm.state.value shouldBe UploadState.Opening
            gate.complete(Unit)
            vm.state.value shouldBe UploadState.Opened
        }

        test("open failure after a good upload is OpenFailed, not an upload failure") {
            Fakes(openWith = { throw java.io.IOException("offline") }).vm().state.value shouldBe UploadState.OpenFailed("offline")
        }

        test("Found → open(bookId) called, state Opened") {
            val f = Fakes()
            f.vm().state.value shouldBe UploadState.Opened
            f.opened shouldBe listOf(7L)
        }

        test("NotPickedUp is shown and nothing opens") {
            val f = Fakes(result = UploadResult.NotPickedUp)
            f.vm().state.value shouldBe UploadState.NotPickedUp
            f.opened shouldBe emptyList()
        }
    })
