package com.home.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesTest {
    @Test
    fun newerModRevision() {
        assertTrue(newerRelease("v1.0-ru-v3", "1.0-ru-v2"))
        assertFalse(newerRelease("v1.0-ru-v2", "1.0-ru-v2"))
        assertFalse(newerRelease("v1.0-ru-v1", "1.0-ru-v2"))
    }

    @Test
    fun newerBaseVersionWins() {
        assertTrue(newerRelease("v1.1-ru-v1", "1.0-ru-v9"))
        assertTrue(newerRelease("v1.0.1-ru-v1", "1.0-ru-v5"))
        assertFalse(newerRelease("v0.3-ru-v1", "1.0-ru-v2"))
    }

    @Test
    fun plainVersionsAndJunk() {
        assertTrue(newerRelease("v1.0-ru-v1", "1.0"))
        assertFalse(newerRelease("latest", "1.0-ru-v2"))
        assertFalse(newerRelease("v1.0-beta", "1.0-ru-v2"))
    }

    @Test
    fun onlyThisRepositorysApk() {
        assertTrue(Updates.validUpdateUrl("https://github.com/DobreinGitHub/beam-launcher-mod/releases/download/v1.0-ru-v3/app-release.apk"))
        assertFalse(Updates.validUpdateUrl("https://github.com/tonisaf/beam-launcher/releases/download/v1.0.1/app-release.apk"))
        assertFalse(Updates.validUpdateUrl("http://github.com/DobreinGitHub/beam-launcher-mod/releases/download/v1/app-release.apk"))
        assertFalse(Updates.validUpdateUrl("https://github.com/DobreinGitHub/beam-launcher-mod/releases/download/../x/app-release.apk"))
    }

    @Test
    fun digest() {
        assertEquals("ab".repeat(32), updateDigest("sha256:" + "AB".repeat(32)))
        assertNull(updateDigest("sha256:short"))
    }
}
