package com.example.mrassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class NoInternetException : Exception()
/** retryAfterSeconds: segundos que pide esperar la API (null = límite diario sin dato exacto). */
class RateLimitException(val retryAfterSeconds: Long? = null) : Exception()

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
            val response = post(apiKey, buildBody(systemInstruction, contents, toolDeclarations))
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
                throw RateLimitException(parseRetryAfter(conn, errorBody))
            }
            if (code !in 200..299) throw Exception("Error del servidor: $code")
            return JSONObject(conn.inputStream.bufferedReader().readText())
        } catch (e: RateLimitException) {
            throw e
        } catch (e: java.io.IOException) {
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
