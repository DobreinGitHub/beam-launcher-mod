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

    @Test
    fun chunksAreJoinedInOrder() {
        val all = joinChunks(listOf(shortArrayOf(1, 2), shortArrayOf(), shortArrayOf(3)))
        assertEquals(listOf<Short>(1, 2, 3), all.toList())
    }

    @Test
    fun theRemoteClickAndSilenceBeforeSpeechAreDropped() {
        val rate = 16000
        val all = ShortArray(100) + ShortArray(20_000) { 1 }
        // 100 samples of silence, then 0.3 s (4800 samples) of click.
        assertEquals(20_100 - (100 + 4800), trimRemoteClick(all, rate).size)
        assertEquals(0, trimRemoteClick(ShortArray(500), rate).size)
        assertEquals(0, trimRemoteClick(ShortArray(0), rate).size)
    }

    @Test
    fun newModelReplacesTheOldOne() {
        val target = folder.newFolder("vosk-ru").apply { File(this, "old.txt").writeText("old") }
        val fresh = folder.newFolder("fresh").apply { File(this, "am").mkdirs() }
        val backup = File(folder.root, "vosk-ru.old")
        swapDirectory(fresh, target, backup)
        assertTrue(File(target, "am").isDirectory)
        assertFalse(File(target, "old.txt").exists())
        assertFalse(backup.exists())
        assertFalse(fresh.exists())
    }

    @Test
    fun missingNewModelLeavesTheOldOneAlone() {
        val target = folder.newFolder("vosk-ru").apply { File(this, "old.txt").writeText("old") }
        try {
            swapDirectory(File(folder.root, "nope"), target, File(folder.root, "vosk-ru.old"))
            fail("expected a refusal")
        } catch (expected: IllegalStateException) {
            // refused
        }
        assertEquals("old", File(target, "old.txt").readText())
    }

    @Test
    fun addedVoiceAppsAreParsedAndFormattedBack() {
        val text = "Kodi|org.xbmc.kodi,org.xbmc.kodi.beta|Коди; Открой коди\nbroken line\n||\nMusic|pkg|"
        val apps = parseVoiceApps(text)
        assertEquals(1, apps.size) // the other lines lack a part
        assertEquals("Kodi", apps[0].name)
        assertEquals(listOf("org.xbmc.kodi", "org.xbmc.kodi.beta"), apps[0].packages)
        assertEquals(listOf("коди", "открой коди"), apps[0].phrases)
        assertEquals("Kodi|org.xbmc.kodi,org.xbmc.kodi.beta|коди;открой коди", formatVoiceApps(apps))
    }

    @Test
    fun builtInAppsHavePackagesAndPhrases() {
        assertTrue(VoiceApps.builtIn.all { it.packages.isNotEmpty() && it.phrases.isNotEmpty() })
        assertTrue(VoiceApps.builtIn.any { it.name == "SmartTube" })
    }
}
