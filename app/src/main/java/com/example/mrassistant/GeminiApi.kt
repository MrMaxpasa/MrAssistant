package com.example.mrassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class NoInternetException : Exception()

object GeminiApi {

    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

    // Número máximo de turnos herramienta→modelo en una sola petición del usuario.
    // Protege contra bucles infinitos si el modelo llama herramientas repetidamente.
    private const val MAX_TOOL_TURNS = 5

    /**
     * Envía [userMessage] a Gemini y gestiona el bucle de function calling.
     *
     * Flujo por turno:
     *   POST → si la respuesta contiene functionCall → ejecuta via [toolExecutor]
     *        → devuelve los resultados en el turno siguiente → repite
     *        → cuando la respuesta es solo texto → devuelve ese texto
     *
     * @param toolDeclarations  Objeto {"function_declarations":[...]} o null (sin herramientas).
     * @param toolExecutor      Lambda que recibe (nombre, args) y devuelve el resultado JSON.
     */
    suspend fun generateContent(
        apiKey: String,
        userMessage: String,
        systemInstruction: String,
        toolDeclarations: JSONObject? = null,
        toolExecutor: suspend (name: String, args: JSONObject) -> JSONObject = { _, _ -> JSONObject() }
    ): String = withContext(Dispatchers.IO) {

        // El historial crece con cada turno: usuario → modelo → resultados → modelo…
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
                // Sin llamadas a herramientas: la respuesta es texto final.
                return@withContext (0 until parts.length())
                    .mapNotNull { parts.getJSONObject(it).optString("text").takeIf { s -> s.isNotBlank() } }
                    .joinToString(" ")
                    .trim()
                    .ifBlank { "Hecho." }
            }

            // Añade la respuesta del modelo (con sus functionCalls) al historial.
            contents.put(JSONObject().apply {
                put("role", "model")
                put("parts", parts)
            })

            // Ejecuta cada herramienta y recoge los resultados.
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

            // Devuelve los resultados como turno de usuario y continúa el bucle.
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
        // Solo incluimos "tools" si hay herramientas registradas.
        if (toolDeclarations != null) {
            put("tools", JSONArray().put(toolDeclarations))
        }
    }

    /** POST síncrono — llámalo solo desde Dispatchers.IO. */
    private fun post(apiKey: String, body: JSONObject): JSONObject {
        val conn = URL("$ENDPOINT?key=$apiKey").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 45_000  // más tiempo: puede haber varios turnos de herramienta
            conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code == 429) throw Exception("Límite de solicitudes alcanzado (429). Espera un momento.")
            if (code !in 200..299) throw Exception("Error del servidor: $code")
            return JSONObject(conn.inputStream.bufferedReader().readText())
        } catch (e: java.io.IOException) {
            throw NoInternetException()
        } finally {
            conn.disconnect()
        }
    }
}
