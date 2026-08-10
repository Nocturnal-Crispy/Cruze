package com.cruze.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Turn instructions over the device TTS. Ducks other audio (music, intercom) rather than
 * stopping it, which is what a rider expects from a nav voice.
 */
class Speaker(context: Context) {
    private val appCtx = context.applicationContext
    private var ready = false
    private var tts: TextToSpeech? = null
    private val audio = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var focus: AudioFocusRequest? = null

    var enabled = true

    init {
        tts = TextToSpeech(appCtx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setAudioAttributes(attrs)
                tts?.language = Locale.getDefault()
                ready = true
            }
        }
    }

    fun say(text: String) {
        if (!enabled || !com.cruze.Settings.voiceGuidance || !ready || text.isBlank()) return
        requestFocus()
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "cruze")
    }

    private fun requestFocus() {
        if (focus != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        audio.requestAudioFocus(req)
        focus = req
    }

    fun release() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
