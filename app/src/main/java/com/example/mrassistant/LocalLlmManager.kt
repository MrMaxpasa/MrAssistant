package com.example.mrassistant

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

private const val LLM_TAG = "LocalLlm"

const val MODEL_FILENAME = "gemma-2b-it-cpu-int4.bin"
const val MODEL_SUBDIR   = "models"

sealed class CopyResult {
    object Success       : CopyResult()
    data class NotFound(val hint: String) : CopyResult()
    data class Failure(val cause: Throwable) : CopyResult()
}

class LocalLlmManager(private val context: Context) {

    private var llm: LlmInference? = null

    val defaultModelPath: String
        get() = "${context.filesDir.absolutePath}/$MODEL_SUBDIR/$MODEL_FILENAME"

    private val destFile get() = File(context.filesDir, "$MODEL_SUBDIR/$MODEL_FILENAME")

    fun modelFileExists(): Boolean = destFile.exists() && destFile.length() > 0L

    /**
     * Copia el archivo elegido por el usuario via SAF al almacenamiento interno.
     * El sistema ya garantizó el acceso al conceder la URI; no hay problema de owner.
     * Siempre sobreescribe por si el usuario reemplaza el modelo.
     */
    suspend fun copyFromUri(
        uri: Uri,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> }
    ): CopyResult = withContext(Dispatchers.IO) {
        val dest = destFile
        dest.parentFile?.mkdirs()

        // Intentamos leer el tamaño para mostrar progreso (puede ser -1 si no disponible)
        val total = querySizeFromUri(uri)

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                copyStream(input, dest, total, onProgress)
            } ?: return@withContext CopyResult.Failure(
                IOException("El sistema no pudo abrir el archivo seleccionado")
            )
            CopyResult.Success
        } catch (e: Exception) {
            dest.delete()
            Log.e(LLM_TAG, "Error copiando desde URI", e)
            CopyResult.Failure(e)
        }
    }

    private fun querySizeFromUri(uri: Uri): Long =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val col = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (col != -1 && cursor.moveToFirst()) cursor.getLong(col) else -1L
        } ?: -1L

    private fun copyStream(
        input: InputStream,
        dest: File,
        total: Long,
        onProgress: (Long, Long) -> Unit
    ) {
        val buf = ByteArray(4 * 1024 * 1024) // 4 MB por chunk
        var copied = 0L
        dest.outputStream().use { out ->
            var n = input.read(buf)
            while (n >= 0) {
                out.write(buf, 0, n)
                copied += n
                onProgress(copied, total)
                n = input.read(buf)
            }
        }
    }

    // ── Inferencia ────────────────────────────────────────────────────────

    suspend fun load(modelPath: String = defaultModelPath): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(LLM_TAG, "Cargando modelo desde: $modelPath")
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(512)
                    .setTopK(40)
                    .setTemperature(0.7f)  // más bajo = más coherente
                    .setRandomSeed(101)
                    .build()
                llm = LlmInference.createFromOptions(context, options)
                Log.d(LLM_TAG, "Modelo cargado correctamente")
                Unit
            }
        }

    suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val model = llm ?: error("Modelo no cargado. Llama a load() primero.")
        val formatted = buildGemmaPrompt(prompt)
        Log.d(LLM_TAG, "=== PROMPT EXACTO ENVIADO AL MODELO ===\n$formatted\n=== FIN PROMPT ===")
        val raw = model.generateResponse(formatted)
        // 1. Elimina el token de cierre que MediaPipe a veces incluye
        val withoutToken = raw.trimEnd().removeSuffix("<end_of_turn>").trim()
        // 2. Si el modelo copió la pregunta al principio, la eliminamos
        val clean = stripLeadingRepetition(withoutToken, prompt)
        Log.d(LLM_TAG, "=== RESPUESTA MODELO ===\n$clean\n=======================")
        clean
    }

    /**
     * Formato de turno Gemma IT con un ejemplo few-shot para enseñarle el estilo.
     *
     * Estructura:
     *   instrucción + ejemplo de identidad (turno completo)
     *   → turno real del usuario
     *   → modelo completa a partir de aquí
     */
    private fun buildGemmaPrompt(userMessage: String): String =
        "<start_of_turn>user\n" +
        "$SYSTEM_INSTRUCTION\n\n" +
        "quién eres<end_of_turn>\n" +
        "<start_of_turn>model\n" +
        "Soy Alfred, el asistente personal de Mister. Dime, ¿en qué puedo ayudarte?<end_of_turn>\n" +
        "<start_of_turn>user\n" +
        "$userMessage<end_of_turn>\n" +
        "<start_of_turn>model\n"

    /**
     * Detecta si el modelo repitió la pregunta del usuario al inicio de su respuesta
     * y la elimina. Trabaja en texto normalizado para ser robusto a tildes y mayúsculas.
     * Solo actúa si la pregunta tiene 5+ caracteres para evitar falsos positivos.
     */
    private fun stripLeadingRepetition(response: String, userMessage: String): String {
        val normResp = normalizeForCmp(response)
        val normUser = normalizeForCmp(userMessage)
        if (normUser.length < 5 || !normResp.startsWith(normUser)) return response
        // Corta en el primer signo de puntuación o salto de línea tras la repetición
        val cut = response.indexOfFirst { it in ".?!\n" }
        if (cut == -1) return response
        return response.substring(cut + 1).trimStart().ifBlank { response }
    }

    private fun normalizeForCmp(s: String): String {
        val nfd = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase().trim()
    }

    companion object {
        // 5 reglas cortas. Los modelos pequeños siguen mejor frases concretas y breves.
        private const val SYSTEM_INSTRUCTION =
            "Eres Alfred, el asistente personal de Mister. " +
            "Nunca digas que eres Gemma, Gemini ni un modelo de Google. " +
            "Habla en primera persona (yo soy, yo puedo). " +
            "Nunca repitas la pregunta: empieza directamente con tu respuesta. " +
            "Responde en español, breve y sin listas ni símbolos."
    }

    fun isLoaded(): Boolean = llm != null

    fun close() {
        llm?.close()
        llm = null
        Log.d(LLM_TAG, "Modelo liberado de memoria")
    }
}
