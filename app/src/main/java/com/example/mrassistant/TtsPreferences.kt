package com.example.mrassistant

import android.content.Context

class TtsPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("alfred_tts", Context.MODE_PRIVATE)

    var voiceName: String?
        get() = prefs.getString("voice_name", null)
        set(value) { prefs.edit().putString("voice_name", value).apply() }

    var pitch: Float
        get() = prefs.getFloat("pitch", AlfredPersona.PITCH)
        set(value) { prefs.edit().putFloat("pitch", value).apply() }

    var speechRate: Float
        get() = prefs.getFloat("speech_rate", AlfredPersona.SPEECH_RATE)
        set(value) { prefs.edit().putFloat("speech_rate", value).apply() }
}
