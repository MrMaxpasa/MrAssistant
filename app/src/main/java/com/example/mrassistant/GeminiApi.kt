package com.example.mrassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class NoInternetException : Exception()
class RateLimitException : Exception()

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
     * POST con backoff ante 429: intenta de inmediato, luego espera 2 s y 4 s.
     * Si el tercer intento también falla con 429 lanza [RateLimitException].
     */
    private suspend fun post(apiKey: String, body: JSONObject): JSONObject {
        val backoff = listOf(2_000L, 4_000L)
        for (wait in backoff) {
            try {
                return doPost(apiKey, body)
            } catch (e: RateLimitException) {
                delay(wait)
            }
        }
        return doPost(apiKey, body) // último intento; si falla, lanza
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
            if (code == 429) throw RateLimitException()
            if (code !in 200..299) throw Exception("Error del servidor: $code")
            return JSONObject(conn.inputStream.bufferedReader().readText())
        } catch (e: java.io.IOException) {
            throw NoInternetException()
        } finally {
            conn.disconnect()
        }
    }
}
