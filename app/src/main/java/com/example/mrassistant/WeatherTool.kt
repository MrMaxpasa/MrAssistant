package com.example.mrassistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

// ── WMO weather interpretation codes → español ────────────────────────────
private val WMO = mapOf(
    0  to "Cielo despejado",
    1  to "Principalmente despejado",  2 to "Parcialmente nublado",    3 to "Nublado",
    45 to "Niebla",                    48 to "Niebla con escarcha",
    51 to "Llovizna ligera",           53 to "Llovizna moderada",      55 to "Llovizna densa",
    61 to "Lluvia ligera",             63 to "Lluvia moderada",        65 to "Lluvia intensa",
    71 to "Nevada ligera",             73 to "Nevada moderada",        75 to "Nevada intensa",
    77 to "Granizo",
    80 to "Chubascos ligeros",         81 to "Chubascos moderados",    82 to "Chubascos fuertes",
    85 to "Chubascos de nieve",        86 to "Chubascos de nieve intensos",
    95 to "Tormenta",                  96 to "Tormenta con granizo",   99 to "Tormenta fuerte con granizo"
)

fun registerWeatherTool() {
    ToolRegistry.register(
        name = "get_weather",
        declaration = JSONObject().apply {
            put("name", "get_weather")
            put("description",
                "Devuelve el tiempo meteorológico actual y la previsión del día. " +
                "Si no se indica ciudad, usa la ubicación del dispositivo (necesita permiso). " +
                "Si se indica una ciudad, obtiene sus coordenadas vía geocoding y no usa el GPS.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("location", JSONObject().apply {
                        put("type", "string")
                        put("description", "Nombre de la ciudad (opcional). Sin este campo se usa la ubicación actual.")
                    })
                })
            })
        }
    ) { args, context ->
        val cityInput = args.optString("location", "").trim()
        try {
            val (lat, lon, place) = if (cityInput.isBlank()) {
                // ── Rama GPS ──────────────────────────────────────────────
                val granted = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (!granted) {
                    // La clave "needs_permission" es captada por AssistantViewModel
                    // para lanzar el diálogo del sistema de forma transparente.
                    return@register JSONObject()
                        .put("needs_permission", "location")
                        .put("error", "No tengo permiso para acceder a tu ubicación.")
                }

                val loc = getDeviceLocation(context)
                    ?: return@register JSONObject()
                        .put("error", "No pude obtener tu ubicación. Activa el GPS o dime una ciudad.")

                Triple(loc.first, loc.second, "tu ubicación")
            } else {
                // ── Rama geocoding ────────────────────────────────────────
                geocodeCity(cityInput)
                    ?: return@register JSONObject()
                        .put("error", "No encontré '$cityInput'. Prueba con otro nombre de ciudad.")
            }

            fetchWeather(lat, lon, place)
        } catch (e: java.io.IOException) {
            JSONObject().put("error", "Sin conexión a Internet para consultar el tiempo.")
        } catch (e: Exception) {
            JSONObject().put("error", "Error consultando el tiempo: ${e.message}")
        }
    }
}

// ── Ubicación del dispositivo ─────────────────────────────────────────────

@SuppressLint("MissingPermission")
private suspend fun getDeviceLocation(context: Context): Pair<Double, Double>? =
    suspendCancellableCoroutine { cont ->
        LocationServices.getFusedLocationProviderClient(context)
            .lastLocation
            .addOnSuccessListener { loc ->
                cont.resume(if (loc != null) loc.latitude to loc.longitude else null)
            }
            .addOnFailureListener { cont.resume(null) }
    }

// ── Geocoding Open-Meteo (sin API key) ────────────────────────────────────

private fun geocodeCity(city: String): Triple<Double, Double, String>? {
    val q = URLEncoder.encode(city, "UTF-8")
    val json = httpGet("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=es&format=json")
    val results = json.optJSONArray("results") ?: return null
    if (results.length() == 0) return null
    val r = results.getJSONObject(0)
    return Triple(r.getDouble("latitude"), r.getDouble("longitude"), r.getString("name"))
}

// ── Forecast Open-Meteo (sin API key) ─────────────────────────────────────

private fun fetchWeather(lat: Double, lon: Double, place: String): JSONObject {
    val url = "https://api.open-meteo.com/v1/forecast" +
        "?latitude=$lat&longitude=$lon" +
        "&current=temperature_2m,apparent_temperature,weathercode" +
        "&daily=temperature_2m_max,temperature_2m_min" +
        "&timezone=auto&forecast_days=1"

    val json  = httpGet(url)
    val cur   = json.getJSONObject("current")
    val daily = json.getJSONObject("daily")
    val code  = cur.getInt("weathercode")

    return JSONObject().apply {
        put("location",     place)
        put("description",  WMO[code] ?: "Condición desconocida (código $code)")
        put("temperature_c", cur.getDouble("temperature_2m"))
        val feels = cur.optDouble("apparent_temperature")
        if (!feels.isNaN()) put("feels_like_c", feels)
        put("max_c", daily.getJSONArray("temperature_2m_max").getDouble(0))
        put("min_c", daily.getJSONArray("temperature_2m_min").getDouble(0))
    }
}

// ── HTTP GET bloqueante (llamar solo desde Dispatchers.IO) ────────────────

private fun httpGet(urlString: String): JSONObject {
    val conn = URL(urlString).openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 10_000
        conn.readTimeout    = 10_000
        val code = conn.responseCode
        if (code !in 200..299) throw Exception("HTTP $code")
        return JSONObject(conn.inputStream.bufferedReader().readText())
    } finally {
        conn.disconnect()
    }
}
