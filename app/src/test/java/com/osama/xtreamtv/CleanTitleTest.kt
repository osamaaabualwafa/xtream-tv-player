package com.osama.xtreamtv

import org.junit.Assert.assertEquals
import org.junit.Test

/** Clean titles drop the release year only when it is the year, never a number that is part of the name. */
class CleanTitleTest {
    @Test
    fun yearThatMatchesGoes() {
        assertEquals("Interstellar", Versions.clean("Interstellar 2014", "2014"))
        assertEquals("Memento", Versions.clean("Memento 2000", "2001"))
        assertEquals("Memento", Versions.clean("Memento (2000)", "2000-10-11"))
        assertEquals("Dune", Versions.clean("Dune - 2021", "2021"))
    }

    @Test
    fun numberInTheNameStays() {
        assertEquals("Blade Runner 2049", Versions.clean("Blade Runner 2049", "2017"))
        assertEquals("Wonder Woman 1984", Versions.clean("Wonder Woman 1984", "2020"))
        assertEquals("1917", Versions.clean("1917", "2019"))
        assertEquals("2012", Versions.clean("2012", "2009"))
    }

    @Test
    fun yearUnknownOnlyBracketsGo() {
        assertEquals("Memento", Versions.clean("Memento (2000)", null))
        assertEquals("Wonder Woman 1984", Versions.clean("Wonder Woman 1984", null))
    }
}
