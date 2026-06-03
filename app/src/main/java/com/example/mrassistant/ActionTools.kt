package com.example.mrassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale

fun registerActionTools() {
    registerCallNumber()
    registerCallContact()
    registerOpenApp()
    registerSetAlarm()
    registerStartNavigation()
    registerCreateCalendarEvent()
}

// ── call_number ───────────────────────────────────────────────────────────
// Usa cuando el usuario dicta dígitos. Limpia el número antes de marcarlo.
private fun registerCallNumber() = ToolRegistry.register(
    name = "call_number",
    declaration = JSONObject().apply {
        put("name", "call_number")
        put("description", "Abre el marcador con un número de teléfono ya introducido. Úsalo cuando el usuario diga un número. El usuario decide si llama.")
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("number", JSONObject()
                    .put("type", "string")
                    .put("description", "Número de teléfono; puede incluir +, espacios, guiones o palabras"))
            })
            put("required", JSONArray().put("number"))
        })
    }
) { args, context ->
    runCatching {
        val raw    = args.getString("number")
        val clean  = cleanNumber(raw)
        if (clean.length < 4) return@register JSONObject()
            .put("success", false)
            .put("error", "El número '$raw' tiene muy pocos dígitos tras limpiarlo.")
        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        JSONObject().put("success", true).put("dialed", clean)
    }.getOrElse { e -> JSONObject().put("success", false).put("error", e.message) }
}

// ── call_contact ──────────────────────────────────────────────────────────
// Usa cuando el usuario dice el nombre de una persona ("llama a mamá").
// Necesita permiso READ_CONTACTS; si no está concedido devuelve needs_permission.
private fun registerCallContact() = ToolRegistry.register(
    name = "call_contact",
    declaration = JSONObject().apply {
        put("name", "call_contact")
        put("description", "Busca un contacto por nombre en la agenda y abre el marcador con su número. Úsalo cuando el usuario mencione el nombre de una persona en lugar de un número.")
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("name", JSONObject()
                    .put("type", "string")
                    .put("description", "Nombre del contacto tal como podría estar en la agenda"))
            })
            put("required", JSONArray().put("name"))
        })
    }
) { args, context ->
    val granted = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CONTACTS
    ) == PackageManager.PERMISSION_GRANTED

    if (!granted) {
        return@register JSONObject()
            .put("needs_permission", "contacts")
            .put("error", "No tengo permiso para leer los contactos.")
    }

    val query = args.getString("name")

    // Consulta todos los contactos con número de teléfono y filtra en Kotlin
    // para soportar normalización de acentos que SQL no hace por defecto.
    val contacts = mutableListOf<Pair<String, String>>() // (id, displayName)
    context.contentResolver.query(
        ContactsContract.Contacts.CONTENT_URI,
        arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
        "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1",
        null, null
    )?.use { cursor ->
        while (cursor.moveToNext()) {
            val id   = cursor.getString(0) ?: continue
            val name = cursor.getString(1) ?: continue
            val nName  = normalize(name)
            val nQuery = normalize(query)
            if (nName.contains(nQuery) || nQuery.contains(nName)) {
                contacts.add(id to name)
            }
        }
    }

    when {
        contacts.isEmpty() -> JSONObject()
            .put("success", false)
            .put("error", "contact_not_found")
            .put("query", query)

        contacts.size > 3 -> JSONObject()
            .put("success", false)
            .put("error", "too_many_matches")
            .put("count", contacts.size)
            .put("examples", JSONArray().apply { contacts.take(3).forEach { put(it.second) } })

        contacts.size > 1 -> JSONObject()
            .put("success", false)
            .put("error", "multiple_matches")
            .put("names", JSONArray().apply { contacts.forEach { put(it.second) } })

        else -> {
            val (contactId, contactName) = contacts[0]
            var number: String? = null
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId), null
            )?.use { pc ->
                if (pc.moveToFirst()) number = pc.getString(0)
            }
            val clean = number?.let { cleanNumber(it) }
            if (clean.isNullOrBlank()) {
                return@register JSONObject()
                    .put("success", false)
                    .put("error", "no_phone_number")
                    .put("contact", contactName)
            }
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_DIAL, Uri.parse("tel:$clean"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                JSONObject().put("success", true).put("contact", contactName).put("dialed", clean)
            }.getOrElse { e -> JSONObject().put("success", false).put("error", e.message) }
        }
    }
}

// ── open_app ──────────────────────────────────────────────────────────────
private fun registerOpenApp() = ToolRegistry.register(
    name = "open_app",
    declaration = JSONObject().apply {
        put("name", "open_app")
        put("description", "Abre una app instalada buscándola por su nombre visible en el lanzador.")
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("app_name", JSONObject()
                    .put("type", "string")
                    .put("description", "Nombre de la app tal como aparece en el lanzador"))
            })
            put("required", JSONArray().put("app_name"))
        })
    }
) { args, context ->
    val query = args.getString("app_name")
    val pm = context.packageManager
    val match = pm.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
    ).firstOrNull { ri ->
        val label  = ri.loadLabel(pm).toString()
        val nLabel = normalize(label)
        val nQuery = normalize(query)
        nLabel.contains(nQuery) || nQuery.contains(nLabel)
    }
    if (match == null) {
        JSONObject().put("success", false).put("error", "app_not_found").put("query", query)
    } else {
        runCatching {
            val pkg    = match.activityInfo.packageName
            val launch = pm.getLaunchIntentForPackage(pkg)
                ?: return@register JSONObject().put("success", false).put("error", "no_launch_intent")
            context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            JSONObject().put("success", true).put("opened", match.loadLabel(pm).toString())
        }.getOrElse { e -> JSONObject().put("success", false).put("error", e.message) }
    }
}

