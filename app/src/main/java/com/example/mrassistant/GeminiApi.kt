package com.example.mrassistant

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "GeminiApi"

class NoInternetException : Exception()
/**
 * retryAfterSeconds: segundos que pide esperar la API (null = límite diario sin dato exacto).
 * pendingToolSummary: si el 429 llegó tras ejecutar herramientas, contiene el resultado
 * de esas herramientas para que otro modelo pueda redactar la respuesta final.
 */
class RateLimitException(
    val retryAfterSeconds: Long? = null,
    val pendingToolSummary: String? = null
) : Exception()

object GeminiApi {

    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent"

    private const val MAX_TOOL_TURNS = 5

    suspend fun generateContent(
        apiKey: String,
        userMessage: String,
        systemInstruction: String,
        toolDeclarations: JSONObject? = null,
        toolExecutor: suspend (name: String, args: JSONObject) -> JSONObject = { _, _ -> JSONObject() }
    ): String = withContext(Dispatchers.IO) {

        val contents = JSONArray().apply { put(userTurn(userMessage)) }

        repeat(MAX_TOOL_TURNS) {
            // Si el 429 llega aquí, contents puede tener ya resultados de herramientas.
            // Los extraemos y los incluimos en la excepción para que BrainRouter
            // pueda pasárselos a Gemma como contexto para redactar la respuesta final.
            val response = try {
                post(apiKey, buildBody(systemInstruction, contents, toolDeclarations))
            } catch (e: RateLimitException) {
                throw RateLimitException(e.retryAfterSeconds, extractToolSummary(contents))
            }
            val parts = response
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")

            val functionCalls = (0 until parts.length())
                .map { parts.getJSONObject(it) }
                .filter { it.has("functionCall") }

            if (functionCalls.isEmpty()) {
                return@withContext (0 until parts.length())
                    .mapNotNull { parts.getJSONObject(it).optString("text").takeIf { s -> s.isNotBlank() } }
                    .joinToString(" ")
                    .trim()
                    .ifBlank { "Hecho." }
            }

            contents.put(JSONObject().apply {
                put("role", "model")
                put("parts", parts)
            })

            val funcResponses = JSONArray()
            for (callPart in functionCalls) {
                val call = callPart.getJSONObject("functionCall")
                val name = call.getString("name")
                val args = call.optJSONObject("args") ?: JSONObject()
                val result = toolExecutor(name, args)
                funcResponses.put(JSONObject().apply {
                    put("functionResponse", JSONObject().apply {
                        put("name", name)
                        put("response", result)
                    })
                })
            }

            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", funcResponses)
            })
        }

        throw Exception("Demasiadas llamadas a herramientas encadenadas.")
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Extrae resultados de herramientas ya ejecutados del historial de contents.
     * Devuelve null si no hay ninguno (el 429 llegó antes de que se llamara alguna herramienta).
     */
    private fun extractToolSummary(contents: JSONArray): String? {
        val sb = StringBuilder()
        for (i in 0 until contents.length()) {
            val content = contents.getJSONObject(i)
            if (content.optString("role") != "user") continue
            val parts = content.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val fr = parts.getJSONObject(j).optJSONObject("functionResponse") ?: continue
                val name = fr.optString("name", "herramienta")
                val resp = fr.optJSONObject("response")?.toString() ?: continue
                sb.append("$name: $resp\n")
            }
        }
        return sb.toString().trim().takeIf { it.isNotBlank() }
    }

    private fun userTurn(text: String): JSONObject = JSONObject().apply {
        put("role", "user")
        put("parts", JSONArray().put(JSONObject().put("text", text)))
    }

    private fun buildBody(
        systemInstruction: String,
        contents: JSONArray,
        toolDeclarations: JSONObject?
    ): JSONObject = JSONObject().apply {
        put("system_instruction", JSONObject().apply {
            put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
        })
        put("contents", contents)
        if (toolDeclarations != null) {
            put("tools", JSONArray().put(toolDeclarations))
        }
    }

    /**
     * Un reintento silencioso de 2 s solo cuando la API no indicó tiempo de espera.
     * Si incluye retryAfterSeconds, lanza inmediatamente para mostrar la cuenta atrás.
     */
    private suspend fun post(apiKey: String, body: JSONObject): JSONObject {
        try {
            return doPost(apiKey, body)
        } catch (e: RateLimitException) {
            if (e.retryAfterSeconds != null) throw e
            // 429 sin tiempo de espera → un reintento silencioso a los 2 s
            Log.w(TAG, "429 sin Retry-After → reintento en 2 s")
            delay(2_000L)
        }
        return doPost(apiKey, body)
    }

    private fun doPost(apiKey: String, body: JSONObject): JSONObject {
        val conn = URL("$ENDPOINT?key=$apiKey").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 45_000
            conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
            val code = conn.responseCode

            if (code == 429) {
                val errorBody = runCatching {
                    conn.errorStream?.bufferedReader()?.readText() ?: ""
                }.getOrDefault("")
                val retryAfterHeader = conn.getHeaderField("Retry-After")
                Log.w(TAG, "HTTP 429 – rate limit | Retry-After=$retryAfterHeader | body=$errorBody")
                throw RateLimitException(parseRetryAfter(conn, errorBody))
            }

            if (code !in 200..299) {
                val errorBody = runCatching {
                    conn.errorStream?.bufferedReader()?.readText() ?: ""
                }.getOrDefault("")
                Log.e(TAG, "HTTP $code – error de servidor | body=$errorBody")
                throw Exception("Error del servidor: $code")
            }

            val responseText = conn.inputStream.bufferedReader().readText()
            Log.d(TAG, "HTTP $code OK | ${responseText.length} chars")
            return JSONObject(responseText)

        } catch (e: RateLimitException) {
            throw e
        } catch (e: java.io.IOException) {
            Log.e(TAG, "IOException: ${e.message}")
            throw NoInternetException()
        } finally {
            conn.disconnect()
        }
    }

    /** Extrae los segundos de espera de la cabecera Retry-After o del cuerpo JSON de error. */
    private fun parseRetryAfter(conn: HttpURLConnection, errorBody: String): Long? {
        conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.let { return it }
        return try {
            val details = JSONObject(errorBody)
                .optJSONObject("error")
                ?.optJSONArray("details") ?: return null
            for (i in 0 until details.length()) {
                val retryDelay = details.getJSONObject(i).optString("retryDelay")
                    .takeIf { it.isNotBlank() } ?: continue
                return parseDelayString(retryDelay)
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /** Convierte "30s", "2m", "1h" → segundos. Sin unidad se asume segundos. */
    private fun parseDelayString(delay: String): Long? {
        val m = Regex("""(\d+)([smh]?)""").find(delay) ?: return null
        val v = m.groupValues[1].toLongOrNull() ?: return null
        return when (m.groupValues[2]) {
            "m"  -> v * 60
            "h"  -> v * 3600
            else -> v
        }
    }
}
