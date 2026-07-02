package com.example.mrassistant

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.Normalizer
import java.util.Calendar
import java.util.TimeZone

private const val ROUTER_TAG = "BrainRouter"

enum class Brain { GEMINI, GEMMA }
enum class GemmaStatus { UNAVAILABLE, LOADING, READY }

data class RouteSuccess(
    val text: String,
    val brain: Brain,
    /** Non-null solo cuando ESTA llamada recibió un 429 de Gemini por primera vez. */
    val newRateLimitUntilMs: Long? = null
)

sealed class RouteResult {
    data class Success(val data: RouteSuccess) : RouteResult()
    /** La consulta requiere datos en tiempo real y no hay conexión. */
    object NeedsInternet : RouteResult()
}

/**
 * Router central de decisión. ÚNICA fuente de verdad sobre qué cerebro responde.
 *
 * Árbol de decisión:
 *  1. Consulta necesita internet + sin conexión → NeedsInternet (Alfred avisa)
 *  2. Hay internet + sin rate limit → intenta Gemini
 *       429           → extrae tool results → fallback Gemma (cuenta atrás sigue)
 *       NoInternet    → fallback Gemma (si no necesita internet real-time)
 *       Otro error    → fallback Gemma
 *  3. Sin internet O rate limit activo → Gemma directamente
 *       Gemma cargando → espera hasta GEMMA_WAIT_MS
 *       Timeout/error  → mensaje honesto
 */
class BrainRouter(
    private val context: Context,
    private val localLlm: LocalLlmManager,
    private val apiKey: String
) {
    companion object {
        private const val GEMMA_WAIT_MS = 10_000L

        // Palabras clave que indican datos en tiempo real que Gemma no puede proporcionar.
        // Sin acento (se comparan con texto normalizado).
        private val INTERNET_KEYWORDS = setOf(
            "tiempo", "clima", "lluvia", "temperatura", "pronostico",
            "meteorologia", "va a llover", "va a hacer frio", "va a hacer calor",
            "prevision", "noticias", "actualidad",
            "busca", "buscame", "encuentrame", "buscar en"
        )
    }

    suspend fun route(
        text: String,
        existingRateLimitUntilMs: Long?,
        systemInstruction: String,
        toolDeclarations: JSONObject?,
        toolExecutor: suspend (name: String, args: JSONObject) -> JSONObject
    ): RouteResult {
        val internet = hasInternet()
        val needsNet = requiresInternet(text)

        if (!internet && needsNet) return RouteResult.NeedsInternet

        val geminiAvailable = internet && existingRateLimitUntilMs == null

        if (geminiAvailable) {
            try {
                val reply = GeminiApi.generateContent(
                    apiKey            = apiKey,
                    userMessage       = text,
                    systemInstruction = systemInstruction,
                    toolDeclarations  = toolDeclarations,
                    toolExecutor      = toolExecutor
                )
                return RouteResult.Success(RouteSuccess(reply, Brain.GEMINI))

            } catch (e: RateLimitException) {
                Log.w(ROUTER_TAG, "Gemini 429 → fallback Gemma | toolSummary=${e.pendingToolSummary != null}")
                val untilMs = if (e.retryAfterSeconds != null)
                    System.currentTimeMillis() + e.retryAfterSeconds * 1000L
                else nextMidnightPacificMs()

                // Si las herramientas ya corrieron antes del 429, construimos un prompt
                // enriquecido para que Gemma redacte la respuesta con ese contexto.
                val gemmaPrompt = if (!e.pendingToolSummary.isNullOrBlank()) {
                    "El usuario preguntó: \"$text\"\n" +
                    "Una herramienta ya devolvió este resultado:\n${e.pendingToolSummary}\n" +
                    "Redacta una respuesta natural y breve para el usuario."
                } else text

                val reply = askGemma(gemmaPrompt)
                    ?: "Mi servidor alcanzó su límite y mi cerebro de reserva aún se despierta, Mister."
                return RouteResult.Success(RouteSuccess(reply, Brain.GEMMA, untilMs))

            } catch (e: NoInternetException) {
                Log.w(ROUTER_TAG, "Gemini sin red → fallback Gemma")
                if (needsNet) return RouteResult.NeedsInternet
                val reply = askGemma(text)
                    ?: "Sin conexión y mi cerebro de reserva aún se está despertando, Mister."
                return RouteResult.Success(RouteSuccess(reply, Brain.GEMMA))

            } catch (e: Exception) {
                Log.w(ROUTER_TAG, "Error Gemini (${e.message}) → fallback Gemma")
                if (needsNet) return RouteResult.Success(
                    RouteSuccess("Hubo un error al conectar con el servidor, Mister.", Brain.GEMINI)
                )
                val reply = askGemma(text)
                    ?: "No pude contactar el servidor y mi cerebro local tampoco está listo, Mister."
                return RouteResult.Success(RouteSuccess(reply, Brain.GEMMA))
            }
        }

        // Sin Gemini disponible (sin internet o rate limit activo)
        if (needsNet) return RouteResult.NeedsInternet

        val reply = askGemma(text)
            ?: "Mi cerebro de reserva aún se está despertando, Mister. Inténtalo en un momento."
        return RouteResult.Success(RouteSuccess(reply, Brain.GEMMA))
    }

    /**
     * Espera hasta GEMMA_WAIT_MS a que el modelo cargue, luego genera.
     * Devuelve null si el modelo no está disponible o falla.
     */
    private suspend fun askGemma(text: String): String? {
        val deadline = System.currentTimeMillis() + GEMMA_WAIT_MS
        while (!localLlm.isLoaded()) {
            if (System.currentTimeMillis() >= deadline) {
                Log.w(ROUTER_TAG, "Gemma: timeout esperando carga (10 s)")
                return null
            }
            delay(500L)
        }
        return runCatching { localLlm.generate(text) }.getOrElse { e ->
            Log.e(ROUTER_TAG, "Error en Gemma.generate: ${e.message}")
            null
        }
    }

    fun hasInternet(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net = cm.activeNetwork ?: return false
            val cap = cm.getNetworkCapabilities(net) ?: return false
            cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    private fun requiresInternet(text: String): Boolean {
        val norm = normalize(text)
        return INTERNET_KEYWORDS.any { norm.contains(it) }
    }

    private fun normalize(s: String): String {
        val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase().trim()
    }

    private fun nextMidnightPacificMs(): Long {
        val pacific = TimeZone.getTimeZone("America/Los_Angeles")
        return Calendar.getInstance(pacific).apply {
            add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
