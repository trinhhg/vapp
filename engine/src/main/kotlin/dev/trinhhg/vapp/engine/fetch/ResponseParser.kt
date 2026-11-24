package dev.trinhhg.vapp.engine.fetch

import dev.trinhhg.vapp.engine.EngineErrorCode
import dev.trinhhg.vapp.engine.EngineException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

data class ParsedVBookResponse(
    val data: Any?,
    val data2: Any?,
)

/**
 * Parses VBook script return payloads (`Response.success(data, data2)` / `Response.error(msg)`),
 * supporting both JSON string returns (standard VBook `core.js`) and normalized Maps.
 */
object ResponseParser {
    private val json =
        Json {
            isLenient = true
            ignoreUnknownKeys = true
        }

    fun parse(
        rawResult: Any?,
        contextLabel: String,
    ): ParsedVBookResponse {
        if (rawResult == null) {
            throw EngineException(
                EngineErrorCode.SCRIPT_ERROR,
                "$contextLabel returned null",
            )
        }

        val normalized: Any? =
            when (rawResult) {
                is String -> {
                    val trimmed = rawResult.trim()
                    if (trimmed.isEmpty()) {
                        throw EngineException(EngineErrorCode.SCRIPT_ERROR, "$contextLabel returned empty string")
                    }
                    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                        runCatching { toKotlinValue(json.parseToJsonElement(trimmed)) }
                            .getOrElse { trimmed }
                    } else {
                        trimmed
                    }
                }
                else -> rawResult
            }

        if (normalized is Map<*, *>) {
            val codeVal = normalized["code"]
            if (codeVal is Number && codeVal.toInt() != 0) {
                val errMsg =
                    normalized["data"]?.toString()?.takeIf { it.isNotBlank() }
                        ?: "Extension script reported error code ${codeVal.toInt()}"
                throw EngineException(EngineErrorCode.SCRIPT_ERROR, "$contextLabel: $errMsg")
            }
            if (normalized.containsKey("data")) {
                return ParsedVBookResponse(data = normalized["data"], data2 = normalized["data2"])
            }
        }

        return ParsedVBookResponse(data = normalized, data2 = null)
    }

    private fun toKotlinValue(el: JsonElement): Any? =
        when (el) {
            is JsonNull -> null
            is JsonPrimitive ->
                when {
                    el.isString -> el.content
                    el.booleanOrNull != null -> el.booleanOrNull
                    el.intOrNull != null -> el.intOrNull
                    el.longOrNull != null -> el.longOrNull
                    el.doubleOrNull != null -> el.doubleOrNull
                    else -> el.content
                }
            is JsonArray -> el.map { toKotlinValue(it) }
            is JsonObject -> el.entries.associate { (k, v) -> k to toKotlinValue(v) }
        }
}
