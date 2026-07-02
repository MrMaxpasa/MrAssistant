package com.example.mrassistant

import org.json.JSONObject
import java.text.Normalizer

data class LocalCommand(val tool: String, val args: JSONObject)

/**
 * Reconocimiento local de comandos de acción por palabras clave.
 * Devuelve un LocalCommand si la frase encaja con un patrón conocido,
 * o null si debe ir a Gemini.
 *
 * Diseño: los prefijos se normalizan (sin acentos, minúsculas) para matching,
 * pero el argumento se extrae del texto original para preservar acentos y
 * mayúsculas que los handlers (call_contact, open_app, etc.) pueden necesitar.
 */
object LocalCommandRecognizer {

    fun match(text: String): LocalCommand? {
        val norm = normalize(text)
        val (trimmedNorm, trimmedOriginal) = stripAlfredPrefix(norm, text)
        return tryCall(trimmedNorm, trimmedOriginal)
            ?: tryOpenApp(trimmedNorm, trimmedOriginal)
            ?: trySetAlarm(trimmedNorm)
            ?: trySetTimer(trimmedNorm)
            ?: tryNavigation(trimmedNorm, trimmedOriginal)
            ?: tryGetDatetime(trimmedNorm)
    }

    // Regex que detecta "alfred" u "oye alfred" con coma opcional al inicio de la frase.
    // Así "Alfred, pon un timer" y "oye Alfred pon un timer" funcionan igual que sin el nombre.
    private val ALFRED_PREFIX = Regex("""^(oye\s+)?alfred\s*,?\s*""", RegexOption.IGNORE_CASE)

    private fun stripAlfredPrefix(norm: String, original: String): Pair<String, String> {
        val normMatch = ALFRED_PREFIX.find(norm) ?: return Pair(norm, original)
        val strippedNorm = norm.substring(normMatch.value.length)
        // Aplicamos la misma regex al original (IGNORE_CASE ya está activo) para preservar
        // acentos y mayúsculas en el argumento que extraerán los handlers.
        val origMatch = ALFRED_PREFIX.find(original.trim())
        val strippedOriginal = if (origMatch != null)
            original.trim().substring(origMatch.value.length)
        else original
        return Pair(strippedNorm, strippedOriginal)
    }

    // ── Llamada ────────────────────────────────────────────────────────────

    private val callPrefixes = listOf(
        "llamame a la", "llamame al", "llamame a",
        "llama a la", "llama al", "llama a",
        "llamar a la", "llamar al", "llamar a",
        "marca a la", "marca al", "marca a",
        "marcar a la", "marcar al", "marcar a"
    ).sortedByDescending { it.length }

    private fun tryCall(norm: String, original: String): LocalCommand? {
        for (prefix in callPrefixes) {
            val arg = extractArg(norm, original, prefix) ?: continue
            if (arg.isBlank()) continue
            val digits = arg.replace(Regex("[^0-9]"), "")
            return if (digits.length >= 6) {
                LocalCommand("call_number", JSONObject().put("number", arg))
            } else {
                LocalCommand("call_contact", JSONObject().put("name", arg))
            }
        }
        return null
    }

    // ── Abrir app ──────────────────────────────────────────────────────────

    private val openAppPrefixes = listOf(
        "abre la aplicacion", "abrir la aplicacion",
        "abre la app", "abrir la app",
        "abre el", "abre la", "abre los", "abre las",
        "abrir el", "abrir la", "abrir los", "abrir las",
        "abre", "abrir",
        "lanza", "lanzar"
    ).sortedByDescending { it.length }

    private fun tryOpenApp(norm: String, original: String): LocalCommand? {
        for (prefix in openAppPrefixes) {
            val arg = extractArg(norm, original, prefix) ?: continue
            if (arg.isBlank()) continue
            return LocalCommand("open_app", JSONObject().put("app_name", arg))
        }
        return null
    }

    // ── Alarma ────────────────────────────────────────────────────────────

