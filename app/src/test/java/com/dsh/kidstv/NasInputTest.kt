package com.dsh.kidstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NasInputTest {

    @Test
    fun `plain ip defaults to http webdav port`() {
        val a = NasInput.parse("192.168.0.109")
        assertEquals("http", a.scheme)
        assertEquals("192.168.0.109", a.host)
        assertEquals(5005, a.port)
        assertEquals("", a.path)
        assertFalse(a.pastedUrl)
    }

    @Test
    fun `full https url keeps its own port and path`() {
        val a = NasInput.parse("https://dav.example.com:443/video/kids", fallbackPort = 5005)
        assertEquals("https", a.scheme)
        assertEquals("dav.example.com", a.host)
        assertEquals(443, a.port)
        assertEquals("video/kids", a.path)
        assertTrue(a.pastedUrl)

        val cfg = NasInput.apply(NasConfig(), "https://dav.example.com:443/video/kids", "u", "p")
        assertEquals("/video/kids", cfg.rootPath)
    }

    @Test
    fun `pasted https url without port uses 443 instead of stale form value`() {
        val a = NasInput.parse("https://dav.example.com", fallbackScheme = "http", fallbackPort = 5005)
        assertEquals("https", a.scheme)
        assertEquals(443, a.port)
        assertTrue(a.pastedUrl)
    }

    @Test
    fun `ipv6 brackets are preserved`() {
        val a = NasInput.parse("https://[2408:1234::1]:5006")
        assertEquals("[2408:1234::1]", a.host)
        assertEquals(5006, a.port)
        assertEquals("https", a.scheme)
    }

    @Test
    fun `host and port in one field are split`() {
        val a = NasInput.parse("192.168.0.109:5006")
        assertEquals("192.168.0.109", a.host)
        assertEquals(5006, a.port)
        assertFalse(a.pastedUrl)
    }
}
