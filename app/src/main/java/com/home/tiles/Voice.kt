package com.home.tiles

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Offline speech model (Vosk, small Russian). Not in the APK: downloaded once into the app's files
 * and loaded only while the voice key is in use, since the projector has little memory.
 */
object VoiceModel {
    const val DEFAULT_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"

    private fun dir(context: Context) = File(context.filesDir, "vosk-ru")

    fun installed(context: Context) = File(dir(context), "am").isDirectory

    @Volatile
    private var model: Model? = null
    private val unload = Runnable { release() }
    private val handler = Handler(Looper.getMainLooper())

    /** Blocking; call off the main thread. Null until the model is installed. */
    @Synchronized
    fun load(context: Context): Model? {
        handler.removeCallbacks(unload)
        model?.let { return it }
        if (!installed(context)) return null
        return runCatching { Model(dir(context).absolutePath) }
            .onFailure { Log.w("Voice", "Model load failed", it) }
            .getOrNull()
            .also { model = it }
    }

    /** Frees the model a minute after the last use. */
    fun releaseLater() {
        handler.removeCallbacks(unload)
        handler.postDelayed(unload, 60_000)
    }

    @Synchronized
    private fun release() {
        model?.close()
        model = null
        Log.i("Voice", "Model unloaded")
    }

    /** Blocking download + unzip. */
    fun download(context: Context, url: String = DEFAULT_URL): Boolean = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.inputStream.use { install(context, it) }
    }.onFailure { Log.w("Voice", "Model download failed", it) }.getOrDefault(false)

    /** The zip pushed over adb into [VoiceModelProvider]. */
    fun installFrom(file: File, context: Context): Boolean = runCatching {
        file.inputStream().use { install(context, it) }.also { file.delete() }
    }.onFailure { Log.w("Voice", "Model install failed", it) }.getOrDefault(false)

    /** Unzips the model (the zip has one top folder, which is stripped). */
    private fun install(context: Context, input: java.io.InputStream): Boolean {
        val target = dir(context)
        val tmp = File(context.filesDir, "vosk-ru.tmp").apply { deleteRecursively(); mkdirs() }
        ZipInputStream(input.buffered()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val relative = entry.name.substringAfter('/', "")
                if (relative.isEmpty()) return@forEach
                val out = File(tmp, relative)
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
            }
        }
        target.deleteRecursively()
        check(tmp.renameTo(target))
        Log.i("Voice", "Model installed")
        return installed(context)
    }
}

/**
 * Lets adb push the model zip without a network download:
 *   adb exec-in "content write --uri content://com.home.tiles.voicemodel/model.zip" < model.zip
 * then `am broadcast ... --ez voice_install true`. Guarded by DUMP, which only the shell holds.
 */
class VoiceModelProvider : android.content.ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: android.net.Uri, mode: String): android.os.ParcelFileDescriptor {
        val file = File(context!!.cacheDir, "vosk-model.zip")
        return android.os.ParcelFileDescriptor.open(
            file,
            android.os.ParcelFileDescriptor.parseMode(if ("w" in mode) "wt" else "r"),
        )
    }

    companion object {
        fun pushedZip(context: Context) = File(context.cacheDir, "vosk-model.zip")
    }

    override fun query(uri: android.net.Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?) = null
    override fun getType(uri: android.net.Uri) = "application/zip"
    override fun insert(uri: android.net.Uri, values: android.content.ContentValues?) = null
    override fun delete(uri: android.net.Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: android.net.Uri, v: android.content.ContentValues?, s: String?, a: Array<String>?) = 0
}

/**
 * One press of the voice key: records the remote's microphone (the audio HAL streams it while the
 * key is held) and recognises the phrase against [VoiceCommands] when the key is released.
 */
class VoiceSession(private val context: Context) {
    private val rate = 16000
    private val chunks = mutableListOf<ShortArray>()

    @Volatile
    private var recording = false
    private var thread: Thread? = null

