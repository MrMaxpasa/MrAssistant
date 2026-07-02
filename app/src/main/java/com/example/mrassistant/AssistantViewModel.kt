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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

enum class AssistantStatus { IDLE, LISTENING, THINKING, SPEAKING }

data class AssistantUiState(
    val status: AssistantStatus     = AssistantStatus.IDLE,
    val userText: String            = "",
    val partialText: String         = "",
    val responseText: String        = "",
    val error: String?              = null,
    val networkWarning: String?     = null,
    val rmsLevel: Float             = 0f,
    val rateLimitUntilMs: Long?     = null,
    val brainUsed: Brain?           = null,   // qué cerebro respondió el último mensaje
    val gemmaStatus: GemmaStatus    = GemmaStatus.UNAVAILABLE  // para el indicador de cabecera
)

class AssistantViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private val _permissionRequest = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val permissionRequest: SharedFlow<String> = _permissionRequest.asSharedFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    private val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler  = Handler(Looper.getMainLooper())

    private var retryWithCloud      = false
    private var retryWithoutLanguage = false

    // ── Cerebros ──────────────────────────────────────────────────────────────
    private val localLlm = LocalLlmManager(app)
    private val router   = BrainRouter(app, localLlm, BuildConfig.GEMINI_API_KEY)

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
        loadGemmaInBackground()
    }

    // ── Carga de Gemma en segundo plano ───────────────────────────────────────

    private fun loadGemmaInBackground() {
        viewModelScope.launch(Dispatchers.IO) {
            if (!localLlm.modelFileExists()) {
                Log.d("AssistantVM", "Gemma: modelo no encontrado en filesDir, desactivado")
                _uiState.value = _uiState.value.copy(gemmaStatus = GemmaStatus.UNAVAILABLE)
                return@launch
            }
            Log.d("AssistantVM", "Gemma: iniciando carga en background…")
            _uiState.value = _uiState.value.copy(gemmaStatus = GemmaStatus.LOADING)
            localLlm.load().fold(
                onSuccess = {
                    Log.d("AssistantVM", "Gemma: lista")
                    _uiState.value = _uiState.value.copy(gemmaStatus = GemmaStatus.READY)
                },
                onFailure = { e ->
                    Log.e("AssistantVM", "Gemma: error al cargar — ${e.message}")
                    _uiState.value = _uiState.value.copy(gemmaStatus = GemmaStatus.UNAVAILABLE)
                }
            )
        }
    }

    // ── TTS ────────────────────────────────────────────────────────────────────

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
            savedName != null ->
                spanishVoices.firstOrNull { it.name == savedName }
            AlfredPersona.PREFER_MALE_VOICE ->
                spanishVoices.firstOrNull { v ->
                    v.name.contains("male", ignoreCase = true) &&
                    !v.name.contains("female", ignoreCase = true)
                }
                ?: engine.voice?.takeIf { it.locale.language == "es" }
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
            val result = engine.setLanguage(Locale("es", "ES"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale("es", "US"))
            }
            Log.d("AlfredTTS", "Sin voces españolas instaladas; usando locale es-ES/es-US")
        }

        engine.setPitch(prefs.pitch)
        engine.setSpeechRate(prefs.speechRate)
    }

    fun reloadTts() = configureTts()

    // ── STT ────────────────────────────────────────────────────────────────────

    fun startListening() {
        if (_uiState.value.status != AssistantStatus.IDLE) return
        if (!SpeechRecognizer.isRecognitionAvailable(getApplication())) {
            _uiState.value = _uiState.value.copy(
                error = "Reconocimiento de voz no disponible en este dispositivo."
            )
            return
        }
        retryWithCloud        = false
        retryWithoutLanguage  = false
        _uiState.value = _uiState.value.copy(
            status = AssistantStatus.LISTENING,
            partialText = "", error = null, networkWarning = null
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

    fun clearRateLimit() {
        _uiState.value = _uiState.value.copy(rateLimitUntilMs = null)
    }

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
            if (!retryWithoutLanguage) putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
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
            if (partial.isNotBlank()) _uiState.value = _uiState.value.copy(partialText = partial)
        }

        override fun onResults(results: Bundle?) {
            speechRecognizer?.destroy()
            speechRecognizer = null
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isNotBlank()) {
                handleVoiceInput(text)
            } else {
                _uiState.value = _uiState.value.copy(
                    status = AssistantStatus.IDLE, rmsLevel = 0f, partialText = "",
                    error = "No se recogió ningún texto. Inténtalo de nuevo."
                )
            }
        }

        override fun onError(error: Int) {
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

            if (error == 11 && !retryWithCloud) {
                retryWithCloud = true
                _uiState.value = _uiState.value.copy(partialText = "", error = null)
                speechRecognizer = buildRecognizer().also { r ->
                    r.setRecognitionListener(recognitionListener)
                    r.startListening(buildIntent())
                }
                return
            }
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
                SpeechRecognizer.ERROR_NO_MATCH        -> "No entendí nada. Inténtalo de nuevo."
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT  -> "Sin voz detectada. Inténtalo de nuevo."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El reconocedor está ocupado. Espera un momento."
                SpeechRecognizer.ERROR_AUDIO           -> "Error de audio. Comprueba el micrófono."
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Sin conexión a Internet."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Sin permisos de micrófono."
                11 -> "Reconocedor desconectado incluso en la nube. Comprueba la red."
                13 -> "Idioma no disponible. Ve a Ajustes → Accesibilidad → Texto a voz y descarga el español."
                else -> "Error del reconocedor (código $error)."
            }
            _uiState.value = _uiState.value.copy(
                status = AssistantStatus.IDLE, rmsLevel = 0f, partialText = "", error = message
            )
        }
    }

    // ── Lógica de respuesta ────────────────────────────────────────────────────

    private fun handleVoiceInput(text: String) {
        _uiState.value = _uiState.value.copy(
            status      = AssistantStatus.THINKING,
            userText    = text,
            partialText = "",
            responseText = "",
            brainUsed   = null,
            rmsLevel    = 0f,
            error       = null,
            networkWarning = null
        )
        viewModelScope.launch {
            // 1. Comandos locales y calculadora: nunca pasan por ningún modelo.
            val cmd = LocalCommandRecognizer.match(text)
            if (cmd != null) {
                executeLocalCommand(cmd)
                return@launch
            }

            // 2. Todo lo demás pasa por el router (Gemini → Gemma en cascada).
            val result = router.route(
                text                    = text,
                existingRateLimitUntilMs = _uiState.value.rateLimitUntilMs,
                systemInstruction       = AlfredPersona.SYSTEM_PROMPT,
                toolDeclarations        = ToolRegistry.declarations(),
                toolExecutor            = { name, args ->
                    val res = ToolRegistry.execute(name, args, getApplication())
                    val perm = res.optString("needs_permission")
                    if (perm.isNotBlank()) _permissionRequest.emit(perm)
                    res
                }
            )

            when (result) {
                is RouteResult.Success -> {
                    val data = result.data

                    // Si este turno generó un NUEVO rate limit, actualízalo y anuncia brevemente.
                    if (data.newRateLimitUntilMs != null) {
                        _uiState.value = _uiState.value.copy(rateLimitUntilMs = data.newRateLimitUntilMs)
                    }

                    _uiState.value = _uiState.value.copy(
                        status       = AssistantStatus.SPEAKING,
                        responseText = data.text,
                        brainUsed    = data.brain
                    )

                    // Cuando Gemma responde porque Gemini entró en 429, añadimos un aviso verbal.
                    val spokenText = if (data.newRateLimitUntilMs != null && data.brain == Brain.GEMMA) {
                        "Mi servidor descansa un momento, respondo con lo que sé. ${data.text}"
                    } else {
                        data.text
                    }
                    tts?.speak(spokenText, TextToSpeech.QUEUE_FLUSH, null, "resp")
                }

                RouteResult.NeedsInternet -> {
                    val reply = "Para eso necesito conexión a internet, Mister."
                    _uiState.value = _uiState.value.copy(
                        status       = AssistantStatus.SPEAKING,
                        responseText = reply,
                        brainUsed    = null
                    )
                    tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "needs_inet")
                }
            }
        }
    }

    private suspend fun executeLocalCommand(cmd: LocalCommand) {
        val result = withContext(Dispatchers.IO) {
            ToolRegistry.execute(cmd.tool, cmd.args, getApplication())
        }
        val needsPerm = result.optString("needs_permission")
        if (needsPerm.isNotBlank()) _permissionRequest.emit(needsPerm)
        val reply = localReply(cmd.tool, cmd.args, result)
        _uiState.value = _uiState.value.copy(
            status = AssistantStatus.SPEAKING, responseText = reply, brainUsed = null
        )
        tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "local_cmd")
    }

    private fun localReply(tool: String, args: JSONObject, result: JSONObject): String {
        if (result.optString("needs_permission").isNotBlank()) return when (tool) {
            "call_contact" -> "Necesito acceso a tus contactos, Mister. Concédeme el permiso y repítelo."
            else -> "Necesito un permiso del sistema para eso, Mister."
        }
        val ok = result.optBoolean("success", false)
        return when (tool) {
            "call_contact" -> if (ok) {
                "Llamando a ${result.optString("contact", args.optString("name"))}, Mister."
            } else when (result.optString("error")) {
                "contact_not_found" -> "No encuentro a ${args.optString("name")} en tu agenda, Mister."
                "multiple_matches"  -> {
                    val arr   = result.optJSONArray("names")
                    val names = if (arr != null) (0 until arr.length()).map { arr.getString(it) } else emptyList()
                    "Tengo varios contactos con ese nombre: ${names.joinToString(", ")}. ¿A cuál te refieres, Mister?"
                }
                "too_many_matches" -> "Hay demasiados contactos con ese nombre, Mister. Sé más específico."
                else -> "No pude marcar el número, Mister."
            }
            "call_number"     -> if (ok) "Marcando el ${result.optString("dialed", args.optString("number"))}, Mister."
                                 else "No pude marcar ese número, Mister."
            "open_app"        -> if (ok) "Abriendo ${result.optString("opened", args.optString("app_name"))}, Mister."
                                 else "No encontré esa aplicación instalada, Mister."
            "set_alarm"       -> if (ok) {
                val h = result.optInt("hour",   args.optInt("hour"))
                val m = result.optInt("minute", args.optInt("minute"))
                val time = if (m == 0) "las $h en punto" else "las $h y ${"%02d".format(m)}"
                "Alarma puesta a $time, Mister."
            } else "No pude crear la alarma, Mister."
            "set_timer"       -> if (ok) {
                val min = result.optInt("duration_minutes", args.optInt("duration_minutes"))
                "Temporizador de $min ${if (min == 1) "minuto" else "minutos"} en marcha, Mister."
            } else "No pude crear el temporizador, Mister."
            "start_navigation" -> if (ok) "Te llevo a ${result.optString("destination", args.optString("destination"))}, Mister."
                                  else "No pude abrir la navegación, Mister."
            "get_datetime"    -> result.optString("datetime").takeIf { it.isNotBlank() }
                                 ?: "No pude obtener la hora, Mister."
            else -> if (ok) "Hecho, Mister." else "Algo fue mal, Mister."
        }
    }

    override fun onCleared() {
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        localLlm.close()
        super.onCleared()
    }
}
