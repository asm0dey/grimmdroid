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
package io.github.asm0dey.grimmdroid.catalog

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun b(
    id: Long,
    no: Float?,
    title: String = "T$id",
) = BookEntity(1, id, title, "A", "S", no, null, null, null, null, null, null, null, null, null, false, 1)

private val none = ReadState.NOT_STARTED
private val read = ReadState.READ
private val reading = ReadState.IN_PROGRESS
private val dismissed = ReadState.DISMISSED

private fun next(vararg books: Pair<BookEntity, ReadState>): Long? {
    val m = books.toMap()
    return nextToRead(books.map { it.first }) { m.getValue(it) }?.id
}

class ReadStateSpec :
    FunSpec({
        test("readState") {
            readState("READ", 0f) shouldBe read
            readState(null, 99f) shouldBe read
            readState(null, 98.9f) shouldBe reading
            readState("WONT_READ", 50f) shouldBe dismissed
            readState("ABANDONED", 99f) shouldBe read
            readState("PAUSED", null) shouldBe reading
            readState("UNSET", 0f) shouldBe none
            readState("SOMETHING_NEW", null) shouldBe none
            readState(null, null) shouldBe none
        }
        test("entity readState uses the readStatus column") {
            val book = b(1, 1f).copy(readStatus = "READ", readProgress = 0f)
            book.readState shouldBe read
            nextToRead(listOf(book, b(2, 2f))) ?.id shouldBe 2
        }
        test("order") {
            val books = listOf(b(1, 3f), b(2, null, "A"), b(3, 1f), b(4, null, "b"), b(5, 2f))
            books.sortedWith(SeriesOrder).map { it.id } shouldBe listOf(3L, 5L, 1L, 2L, 4L)
        }
        test("next to read") {
            next(b(1, 1f) to read, b(2, 2f) to none, b(3, 3f) to dismissed, b(4, 4f) to none) shouldBe 4
            next(b(1, 1f) to read, b(2, 2f) to none, b(3, 3f) to reading) shouldBe 3
            next(b(1, 1f) to none, b(2, 2f) to read, b(3, 3f) to none) shouldBe 3
            next(b(1, 1f) to read, b(2, 2f) to read, b(3, null) to none) shouldBe 3
            next(b(1, 1f) to read, b(3, null) to read, b(2, 2f) to none) shouldBe 2
            next(b(1, 1f) to read, b(2, 2f) to dismissed) shouldBe null
        }
        test("toGroup") {
            val a = listOf(b(1, 1f), b(2, 2f), b(3, 3f))
            val s1 = mapOf(a[0] to read, a[1] to dismissed, a[2] to none)
            a.toGroup("S") { s1.getValue(it) }.let {
                it.readCount shouldBe 1
                it.total shouldBe 3
                it.started shouldBe true
            }
            val s2 = mapOf(a[0] to dismissed, a[1] to none)
            a.take(2).toGroup("S") { s2.getValue(it) }.started shouldBe false
        }
    })