    /**
     * [loadModel]: start loading the model right away, so recognition is quick on release. The
     * voice key passes false and calls [loadModel] only once the key is really held: loading takes
     * seconds and stalled the panel that a short press opens.
     */
    @SuppressLint("MissingPermission")
    fun start(loadModel: Boolean = true) {
        recording = true
        if (loadModel) loadModel()
        thread = Thread {
            val size = maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), rate / 2)
            val recorder = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            }.getOrNull()
            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.w("Voice", "Microphone unavailable")
                recording = false
                return@Thread
            }
            recorder.startRecording()
            val buffer = ShortArray(rate / 10)
            while (recording) {
                val n = recorder.read(buffer, 0, buffer.size)
                if (n > 0) synchronized(chunks) { chunks += buffer.copyOf(n) }
            }
            recorder.stop()
            recorder.release()
        }.apply { start() }
    }

    fun loadModel() {
        Thread { VoiceModel.load(context) }.start()
    }

    fun cancel() {
        recording = false
    }

    /**
     * Stops recording and returns what to act on (blocking): a "найди …" search in free speech,
     * otherwise the phrase matched against [VoiceCommands] ("" if nothing matched).
     */
    fun finish(): String {
        val samples = stop() ?: return NO_MODEL
        val free = recognize(samples, null)
        val text = if (VoiceCommands.searchQuery(free) != null) free else recognize(samples, VoiceCommands.grammar())
        VoiceModel.releaseLater()
        Log.i("Voice", "Heard \"$text\" (free speech: \"$free\")")
        return text
    }

    /** Stops recording and returns free speech, for apps that asked for dictation. Blocking. */
    fun finishDictation(): String {
        val samples = stop() ?: return NO_MODEL
        return recognize(samples, null).also {
            VoiceModel.releaseLater()
            Log.i("Voice", "Dictated \"$it\"")
        }
    }

    /** Null when the model isn't installed. */
    private fun stop(): List<ShortArray>? {
        recording = false
        thread?.join(2000)
        VoiceModel.load(context) ?: return null
        val all = synchronized(chunks) { chunks.toList() }.flatMap { it.asList() }
        // The remote clicks as its microphone starts (full-scale pop), which the model hears as
        // "а": skip the silence before the stream and its first 0.3 s.
        val start = all.indexOfFirst { it.toInt() != 0 }
        val speech = if (start < 0) ShortArray(0) else all.subList(minOf(all.size, start + rate * 3 / 10), all.size).toShortArray()
        Log.i("Voice", "Recorded ${all.size / rate.toFloat()} s, speech ${speech.size / rate.toFloat()} s")
        return listOf(speech)
    }

    private fun recognize(samples: List<ShortArray>, grammar: String?): String {
        val model = VoiceModel.load(context) ?: return ""
        val recognizer = if (grammar != null) Recognizer(model, rate.toFloat(), grammar) else Recognizer(model, rate.toFloat())
        val text = recognizer.use { r ->
            samples.forEach { r.acceptWaveForm(it, it.size) }
            JSONObject(r.finalResult).optString("text")
        }
        // A stray leading "а"/"и" (click remains, hesitation) would break "найди …" and searches.
        return text.trim().split(' ').dropWhile { it in fillers }.joinToString(" ")
    }

    private val fillers = setOf("а", "и", "э", "ну")

    companion object {
        const val NO_MODEL = "\u0000no-model"
    }
}

/** The phrases the voice key understands, and what they do. */
object VoiceCommands {
    private class Command(val phrases: List<String>, val run: (Context) -> String)

    private val numbers = listOf("один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять", "десять")

