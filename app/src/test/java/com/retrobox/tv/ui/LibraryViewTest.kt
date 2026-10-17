package com.retrobox.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryViewTest {
    // The chosen view is saved by position (rememberSaveable ordinal), so the order must not change under saved state.
    @Test fun gridListBigInThatOrder() {
        assertEquals(listOf(LibraryView.GRID, LibraryView.LIST, LibraryView.BIG), LibraryView.entries)
        assertEquals(0, LibraryView.GRID.ordinal)
    }

    @Test fun everyViewHasItsOwnHeaderIcon() {
        assertEquals(LibraryView.entries.size, LibraryView.entries.map { it.icon }.toSet().size)
        assertTrue(LibraryView.entries.all { it.icon != UiIcon.BACK && it.icon != UiIcon.SEARCH })
    }
}
