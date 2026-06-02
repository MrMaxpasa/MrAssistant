package com.example.mrassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

typealias ToolHandler = suspend (args: JSONObject, context: Context) -> JSONObject

/**
 * Registro central de herramientas de Alfred.
 *
 * Para añadir una herramienta nueva:
 *   1. Abre AppTools.kt.
 *   2. Añade un bloque ToolRegistry.register() dentro de registerAppTools().
 *      - name: identificador que Gemini usará en la functionCall.
 *      - declaration: schema JSON que Gemini ve para saber cuándo y cómo llamarla.
 *      - handler: lambda suspend que recibe los argumentos y devuelve el resultado.
 *   3. Listo. No hay que tocar GeminiApi ni AssistantViewModel.
 */
object ToolRegistry {

    private val tools = LinkedHashMap<String, Pair<JSONObject, ToolHandler>>()

    fun register(name: String, declaration: JSONObject, handler: ToolHandler) {
        tools[name] = declaration to handler
    }

    /**
     * Devuelve el objeto {"function_declarations": [...]} para incluir en
     * el cuerpo de la petición a Gemini. Null si no hay herramientas.
     */
    fun declarations(): JSONObject? {
        if (tools.isEmpty()) return null
        return JSONObject().put(
            "function_declarations",
            JSONArray().also { arr -> tools.values.forEach { arr.put(it.first) } }
        )
    }

    /** Ejecuta la herramienta [name] con los argumentos dados por Gemini. */
    suspend fun execute(name: String, args: JSONObject, context: Context): JSONObject =
        tools[name]?.second?.invoke(args, context)
            ?: JSONObject().put("error", "Herramienta desconocida: $name")
}