    private val commands: List<Command> by lazy {
        buildList {
            fun cmd(vararg phrases: String, run: (Context) -> String) = add(Command(phrases.toList(), run))
            fun app(name: String, pkg: String, vararg phrases: String) = cmd(*phrases) { it.launchPackage(pkg); name }

            app("SmartTube", "org.smarttube.stable", "ютуб", "открой ютуб", "смарт тюб", "открой смарт тюб")
            app("Spotify", "com.spotify.tv.android", "музыка", "включи музыку", "открой музыку")
            app("Jellyfin", "org.jellyfin.androidtv", "фильмы", "открой фильмы", "медиатека")
            cmd("домой", "главный экран", "на главный экран") { ctx ->
                ctx.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_MAIN)
                        .addCategory(android.content.Intent.CATEGORY_HOME)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                "Главный экран"
            }
            cmd("эйч ди эм ай", "приставка", "включи приставку") { ctx ->
                Xgimi.hdmiInputs(ctx).firstOrNull()?.let { Xgimi.openInput(ctx, it); "HDMI" } ?: "HDMI не найден"
            }
            cmd("настройки", "панель") { PanelOverlay.show(); "Панель" }

            numbers.forEachIndexed { i, word ->
                cmd("яркость $word") { Lumens.setLevel(i + 1); "Яркость ${i + 1}" }
            }
            cmd("ярче", "сделай ярче") { val l = ((Lumens.level() ?: 8) + 2).coerceAtMost(Lumens.MAX); Lumens.setLevel(l); "Яркость $l" }
            cmd("темнее", "сделай темнее") { val l = ((Lumens.level() ?: 8) - 2).coerceAtLeast(1); Lumens.setLevel(l); "Яркость $l" }
            cmd("эко режим", "включи эко режим", "экономный режим") { Eco.set(true); "Эко-режим включён" }
            cmd("выключи эко режим", "обычная яркость") { Eco.set(false); "Эко-режим выключен" }

            listOf(
                "кино" to 1, "фильм" to 1, "спорт" to 9, "телевизор" to 7, "офис" to 25,
                "пользовательский" to 3, "умный" to 16, "автоматический" to 16,
            ).forEach { (word, mode) ->
                cmd("режим $word", "$word режим") { ctx ->
                    Xgimi.setPictureMode(ctx, mode)
                    "Режим: " + (Xgimi.pictureModes.firstOrNull { it.second == mode }?.first ?: word)
                }
            }

            cmd("громче", "сделай громче") { ctx -> volume(ctx, android.media.AudioManager.ADJUST_RAISE); "Громче" }
            cmd("тише", "сделай тише") { ctx -> volume(ctx, android.media.AudioManager.ADJUST_LOWER); "Тише" }
            cmd("без звука", "выключи звук") { ctx -> volume(ctx, android.media.AudioManager.ADJUST_TOGGLE_MUTE); "Звук выключен" }
            cmd("пауза", "стоп", "останови") { ctx -> media(ctx, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE); "Пауза" }
            cmd("играй", "продолжи", "воспроизведение") { ctx -> media(ctx, android.view.KeyEvent.KEYCODE_MEDIA_PLAY); "Воспроизведение" }
            cmd("следующий", "следующий трек", "дальше") { ctx -> media(ctx, android.view.KeyEvent.KEYCODE_MEDIA_NEXT); "Следующий" }
            cmd("предыдущий", "предыдущий трек") { ctx -> media(ctx, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS); "Предыдущий" }

            cmd("фокус", "автофокус") { ctx -> Xgimi.autoFocus(ctx); "Автофокус" }
            cmd("трапеция", "выровняй", "выровняй картинку") { ctx -> Xgimi.autoKeystone(ctx); "Трапеция" }
        }
    }

    private fun volume(context: Context, direction: Int) {
        context.getSystemService(android.media.AudioManager::class.java)
            .adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, direction, android.media.AudioManager.FLAG_SHOW_UI)
    }

    private fun media(context: Context, code: Int) {
        val audio = context.getSystemService(android.media.AudioManager::class.java)
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
    }

    /** JSON phrase list for Vosk's grammar mode; "[unk]" lets anything else fall through. */
    fun grammar(): String = JSONArray(commands.flatMap { it.phrases }.distinct() + "[unk]").toString()

    private val searchWords = listOf("найди", "найти", "поищи", "поиск", "ищи", "покажи")

    /** "найди котики" -> "котики"; null if the phrase isn't a search. */
    fun searchQuery(text: String): String? {
        val words = text.trim().split(' ').filter { it.isNotBlank() }
        if (words.size < 2 || words.first() !in searchWords) return null
        return words.drop(1).joinToString(" ")
    }

    /** YouTube search in SmartTube (it opens youtube.com search links). */
    fun search(context: Context, query: String) {
        val url = "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(query, "UTF-8")
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .setPackage("org.smarttube.stable")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Runs the command for a recognised phrase; returns what to show, or null if nothing matched. */
    fun run(context: Context, text: String): String? {
        searchQuery(text)?.let { query ->
            return runCatching { search(context, query); "Поиск: $query" }.getOrNull()
        }
        val phrase = text.trim()
        val command = commands.firstOrNull { phrase in it.phrases } ?: return null
        return runCatching { command.run(context) }
            .onFailure { Log.w("Voice", "Command \"$phrase\" failed", it) }
            .getOrNull()
    }
}

/**
 * A dictation request from another app (system speech recognition). The remote only streams its
 * microphone while the voice key is held, so the request waits for that key: [PanelOverlay] hands
 * it the key instead of toggling the panel while one is pending.
 */
object VoiceRequests {
    /** Called on the main thread with true when the voice key goes down, false when it comes up. */
    @Volatile
    var onVoiceKey: ((Boolean) -> Unit)? = null
}

