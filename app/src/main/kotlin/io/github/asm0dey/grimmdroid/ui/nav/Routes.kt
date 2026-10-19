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
package io.github.asm0dey.grimmdroid.ui.nav

import kotlinx.serialization.Serializable

@Serializable data object Onboarding

@Serializable data object Home

@Serializable data object Library

@Serializable data object Shelves

@Serializable data object Downloads

@Serializable
data class ShelfBooks(
    val shelfId: Long,
    val magic: Boolean,
    val name: String,
)

@Serializable
data class Details(
    val bookId: Long,
    /** The account the book belongs to; the details never follow an account switch. */
    val accountId: Long,
)

@Serializable data object Search

@Serializable data object Settings
