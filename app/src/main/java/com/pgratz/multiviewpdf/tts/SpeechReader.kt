package com.pgratz.multiviewpdf.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Thin wrapper over the system [TextToSpeech] engine. Engine callbacks arrive on a binder
 * thread, so they are only turned into [events]; the ViewModel collects those on Main.
 * All methods are meant to be called from the main thread (the engine is created there).
 */
class SpeechReader(context: Context, initialRate: Float) {
    sealed interface Event {
        data object Ready : Event
        data object InitFailed : Event
        data class Started(val id: String) : Event
        data class Done(val id: String) : Event
        /** After our own stop()/flush; [id] is the utterance that was cut off. */
        data class Stopped(val id: String) : Event
        data class Error(val id: String?, val code: Int) : Event
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events

    var isReady = false
        private set
    private var failed = false
    private var rate = initialRate
    private var tts: TextToSpeech? = null

    /** speak() calls made before the engine finished initialising. */
    private val pending = ArrayDeque<Triple<String, String, Boolean>>()

    val maxLength: Int get() = TextToSpeech.getMaxSpeechInputLength()

    init {
        tts = TextToSpeech(context.applicationContext) { status -> onInit(status) }
    }

    private fun onInit(status: Int) {
        val t = tts
        if (status != TextToSpeech.SUCCESS || t == null) {
            failed = true
            pending.clear()
            _events.tryEmit(Event.InitFailed)
            return
        }
        t.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        t.setSpeechRate(rate)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) { _events.tryEmit(Event.Started(utteranceId)) }
            override fun onDone(utteranceId: String) { _events.tryEmit(Event.Done(utteranceId)) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) { _events.tryEmit(Event.Error(utteranceId, -1)) }
            override fun onError(utteranceId: String, errorCode: Int) { _events.tryEmit(Event.Error(utteranceId, errorCode)) }
            override fun onStop(utteranceId: String, interrupted: Boolean) { _events.tryEmit(Event.Stopped(utteranceId)) }
        })
        isReady = true
        _events.tryEmit(Event.Ready)
        while (pending.isNotEmpty()) {
            val (id, text, flush) = pending.removeFirst()
            doSpeak(id, text, flush)
        }
    }

    /** Queues [text] (QUEUE_ADD) or replaces everything queued ([flush]). [id] comes back in the events. */
    fun speak(id: String, text: String, flush: Boolean) {
        if (failed) return
        if (!isReady) {
            if (flush) pending.clear()
            pending.addLast(Triple(id, text, flush))
            return
        }
        doSpeak(id, text, flush)
    }

    private fun doSpeak(id: String, text: String, flush: Boolean) {
        val r = tts?.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        if (r != TextToSpeech.SUCCESS) _events.tryEmit(Event.Error(id, r ?: -1))
    }

    fun stop() {
        pending.clear()
        if (isReady) tts?.stop()
    }

    fun setRate(r: Float) {
        rate = r
        if (isReady) tts?.setSpeechRate(r)
    }

    fun shutdown() {
        pending.clear()
        tts?.let { runCatching { it.stop(); it.shutdown() } }
        tts = null
        isReady = false
    }
}