/**
 * Beam as the system speech recogniser, so apps' own microphone buttons (SmartTube search) work:
 * set with `settings put secure voice_recognition_service com.home.tiles/com.home.tiles.VoskRecognitionService`.
 */
class VoskRecognitionService : android.speech.RecognitionService() {
    private val handler = Handler(Looper.getMainLooper())
    private var session: VoiceSession? = null
    private val timeout = Runnable { finishRequest(null) }
    private var callback: Callback? = null

    override fun onStartListening(intent: android.content.Intent?, listener: Callback) {
        if (!VoiceModel.installed(this)) {
            listener.error(android.speech.SpeechRecognizer.ERROR_CLIENT)
            return
        }
        callback = listener
        session = VoiceSession(this).also { it.start() }
        listener.readyForSpeech(android.os.Bundle())
        PanelOverlay.caption("🎤  Зажмите голосовую кнопку и говорите")
        VoiceRequests.onVoiceKey = { down ->
            if (down) {
                handler.removeCallbacks(timeout)
                PanelOverlay.caption("🎤  Слушаю…")
                callback?.beginningOfSpeech()
            } else {
                callback?.endOfSpeech()
                finishRequest(session)
            }
        }
        handler.postDelayed(timeout, 15_000)
    }

    override fun onStopListening(listener: Callback) = finishRequest(session)

    override fun onCancel(listener: Callback) {
        session?.cancel()
        clear()
        PanelOverlay.caption(null)
    }

    /** Recognises what was recorded and returns it to the app; null (timeout) reports no speech. */
    private fun finishRequest(recorded: VoiceSession?) {
        val listener = callback ?: return
        val current = session
        clear()
        if (recorded == null) {
            current?.cancel()
            PanelOverlay.caption(null)
            runCatching { listener.error(android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT) }
            return
        }
        PanelOverlay.caption("…")
        Thread {
            val text = recorded.finishDictation().takeIf { it != VoiceSession.NO_MODEL }.orEmpty()
            handler.post {
                PanelOverlay.caption(if (text.isBlank()) "Не расслышал" else "«$text»", hideAfterMs = 1500)
                runCatching {
                    if (text.isBlank()) listener.error(android.speech.SpeechRecognizer.ERROR_NO_MATCH)
                    else listener.results(
                        android.os.Bundle().apply {
                            putStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                            putFloatArray(android.speech.SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(1f))
                        },
                    )
                }
            }
        }.start()
    }

    private fun clear() {
        handler.removeCallbacks(timeout)
        VoiceRequests.onVoiceKey = null
        callback = null
        session = null
    }

    override fun onDestroy() {
        session?.cancel()
        clear()
        super.onDestroy()
    }
}

/**
 * The same dictation for apps that start the ACTION_RECOGNIZE_SPEECH activity instead of using
 * the recognition service; returns the text in RecognizerIntent.EXTRA_RESULTS.
 */
class VoiceSearchActivity : android.app.Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var session: VoiceSession? = null
    private val timeout = Runnable { done(null) }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (!VoiceModel.installed(this)) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        session = VoiceSession(this).also { it.start() }
        PanelOverlay.caption("🎤  Зажмите голосовую кнопку и говорите")
        VoiceRequests.onVoiceKey = { down ->
            if (down) {
                handler.removeCallbacks(timeout)
                PanelOverlay.caption("🎤  Слушаю…")
            } else {
                done(session)
            }
        }
        handler.postDelayed(timeout, 15_000)
    }

    private fun done(recorded: VoiceSession?) {
        handler.removeCallbacks(timeout)
        VoiceRequests.onVoiceKey = null
        session = null
        if (recorded == null) {
            PanelOverlay.caption(null)
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        PanelOverlay.caption("…")
        Thread {
            val text = recorded.finishDictation().takeIf { it != VoiceSession.NO_MODEL }.orEmpty()
            handler.post {
                PanelOverlay.caption(if (text.isBlank()) "Не расслышал" else "«$text»", hideAfterMs = 1500)
                if (text.isBlank()) setResult(RESULT_CANCELED)
                else setResult(
                    RESULT_OK,
                    android.content.Intent().putStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS, arrayListOf(text)),
                )
                finish()
            }
        }.start()
    }

    override fun onDestroy() {
        if (session != null) {
            session?.cancel()
            VoiceRequests.onVoiceKey = null
            PanelOverlay.caption(null)
        }
        super.onDestroy()
    }
}
