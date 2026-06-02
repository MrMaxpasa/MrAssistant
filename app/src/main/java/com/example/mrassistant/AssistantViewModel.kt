package com.example.mrassistant

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

enum class AssistantStatus { IDLE, LISTENING, THINKING, SPEAKING }

data class AssistantUiState(
    val status: AssistantStatus = AssistantStatus.IDLE,
    val userText: String = "",
    val partialText: String = "",
    val responseText: String = "",
    val error: String? = null,          // error duro: fallo de escucha/reconocimiento
    val networkWarning: String? = null, // aviso suave: escucha OK pero Gemini sin red
    val rmsLevel: Float = 0f
)

class AssistantViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    // Emite Unit cuando una herramienta necesita el permiso de ubicación.
    // MainActivity lo observa y lanza el diálogo del sistema.
    private val _locationPermRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val locationPermRequest: SharedFlow<Unit> = _locationPermRequest.asSharedFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    // AudioManager para silenciar STREAM_SYSTEM en reintentos silenciosos.
    // TTS usa STREAM_MUSIC → no se ve afectado.
    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var retryWithCloud = false
    private var retryWithoutLanguage = false

    init {
        registerAppTools()
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureTts()
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

    private fun configureTts() {
        val engine = tts ?: return
        val prefs  = TtsPreferences(getApplication())

        val spanishVoices = engine.voices
            ?.filter { it.locale.language == "es" }
            ?.sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }
                .thenBy { it.isNetworkConnectionRequired })
            ?: emptyList()

        val savedName = prefs.voiceName
        val selectedVoice: android.speech.tts.Voice? = when {

            // 1. El usuario eligió una voz en los ajustes → úsala.
            savedName != null ->
                spanishVoices.firstOrNull { it.name == savedName }

            // 2. Auto-detect: busca "male" / "female" en el nombre (patrón Google TTS).
            AlfredPersona.PREFER_MALE_VOICE ->
                spanishVoices.firstOrNull { v ->
                    v.name.contains("male", ignoreCase = true) &&
                    !v.name.contains("female", ignoreCase = true)
                }
                // 3. La voz activa del sistema es española → respétala.
                // engine.voice es la voz que TTS usaría sin tocar nada.
                // Si el usuario tiene configurada una voz masculina en Ajustes del
                // sistema, aquí ya es la correcta y no hace falta llamar setLanguage.
                ?: engine.voice?.takeIf { it.locale.language == "es" }
                // 4. Cualquier voz en español instalada.
                ?: spanishVoices.firstOrNull()

            else ->
                spanishVoices.firstOrNull { v ->
                    v.name.contains("female", ignoreCase = true)
                }
                ?: engine.voice?.takeIf { it.locale.language == "es" }
                ?: spanishVoices.firstOrNull()
        }

        if (selectedVoice != null) {
            engine.setVoice(selectedVoice)
            Log.d("AlfredTTS", "Voz activa → ${selectedVoice.name}")
        } else {
            // Ninguna voz en español encontrada: forzamos locale como último recurso.
            // (No llamamos setLanguage si ya tenemos voz española, para no pisarla.)
            val result = engine.setLanguage(Locale("es", "ES"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale("es", "US"))
            }
            Log.d("AlfredTTS", "Sin voces españolas instaladas; usando locale es-ES/es-US")
        }

        engine.setPitch(prefs.pitch)
        engine.setSpeechRate(prefs.speechRate)
    }

    /** Llama a esto al volver de los ajustes de voz para aplicar los cambios al TTS activo. */
    fun reloadTts() = configureTts()

    fun startListening() {
        if (_uiState.value.status != AssistantStatus.IDLE) return

        if (!SpeechRecognizer.isRecognitionAvailable(getApplication())) {
            _uiState.value = _uiState.value.copy(
                error = "Reconocimiento de voz no disponible en este dispositivo."
            )
            return
        }

        retryWithCloud = false
        retryWithoutLanguage = false
        _uiState.value = _uiState.value.copy(
            status = AssistantStatus.LISTENING,
            partialText = "",
            error = null,
            networkWarning = null
        )

        speechRecognizer?.destroy()
        speechRecognizer = buildRecognizer().also { r ->
            r.setRecognitionListener(recognitionListener)
            r.startListening(buildIntent())
        }
    }

    fun onMicPermissionDenied() {
        _uiState.value = _uiState.value.copy(error = "Permiso de micrófono denegado.")
    }

    // Comprobación dinámica en cada intento (no cacheada): si el usuario
    // acaba de descargar el paquete on-device, esta llamada ya lo detecta.
    private fun buildRecognizer(): SpeechRecognizer =
        if (!retryWithCloud &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(getApplication())
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(getApplication())
        } else {
            SpeechRecognizer.createSpeechRecognizer(getApplication())
        }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            if (!retryWithoutLanguage) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

    private val recognitionListener: RecognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onRmsChanged(rmsdB: Float) {
            _uiState.value = _uiState.value.copy(
                rmsLevel = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            )
        }

        override fun onEndOfSpeech() {
            _uiState.value = _uiState.value.copy(rmsLevel = 0f)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (partial.isNotBlank()) {
                _uiState.value = _uiState.value.copy(partialText = partial)
            }
        }

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
            // Si es un reintento silencioso, silenciamos STREAM_SYSTEM ANTES de
            // destruir el reconocedor: así el pitido de error del OS queda tapado.
            // Restauramos el volumen 500 ms después, tiempo suficiente para que
            // pase la ventana de audio del error. STREAM_MUSIC (TTS) no se toca.
            val isSilentRetry = (error == 11 && !retryWithCloud) ||
                                 (error == 13 && !retryWithoutLanguage)
            if (isSilentRetry) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_MUTE, 0)
                mainHandler.postDelayed({
                    audioManager.adjustStreamVolume(AudioManager.STREAM_SYSTEM, AudioManager.ADJUST_UNMUTE, 0)
                }, 500L)
            }

            speechRecognizer?.destroy()
            speechRecognizer = null

            // Error 11 = SERVER_DISCONNECTED: on-device se desconectó → nube.
            if (error == 11 && !retryWithCloud) {
                retryWithCloud = true
                _uiState.value = _uiState.value.copy(partialText = "", error = null)
                speechRecognizer = buildRecognizer().also { r ->
                    r.setRecognitionListener(recognitionListener)
                    r.startListening(buildIntent())
                }
                return
            }

            // Error 13 = LANGUAGE_UNAVAILABLE: sin paquete de español → sin idioma forzado.
            if (error == 13 && !retryWithoutLanguage) {
                retryWithoutLanguage = true
                _uiState.value = _uiState.value.copy(partialText = "", error = null)
                speechRecognizer = buildRecognizer().also { r ->
                    r.setRecognitionListener(recognitionListener)
                    r.startListening(buildIntent())
                }
                return
            }

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
                11 -> "Reconocedor desconectado incluso en la nube. Comprueba la red."
                13 -> "Idioma no disponible. Ve a Ajustes → Accesibilidad → Texto a voz y descarga el español."
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

    private fun sendToGemini(text: String) {
        _uiState.value = _uiState.value.copy(
            status = AssistantStatus.THINKING,
            userText = text,
            partialText = "",
            responseText = "",
            rmsLevel = 0f,
            error = null,
            networkWarning = null
        )
        viewModelScope.launch {
            try {
                val reply = GeminiApi.generateContent(
                    apiKey = BuildConfig.GEMINI_API_KEY,
                    userMessage = text,
                    systemInstruction = AlfredPersona.SYSTEM_PROMPT,
                    toolDeclarations = ToolRegistry.declarations(),
                    toolExecutor = { name, args ->
                        val result = ToolRegistry.execute(name, args, getApplication())
                        // Si la herramienta devuelve "needs_permission":"location",
                        // lanzamos el diálogo del sistema en paralelo mientras
                        // Alfred dice en voz alta que necesita el permiso.
                        if (result.optString("needs_permission") == "location") {
                            _locationPermRequest.emit(Unit)
                        }
                        result
                    }
                )
                _uiState.value = _uiState.value.copy(
                    status = AssistantStatus.SPEAKING,
                    responseText = reply
                )
                tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "resp")
            } catch (e: RateLimitException) {
                _uiState.value = _uiState.value.copy(
                    status = AssistantStatus.IDLE,
                    networkWarning = "He llegado a mi límite de consultas por ahora, Mister. Dame un momento y vuelve a intentarlo."
                )
            } catch (e: NoInternetException) {
                _uiState.value = _uiState.value.copy(
                    status = AssistantStatus.IDLE,
                    networkWarning = "Te he escuchado, pero necesito internet para responderte."
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    status = AssistantStatus.IDLE,
                    error = e.message ?: "Error desconocido"
                )
            }
        }
    }

    override fun onCleared() {
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onCleared()
    }
}