// ── set_alarm ─────────────────────────────────────────────────────────────
private fun registerSetAlarm() = ToolRegistry.register(
    name = "set_alarm",
    declaration = JSONObject().apply {
        put("name", "set_alarm")
        put("description", "Crea una alarma a una hora concreta en la app de reloj del sistema.")
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("hour",   JSONObject().put("type", "integer").put("description", "Hora en formato 24 h (0-23)"))
                put("minute", JSONObject().put("type", "integer").put("description", "Minutos (0-59)"))
                put("label",  JSONObject().put("type", "string").put("description", "Etiqueta opcional de la alarma"))
            })
            put("required", JSONArray().put("hour").put("minute"))
        })
    }
) { args, context ->
    runCatching {
        val hour   = args.getInt("hour")
        val minute = args.getInt("minute")
        val label  = args.optString("label", "Alarma")
        context.startActivity(
            Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        JSONObject().put("success", true).put("hour", hour).put("minute", minute)
    }.getOrElse { e -> JSONObject().put("success", false).put("error", e.message) }
}

// ── start_navigation ──────────────────────────────────────────────────────
// Usa la URL web de Google Maps dir: siempre funciona (browser como fallback).
// La descripción es explícita para que Gemini la llame sin dudar.
private fun registerStartNavigation() = ToolRegistry.register(
    name = "start_navigation",
    declaration = JSONObject().apply {
        put("name", "start_navigation")
        put("description",
            "Abre Google Maps con la ruta hacia un destino. " +
            "LLAMA SIEMPRE a esta función cuando el usuario quiera navegar, " +
            "ir a algún lugar, que le lleves a algún sitio o pedir indicaciones. " +
            "No respondas con texto; ejecuta la acción.")
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("destination", JSONObject()
                    .put("type", "string")
                    .put("description", "Dirección o nombre del lugar al que navegar"))
            })
            put("required", JSONArray().put("destination"))
        })
    }
) { args, context ->
    runCatching {
        val dest    = args.getString("destination")
        val encoded = URLEncoder.encode(dest, "UTF-8")
        // URL de Google Maps dirección: abre Maps si está instalado, o el browser como fallback.
        val url    = "https://www.google.com/maps/dir/?api=1&destination=$encoded&travelmode=driving"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pm = context.packageManager
        if (pm.resolveActivity(intent, 0) == null) {
            return@register JSONObject()
                .put("success", false)
                .put("error", "No encontré ninguna app para abrir la navegación.")
        }
        context.startActivity(intent)
        JSONObject().put("success", true).put("destination", dest)
    }.getOrElse { e -> JSONObject().put("success", false).put("error", e.message) }
}

// ── create_calendar_event ─────────────────────────────────────────────────
private fun registerCreateCalendarEvent() = ToolRegistry.register(
    name = "create_calendar_event",
    declaration = JSONObject().apply {
        put("name", "create_calendar_event")
        put("description", "Abre el calendario con un evento listo para guardar.")
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("title", JSONObject()
                    .put("type", "string")
                    .put("description", "Título del evento"))
                put("start_datetime", JSONObject()
                    .put("type", "string")
                    .put("description", "Inicio del evento en formato ISO 8601: yyyy-MM-ddTHH:mm:ss"))
                put("end_datetime", JSONObject()
                    .put("type", "string")
                    .put("description", "Fin del evento en formato ISO 8601 (opcional)"))
            })
            put("required", JSONArray().put("title").put("start_datetime"))
        })
    }
) { args, context ->
    runCatching {
        val title   = args.getString("title")
        val startMs = parseIso(args.getString("start_datetime"))
            ?: return@register JSONObject().put("success", false).put("error", "Fecha de inicio inválida.")
        val endMs   = args.optString("end_datetime", "").takeIf { it.isNotBlank() }?.let { parseIso(it) }
        val intent  = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
            if (endMs != null) putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        JSONObject().put("success", true).put("title", title)
    }.getOrElse { e -> JSONObject().put("success", false).put("error", e.message) }
}

// ── Helpers ───────────────────────────────────────────────────────────────

/** Deja solo dígitos y el + inicial; elimina espacios, guiones, comas y palabras. */
private fun cleanNumber(raw: String): String {
    val digitsAndPlus = raw.replace(Regex("[^0-9+]"), "")
    // El + solo es válido al principio (indicativo internacional)
    return if (digitsAndPlus.startsWith("+"))
        "+" + digitsAndPlus.drop(1).replace("+", "")
    else
        digitsAndPlus.replace("+", "")
}

/** Quita acentos, pone en minúsculas y elimina espacios extremos. */
private fun normalize(s: String): String {
    val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
    return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .lowercase()
        .trim()
}

/** Parsea ISO 8601 y variantes comunes; devuelve milisegundos o null. */
private fun parseIso(dt: String): Long? {
    val formats = listOf(
        "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm",
        "yyyy-MM-dd HH:mm:ss",   "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd"
    )
    for (fmt in formats) {
        try { return SimpleDateFormat(fmt, Locale.getDefault()).parse(dt.trim())?.time }
        catch (_: Exception) {}
    }
    return null
}