    private fun trySetAlarm(norm: String): LocalCommand? {
        if (!norm.contains("alarma") && !norm.contains("despertador")) return null
        // Prueba HH:mm primero
        val colonMatch = Regex("""(\d{1,2})[:\.](\d{2})""").find(norm)
        if (colonMatch != null) {
            val h = colonMatch.groupValues[1].toIntOrNull() ?: return null
            val m = colonMatch.groupValues[2].toIntOrNull() ?: return null
            if (h in 0..23 && m in 0..59)
                return LocalCommand("set_alarm", JSONObject().put("hour", h).put("minute", m))
        }
        // Solo hora: "a las 8", "para las 9", "las 7"
        val hourMatch = Regex("""(?:a las|para las|las|a la|para la)\s+(\d{1,2})""").find(norm)
        if (hourMatch != null) {
            val h = hourMatch.groupValues[1].toIntOrNull() ?: return null
            if (h in 0..23)
                return LocalCommand("set_alarm", JSONObject().put("hour", h).put("minute", 0))
        }
        return null
    }

    // ── Temporizador ──────────────────────────────────────────────────────

    private fun trySetTimer(norm: String): LocalCommand? {
        if (!norm.contains("temporizador") && !norm.contains("timer") &&
            !norm.contains("cronometro")) return null
        if (norm.contains("media hora"))
            return LocalCommand("set_timer", JSONObject().put("duration_minutes", 30))
        if (norm.contains("cuarto de hora"))
            return LocalCommand("set_timer", JSONObject().put("duration_minutes", 15))
        val minMatch = Regex("""(\d+)\s*(?:min(?:utos?)?\b)""").find(norm)
        if (minMatch != null) {
            val v = minMatch.groupValues[1].toIntOrNull() ?: return null
            return LocalCommand("set_timer", JSONObject().put("duration_minutes", v))
        }
        // Fallback: primer número que aparezca
        val numMatch = Regex("""(\d+)""").find(norm)
        if (numMatch != null) {
            val v = numMatch.groupValues[1].toIntOrNull() ?: return null
            return LocalCommand("set_timer", JSONObject().put("duration_minutes", v))
        }
        return null
    }

    // ── Navegación ────────────────────────────────────────────────────────

    private val navPrefixes = listOf(
        "llevame hasta", "llevame a las", "llevame a la", "llevame al", "llevame a",
        "llevate hasta", "llevate a la", "llevate al", "llevate a",
        "navega hasta", "navega a la", "navega al", "navega a",
        "navegar hasta", "navegar a la", "navegar al", "navegar a",
        "como llego hasta", "como llego a la", "como llego al", "como llego a",
        "como voy hasta", "como voy a la", "como voy al", "como voy a",
        "quiero ir a la", "quiero ir al", "quiero ir a",
        "me llevas a la", "me llevas al", "me llevas a",
        "dirigeme a la", "dirigeme al", "dirigeme a",
        "ruta a la", "ruta al", "ruta a"
    ).sortedByDescending { it.length }

    private val leadingArticle = Regex("""^(?:el|la|los|las|un|una)\s+""", RegexOption.IGNORE_CASE)

    private fun tryNavigation(norm: String, original: String): LocalCommand? {
        for (prefix in navPrefixes) {
            val arg = extractArg(norm, original, prefix) ?: continue
            if (arg.isBlank()) continue
            val dest = leadingArticle.replace(arg, "").trim().ifBlank { arg }
            return LocalCommand("start_navigation", JSONObject().put("destination", dest))
        }
        return null
    }

    // ── Fecha y hora ──────────────────────────────────────────────────────

    private val datetimeTriggers = listOf(
        "que hora es", "que horas son", "dime la hora", "me dices la hora",
        "que hora tienes", "tienes hora",
        "que dia es hoy", "que dia es", "que fecha es hoy", "que fecha es",
        "que fecha tenemos", "que dia estamos", "a que dia estamos",
        "dime la fecha", "cual es la fecha", "cual es la hora"
    )

    private fun tryGetDatetime(norm: String): LocalCommand? {
        for (trigger in datetimeTriggers) {
            if (norm.contains(trigger)) return LocalCommand("get_datetime", JSONObject())
        }
        return null
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Si [norm] empieza por "[normPrefix] " (con espacio), devuelve el sufijo
     * correspondiente extrayéndolo de [original] por conteo de palabras,
     * para preservar acentos y mayúsculas del texto original.
     */
    private fun extractArg(norm: String, original: String, normPrefix: String): String? {
        if (!norm.startsWith("$normPrefix ")) return null
        val prefixWordCount = normPrefix.trim().split("\\s+".toRegex()).size
        val words = original.trim().split("\\s+".toRegex())
        if (words.size <= prefixWordCount) return null
        return words.drop(prefixWordCount).joinToString(" ").trim()
    }

    private fun normalize(s: String): String {
        val nfd = Normalizer.normalize(s, Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
            .trim()
    }
}
