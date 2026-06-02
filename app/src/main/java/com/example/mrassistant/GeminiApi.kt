package com.example.mrassistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Excepción específica para "sin red": permite al ViewModel distinguirla
// de otros errores del servidor o de parseo y mostrar un mensaje amable.
class NoInternetException : Exception()

object GeminiApi {

    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

    suspend fun generateContent(apiKey: String, prompt: String): String =
        withContext(Dispatchers.IO) {
            val conn = (URL("$ENDPOINT?key=$apiKey").openConnection()) as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000

                val body = JSONObject().apply {
                    put("contents", JSONArray().apply {
                        put(JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().put("text", prompt))
                            })
                        })
                    })
                }.toString()

                conn.outputStream.bufferedWriter().use { it.write(body) }

                val code = conn.responseCode
                if (code == 429) throw Exception("Límite de solicitudes alcanzado (429). Espera un momento.")
                if (code !in 200..299) throw Exception("Error del servidor: $code")

                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")
            } catch (e: java.io.IOException) {
                // IOException cubre UnknownHostException, SocketException,
                // ConnectException y cualquier otro fallo de red.
                throw NoInternetException()
            } finally {
                conn.disconnect()
            }
        }
}
