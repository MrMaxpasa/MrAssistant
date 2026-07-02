package com.example.mrassistant

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LlmLoadState { IDLE, COPYING, LOADING, READY, ERROR }

data class LocalLlmUiState(
    val loadState: LlmLoadState     = LlmLoadState.IDLE,
    val modelExistsInternally: Boolean = false,
    val prompt: String              = "",
    val response: String            = "",
    val isGenerating: Boolean       = false,
    val errorMessage: String?       = null,
    val copyProgress: Float         = 0f,  // 0..1
    val copyBytesLabel: String      = ""   // e.g. "342 MB / 1.2 GB"
)

class LocalLlmViewModel(app: Application) : AndroidViewModel(app) {

    private val manager = LocalLlmManager(app)

    private val _state = MutableStateFlow(
        LocalLlmUiState(modelExistsInternally = manager.modelFileExists())
    )
    val state: StateFlow<LocalLlmUiState> = _state.asStateFlow()

    val internalModelPath: String get() = manager.defaultModelPath

    // ── Eventos de UI ──────────────────────────────────────────────────────

    fun onPromptChanged(text: String) {
        _state.value = _state.value.copy(prompt = text)
    }

    // ── Acciones ───────────────────────────────────────────────────────────

    /**
     * El usuario eligió un archivo via SAF. Copiamos al filesDir interno y cargamos.
     * El acceso a la URI está garantizado por el sistema; no hay problema de owner.
     */
    fun onUriSelected(uri: Uri) {
        _state.value = _state.value.copy(
            loadState      = LlmLoadState.COPYING,
            errorMessage   = null,
            copyProgress   = 0f,
            copyBytesLabel = ""
        )
        viewModelScope.launch {
            when (val result = manager.copyFromUri(uri) { copied, total ->
                _state.value = _state.value.copy(
                    copyProgress   = if (total > 0L) copied.toFloat() / total else 0f,
                    copyBytesLabel = "${formatBytes(copied)} / ${formatBytes(total)}"
                )
            }) {
                CopyResult.Success -> {
                    _state.value = _state.value.copy(modelExistsInternally = true)
                    startLoad()
                }
                is CopyResult.NotFound -> _state.value = _state.value.copy(
                    loadState    = LlmLoadState.ERROR,
                    errorMessage = result.hint
                )
                is CopyResult.Failure -> _state.value = _state.value.copy(
                    loadState    = LlmLoadState.ERROR,
                    errorMessage = "Error al copiar el modelo:\n${result.cause.message}"
                )
            }
        }
    }

    /** El modelo ya existe en filesDir; carga MediaPipe directamente. */
    fun loadExistingModel() {
        if (!manager.modelFileExists()) {
            _state.value = _state.value.copy(
                modelExistsInternally = false,
                loadState = LlmLoadState.IDLE
            )
            return
        }
        startLoad()
    }

    fun generate() {
        val prompt = _state.value.prompt.trim()
        if (prompt.isBlank() || _state.value.loadState != LlmLoadState.READY) return
        _state.value = _state.value.copy(
            isGenerating = true,
            response     = "",
            errorMessage = null
        )
        viewModelScope.launch {
            runCatching { manager.generate(prompt) }.fold(
                onSuccess = { reply ->
                    _state.value = _state.value.copy(isGenerating = false, response = reply)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isGenerating = false,
                        errorMessage = "Error al generar: ${e.message}"
                    )
                }
            )
        }
    }

    override fun onCleared() {
        manager.close()
        super.onCleared()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun startLoad() {
        _state.value = _state.value.copy(loadState = LlmLoadState.LOADING, errorMessage = null)
        viewModelScope.launch {
            manager.load().fold(
                onSuccess = {
                    _state.value = _state.value.copy(loadState = LlmLoadState.READY)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        loadState    = LlmLoadState.ERROR,
                        errorMessage = classifyLoadError(e)
                    )
                }
            )
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000L     -> "%d MB".format(bytes / 1_000_000L)
        bytes >= 1_000L         -> "%d KB".format(bytes / 1_000L)
        else                    -> "$bytes B"
    }

    private fun classifyLoadError(e: Throwable): String = when {
        e is OutOfMemoryError ||
        e.message?.contains("memory", ignoreCase = true) == true ->
            "Memoria insuficiente. Cierra otras apps y vuelve a intentarlo."

        e.message?.contains("not found", ignoreCase = true) == true ||
        e.message?.contains("No such file", ignoreCase = true) == true ||
        e.message?.contains("open failed", ignoreCase = true) == true ->
            "Archivo no encontrado en la ruta interna.\n${manager.defaultModelPath}"

        else -> "Error al cargar el modelo:\n${e.message}"
    }
}
