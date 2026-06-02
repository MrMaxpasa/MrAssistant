package com.example.mrassistant

import android.content.Intent
import android.provider.AlarmClock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Registro de todas las herramientas disponibles para Alfred.
 * Llama a esta función una sola vez desde AssistantViewModel.init.
 *
 * ── Cómo añadir una herramienta nueva ────────────────────────────────────
 * Copia el bloque de set_timer como plantilla y cambia:
 *   • name      → identificador snake_case que Gemini usará.
 *   • description → para qué sirve (Gemini lee esto para decidir cuándo llamarla).
 *   • parameters  → schema JSON de los argumentos (igual que OpenAI tool_calling).
 *   • handler   → lógica Kotlin que ejecuta la acción y devuelve un JSONObject.
 * ─────────────────────────────────────────────────────────────────────────
 */
fun registerAppTools() {

    // ── get_datetime ──────────────────────────────────────────────────────
    // Sin parámetros. Devuelve fecha y hora del dispositivo, sin internet.
    ToolRegistry.register(
        name = "get_datetime",
        declaration = JSONObject().apply {
            put("name", "get_datetime")
            put("description", "Devuelve la fecha y hora actuales del dispositivo. No requiere internet.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            })
        }
    ) { _, _ ->
        val fmt = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy. Son las HH:mm.", Locale("es", "ES"))
        JSONObject().put("datetime", fmt.format(Calendar.getInstance().time))
    }

    // ── set_timer ─────────────────────────────────────────────────────────
    // Lanza un Intent AlarmClock.ACTION_SET_TIMER hacia la app de reloj del sistema.
    // EXTRA_SKIP_UI=true crea el temporizador en silencio sin abrir la app.
    ToolRegistry.register(
        name = "set_timer",
        declaration = JSONObject().apply {
            put("name", "set_timer")
            put("description", "Crea un temporizador en la app de reloj del sistema.")
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("duration_minutes", JSONObject().apply {
                        put("type", "integer")
                        put("description", "Duración en minutos")
                    })
                    put("label", JSONObject().apply {
                        put("type", "string")
                        put("description", "Etiqueta opcional del temporizador")
                    })
                })
                put("required", JSONArray().put("duration_minutes"))
            })
        }
    ) { args, context ->
        val minutes = args.getInt("duration_minutes")
        val label = args.optString("label", "Temporizador")
        runCatching {
            context.startActivity(
                Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
            JSONObject()
                .put("success", true)
                .put("duration_minutes", minutes)
                .put("label", label)
        }.getOrElse { e ->
            JSONObject()
                .put("success", false)
                .put("error", e.message ?: "Error desconocido")
        }
    }

    registerWeatherTool()

    // ── Aquí irán las herramientas futuras ────────────────────────────────
    // set_alarm, make_call, send_message, open_app …
}
