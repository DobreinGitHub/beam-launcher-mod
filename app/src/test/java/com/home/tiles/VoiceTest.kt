package com.home.tiles

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    @Test
    fun searchQueryTakesTheWordsAfterASearchWord() {
        assertEquals("котики", VoiceCommands.searchQuery("найди котики"))
        assertEquals("смешные видео", VoiceCommands.searchQuery("покажи смешные видео"))
        assertEquals("котики", VoiceCommands.searchQuery("  найди   котики  "))
    }

    @Test
    fun searchQueryIgnoresEverythingElse() {
        assertNull(VoiceCommands.searchQuery("найди"))
        assertNull(VoiceCommands.searchQuery("включи музыку"))
        assertNull(VoiceCommands.searchQuery(""))
    }

    @Test
    fun modelZipIsUnpackedWithoutItsTopFolder() {
        val target = folder.newFolder("model")
        extractModelZip(zip("vosk-model/am/final.mdl" to "weights", "vosk-model/conf/model.conf" to "x"), target)
        assertEquals("weights", File(target, "am/final.mdl").readText())
        assertTrue(File(target, "conf/model.conf").isFile)
        assertFalse(File(target, "vosk-model").exists())
    }

    @Test
    fun zipSlipEntryIsRefusedAndWritesNothingOutside() {
        val target = folder.newFolder("model")
        try {
            extractModelZip(zip("vosk-model/../../evil.txt" to "pwned"), target)
            fail("expected the entry to be refused")
        } catch (expected: IllegalStateException) {
            // refused
        }
        assertFalse(File(target.parentFile, "evil.txt").exists())
        assertFalse(File(target.parentFile.parentFile, "evil.txt").exists())
    }
}
