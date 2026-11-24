package dev.trinhhg.vapp.engine.vbook

import dev.trinhhg.vapp.engine.EngineErrorCode
import dev.trinhhg.vapp.engine.EngineException
import dev.trinhhg.vapp.engine.js.api.HttpExecutor
import dev.trinhhg.vapp.engine.js.api.HttpSpec
import dev.trinhhg.vapp.engine.log.EngineLogger
import dev.trinhhg.vapp.engine.security.NetworkSecurityValidator
import dev.trinhhg.vapp.engine.security.RegexSecurityValidator
import dev.trinhhg.vapp.engine.security.SecurityValidationException
import dev.trinhhg.vapp.engine.security.ZipSecurityValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import net.lingala.zip4j.ZipFile
import okio.FileSystem
import okio.Path

/**
 * Downloads, validates, and extracts VBook `plugin.zip` / `.plugin` archives.
 * Rejects Zip-Slip, ReDoS regexes, and `"encrypt": true` extensions explicitly.
 */
class PluginZipExtractor(
    private val httpExecutor: HttpExecutor,
    private val log: EngineLogger,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    fun downloadAndExtract(
        zipUrl: String,
        cacheDir: Path,
    ): VBookPluginPackage {
        try {
            NetworkSecurityValidator.validateUrl(zipUrl)
        } catch (e: SecurityValidationException) {
            throw EngineException(EngineErrorCode.VALIDATION_ERROR, e.message, e)
        }

        val res = httpExecutor.execute(HttpSpec(method = "GET", url = zipUrl))
        if (res.code !in 200..299) {
            throw EngineException(
                EngineErrorCode.HTTP_ERROR,
                "Failed to download extension ($zipUrl): HTTP ${res.code}",
            )
        }
        try {
            NetworkSecurityValidator.validateResponse(
                res.headers["content-type"],
                res.body.size.toLong(),
            )
        } catch (e: SecurityValidationException) {
            throw EngineException(EngineErrorCode.VALIDATION_ERROR, e.message, e)
        }

        fileSystem.createDirectories(cacheDir)
        val fileName = zipUrl.substringAfterLast('/').ifBlank { "plugin.zip" }
        val tmpZip = cacheDir / "$fileName.tmp"
        val destZip = cacheDir / fileName
        fileSystem.write(tmpZip) { write(res.body) }
        fileSystem.atomicMove(tmpZip, destZip)
        return extractFromZip(destZip)
    }

    fun extractFromZip(zipPath: Path): VBookPluginPackage {
        try {
            ZipSecurityValidator.validateZipFile(zipPath, fileSystem)
        } catch (e: SecurityValidationException) {
            throw EngineException(EngineErrorCode.VALIDATION_ERROR, e.message, e)
        }

        val zipFile = ZipFile(zipPath.toFile())
        val pluginJsonText =
            readEntryUtf8(zipFile, "plugin.json")
                ?: throw EngineException(
                    EngineErrorCode.VALIDATION_ERROR,
                    "plugin.json not found in archive $zipPath",
                )

        val root =
            runCatching { json.parseToJsonElement(pluginJsonText).jsonObject }.getOrElse { err ->
                throw EngineException(
                    EngineErrorCode.VALIDATION_ERROR,
                    "Invalid plugin.json: ${err.message}",
                    err,
                )
            }

        val meta =
            root["metadata"] as? JsonObject
                ?: throw EngineException(EngineErrorCode.VALIDATION_ERROR, "plugin.json missing metadata")

        val isEncrypted = (meta["encrypt"] as? JsonPrimitive)?.booleanOrNull == true
        val name = getString(meta, "name").orEmpty()
        if (isEncrypted) {
            log.warn("Rejected encrypted extension '$name' (encrypt: true)", tag = "PluginZip")
            throw EngineException(
                EngineErrorCode.ENCRYPTED_UNSUPPORTED,
                "Extension '$name' uses proprietary VBook encryption (encrypt: true), which is not supported by vapp.",
            )
        }

        val regexp = getString(meta, "regexp").orEmpty()
        if (regexp.isNotEmpty()) {
            try {
                RegexSecurityValidator.validateRegex(regexp)
            } catch (e: SecurityValidationException) {
                throw EngineException(EngineErrorCode.VALIDATION_ERROR, e.message, e)
            }
        }

        val scriptMap = LinkedHashMap<String, String>()
        (root["script"] as? JsonObject)?.forEach { (k, v) ->
            (v as? JsonPrimitive)?.content?.let { scriptMap[k] = it }
        }

        val configSpecs = parseConfigSpecs(root["config"] as? JsonObject)
        val scriptContents = extractSrcScripts(zipFile)

        if (scriptContents.isEmpty()) {
            throw EngineException(
                EngineErrorCode.VALIDATION_ERROR,
                "Extension '$name' contains no readable scripts in src/",
            )
        }

        val iconBytes = readEntryBytes(zipFile, "icon.png")

        return VBookPluginPackage(
            name = name.ifBlank { "Unnamed Extension" },
            author = getString(meta, "author") ?: "vBook",
            version = getInt(meta, "version") ?: 1,
            source = getString(meta, "source").orEmpty(),
            regexp = regexp,
            description = getString(meta, "description").orEmpty(),
            locale = getString(meta, "locale") ?: "vi_VN",
            type = getString(meta, "type") ?: "novel",
            tag = getString(meta, "tag"),
            encrypted = false,
            scriptMap = scriptMap,
            scriptContents = scriptContents,
            configSpecs = configSpecs,
            iconBytes = iconBytes,
            rawMetadataJson = pluginJsonText,
        )
    }

    private fun extractSrcScripts(zipFile: ZipFile): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (header in zipFile.fileHeaders) {
            if (header.isDirectory) continue
            val entryName = header.fileName.replace("\\", "/")
            if (entryName.startsWith("src/") && entryName.endsWith(".js") && !entryName.removePrefix("src/").contains('/')) {
                val jsName = entryName.removePrefix("src/")
                readEntryUtf8(zipFile, header.fileName)?.let { out[jsName] = it }
            }
        }
        return out
    }

    private fun parseConfigSpecs(configObj: JsonObject?): Map<String, VBookConfigSpec> {
        if (configObj == null) return emptyMap()
        val out = LinkedHashMap<String, VBookConfigSpec>()
        for ((key, value) in configObj) {
            when (value) {
                is JsonObject -> {
                    out[key] =
                        VBookConfigSpec(
                            title = getString(value, "title"),
                            subtitle = getString(value, "subtitle"),
                            default = getString(value, "default"),
                            values =
                                (value["values"] as? JsonArray)
                                    ?.mapNotNull { (it as? JsonPrimitive)?.content }
                                    ?: emptyList(),
                            mode = getString(value, "mode"),
                            format = getString(value, "format"),
                        )
                }
                is JsonPrimitive -> {
                    out[key] =
                        VBookConfigSpec(
                            title = key,
                            default = value.content,
                            mode = "input",
                        )
                }
                else -> Unit
            }
        }
        return out
    }

    private fun readEntryBytes(
        zipFile: ZipFile,
        name: String,
    ): ByteArray? {
        val header = zipFile.getFileHeader(name) ?: return null
        return zipFile.getInputStream(header).use { it.readBytes() }
    }

    private fun readEntryUtf8(
        zipFile: ZipFile,
        name: String,
    ): String? = readEntryBytes(zipFile, name)?.decodeToString()

    private fun getString(
        obj: JsonObject,
        key: String,
    ): String? = (obj[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun getInt(
        obj: JsonObject,
        key: String,
    ): Int? = getString(obj, key)?.toIntOrNull()
}
