package com.example.mrassistant

import android.app.Application
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

enum class AssistantStatus { IDLE, LISTENING, THINKING, SPEAKING }

data class AssistantUiState(
    val status: AssistantStatus = AssistantStatus.IDLE,
    val userText: String = "",
    val responseText: String = "",
    val error: String? = null
)

class AssistantViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("es", "ES")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        _uiState.value = _uiState.value.copy(status = AssistantStatus.IDLE)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _uiState.value = _uiState.value.copy(status = AssistantStatus.IDLE)
                    }
                })
            }
        }
    }

    fun setListening() {
        _uiState.value = _uiState.value.copy(status = AssistantStatus.LISTENING, error = null)
    }

    fun onSpeechResult(spokenText: String) {
        _uiState.value = _uiState.value.copy(
            status = AssistantStatus.THINKING,
            userText = spokenText,
            responseText = "",
            error = null
        )
        viewModelScope.launch {
            try {
                val reply = GeminiApi.generateContent(BuildConfig.GEMINI_API_KEY, spokenText)
                _uiState.value = _uiState.value.copy(
                    status = AssistantStatus.SPEAKING,
                    responseText = reply
                )
                tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "resp")
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    status = AssistantStatus.IDLE,
                    error = e.message ?: "Error desconocido"
                )
            }
        }
    }

    fun onSpeechError() {
        _uiState.value = _uiState.value.copy(
            status = AssistantStatus.IDLE,
            error = "No se pudo reconocer el audio. Inténtalo de nuevo."
        )
    }

    override fun onCleared() {
        tts?.stop()
        tts?.shutdown()
        super.onCleared()
    }
}
