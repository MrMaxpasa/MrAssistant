package com.example.mrassistant

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
    val partialText: String = "",   // transcripción en vivo mientras escucha
    val responseText: String = "",
    val error: String? = null,
    val rmsLevel: Float = 0f        // 0..1, alimenta la animación del círculo
)

// AndroidViewModel en lugar de ViewModel normal porque necesitamos el
// Application context para crear SpeechRecognizer y TextToSpeech.
class AssistantViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    // SpeechRecognizer DEBE crearse y usarse desde el hilo principal.
    // Como este método se llama desde un click de Compose (hilo principal), todo bien.
    private var speechRecognizer: SpeechRecognizer? = null
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

    // Punto de entrada único desde la UI al pulsar el botón.
    fun startListening() {
        if (_uiState.value.status != AssistantStatus.IDLE) return

        if (!SpeechRecognizer.isRecognitionAvailable(getApplication())) {
            _uiState.value = _uiState.value.copy(
                error = "Reconocimiento de voz no disponible en este dispositivo."
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            status = AssistantStatus.LISTENING,
            partialText = "",
            error = null
        )

        // Siempre destruimos el reconocedor anterior antes de crear uno nuevo
        // para evitar el error ERROR_RECOGNIZER_BUSY.
        speechRecognizer?.destroy()
        speechRecognizer = buildRecognizer().also { r ->
            r.setRecognitionListener(recognitionListener)
            r.startListening(buildIntent())
        }
    }

    fun onMicPermissionDenied() {
        _uiState.value = _uiState.value.copy(error = "Permiso de micrófono denegado.")
    }

    // Android 13+ (API 33): si el modelo on-device está descargado, usamos
    // createOnDeviceSpeechRecognizer → no requiere conexión y es más rápido.
    // En Android <13 o si no está disponible, usamos el reconocedor en la nube normal.
    private fun buildRecognizer(): SpeechRecognizer =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(getApplication())
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(getApplication())
        } else {
            SpeechRecognizer.createSpeechRecognizer(getApplication())
        }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            // Resultados parciales: el reconocedor envía texto provisional
            // mientras hablas, antes de que termine la frase.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    // RecognitionListener: el reconocedor llama a estos métodos desde el hilo principal.
    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        // Se llama ~10 veces por segundo con el volumen del audio entrante.
        // rmsdB está en torno a [-2, 10] dB; lo normalizamos a [0, 1] para
        // que la UI pueda escalar un círculo directamente.
        override fun onRmsChanged(rmsdB: Float) {
            _uiState.value = _uiState.value.copy(
                rmsLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            )
        }

        override fun onEndOfSpeech() {
            // El micrófono se cerró; esperamos onResults u onError.
            _uiState.value = _uiState.value.copy(rmsLevel = 0f)
        }

        // El motor envía texto provisional (puede cambiar) mientras hablas.
        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (partial.isNotBlank()) {
                _uiState.value = _uiState.value.copy(partialText = partial)
            }
        }

        // Reconocimiento terminado con éxito; texto final disponible.
        override fun onResults(results: Bundle?) {
            speechRecognizer?.destroy()
            speechRecognizer = null

            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()

            if (text.isNotBlank()) {
                sendToGemini(text)
            } else {
                _uiState.value = _uiState.value.copy(
                    status = AssistantStatus.IDLE,
                    rmsLevel = 0f,
                    partialText = "",
                    error = "No se recogió ningún texto. Inténtalo de nuevo."
                )
            }
        }

        override fun onError(error: Int) {
            speechRecognizer?.destroy()
            speechRecognizer = null

            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH ->
                    "No entendí nada. Inténtalo de nuevo."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    "Sin voz detectada. Inténtalo de nuevo."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                    "El reconocedor está ocupado. Espera un momento."
                SpeechRecognizer.ERROR_AUDIO ->
                    "Error de audio. Comprueba el micrófono."
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "Sin conexión a Internet."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    "Sin permisos de micrófono."
                else -> "Error del reconocedor (código $error)."
            }

            _uiState.value = _uiState.value.copy(
                status = AssistantStatus.IDLE,
                rmsLevel = 0f,
                partialText = "",
                error = message
            )
        }
    }

    // Igual que antes; se mantiene intacto salvo que ahora es private
    // y limpia también los campos nuevos.
    private fun sendToGemini(text: String) {
        _uiState.value = _uiState.value.copy(
            status = AssistantStatus.THINKING,
            userText = text,
            partialText = "",
            responseText = "",
            rmsLevel = 0f,
            error = null
        )
        viewModelScope.launch {
            try {
                val reply = GeminiApi.generateContent(BuildConfig.GEMINI_API_KEY, text)
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

    override fun onCleared() {
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onCleared()
    }
}
