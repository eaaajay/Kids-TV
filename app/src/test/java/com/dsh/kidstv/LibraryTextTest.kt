package com.dsh.kidstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 不依赖 Android 运行时的排序和标题清洗回归测试。 */
class LibraryTextTest {

    @Test
    fun `natural sort compares embedded numbers numerically`() {
        assertTrue(Library.natCompare("S01E02.mp4", "S01E10.mp4") < 0)
        assertTrue(Library.natCompare("S01E10.mp4", "S01E02.mp4") > 0)
        assertTrue(Library.natCompare("第2集", "第10集") < 0)
        assertEquals(0, Library.natCompare("EP02", "ep2"))
    }

    @Test
    fun `natural sort handles leading zeros and non digit prefixes`() {
        // 前导零只影响写法，不影响数字大小
        assertEquals(0, Library.natCompare("01-Intro", "1-Intro"))
        assertTrue(Library.natCompare("A2", "A10") < 0)
        assertTrue(Library.natCompare("A", "A1") < 0)
    }

    @Test
    fun `clean title strips episode prefixes and normalizes separators`() {
        assertEquals("Muddy Puddles", Library.cleanTitle("01 - Muddy Puddles.mp4"))
        assertEquals("Magic Xylophone", Library.cleanTitle("02.Magic Xylophone.mkv"))
        assertEquals("ABC Song", Library.cleanTitle("ABC_Song.mp4"))
        assertEquals("Two Words", Library.cleanTitle("Two   Words.mp4"))
    }

    @Test
    fun `clean title keeps bare number when no delimiter exists`() {
        assertEquals("01", Library.cleanTitle("01.mp4"))
    }
}
