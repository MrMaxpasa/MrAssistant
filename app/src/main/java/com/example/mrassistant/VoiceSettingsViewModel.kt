package com.example.mrassistant

import android.app.Application
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoiceItem(
    val name: String,
    val locale: String,
    val quality: Int,
    val isNetworkRequired: Boolean,
    val rawVoice: Voice
)

class VoiceSettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = TtsPreferences(app)

    private val _voices = MutableStateFlow<List<VoiceItem>>(emptyList())
    val voices: StateFlow<List<VoiceItem>> = _voices.asStateFlow()

    private val _selectedVoiceName = MutableStateFlow(prefs.voiceName)
    val selectedVoiceName: StateFlow<String?> = _selectedVoiceName.asStateFlow()

    private val _pitch = MutableStateFlow(prefs.pitch)
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    private val _speechRate = MutableStateFlow(prefs.speechRate)
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    private var previewTts: TextToSpeech? = null

    init {
        previewTts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val raw = previewTts?.voices
                    ?.filter { it.locale.language == "es" }
                    ?.sortedWith(
                        compareByDescending<Voice> { it.quality }
                            .thenBy { it.isNetworkConnectionRequired }
                    )
                    ?: emptyList()

                _voices.value = raw.map { v ->
                    VoiceItem(
                        name = v.name,
                        locale = "${v.locale.language}-${v.locale.country.uppercase()}",
                        quality = v.quality,
                        isNetworkRequired = v.isNetworkConnectionRequired,
                        rawVoice = v
                    )
                }

                // Logcat tag "AlfredTTS" → aquí verás todas las voces del dispositivo
                Log.d("AlfredTTS", "=== Voces en español (${raw.size}) ===")
                raw.forEach { v ->
                    Log.d("AlfredTTS", "  ${v.name}  |  ${v.locale}  |  calidad=${v.quality}  |  red=${v.isNetworkConnectionRequired}")
                }
            }
        }
    }

    fun onVoiceSelected(name: String) { _selectedVoiceName.value = name }
    fun onPitchChanged(value: Float)  { _pitch.value = value }
    fun onSpeechRateChanged(value: Float) { _speechRate.value = value }

    fun testVoice(item: VoiceItem) {
        val engine = previewTts ?: return
        engine.stop()
        engine.setVoice(item.rawVoice)
        engine.setPitch(_pitch.value)
        engine.setSpeechRate(_speechRate.value)
        engine.speak("Buenas, Mister. Soy Alfred.", TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    fun saveSettings() {
        prefs.voiceName = _selectedVoiceName.value
        prefs.pitch = _pitch.value
        prefs.speechRate = _speechRate.value
    }

    override fun onCleared() {
        previewTts?.stop()
        previewTts?.shutdown()
        super.onCleared()
    }
}
