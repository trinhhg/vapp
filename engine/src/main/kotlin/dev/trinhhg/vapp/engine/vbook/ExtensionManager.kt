package dev.trinhhg.vapp.engine.vbook

import dev.trinhhg.vapp.engine.EngineErrorCode
import dev.trinhhg.vapp.engine.EngineException
import dev.trinhhg.vapp.engine.log.EngineLogger
import dev.trinhhg.vapp.engine.util.normalizeUrl
import dev.trinhhg.vapp.engine.util.parseHost
import dev.trinhhg.vapp.engine.util.sha256Hex
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages installed unencrypted VBook extensions on disk and matches book URLs to extensions.
 */
class ExtensionManager(
    private val rootDir: Path,
    private val repositoryClient: VBookRepositoryClient,
    private val zipExtractor: PluginZipExtractor,
    private val log: EngineLogger,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }
    private val lock = Any()
    private val extensions = ConcurrentHashMap<String, InstalledExtension>()
    private val indexFile: Path get() = rootDir / "extensions" / "index.json"

    init {
        loadFromDisk()
    }

    fun listExtensions(): List<InstalledExtension> = extensions.values.sortedBy { it.name.lowercase() }

    fun getExtension(extensionId: String): InstalledExtension? = extensions[extensionId]

    fun matchByUrl(bookUrl: String): InstalledExtension? {
        val clean = bookUrl.normalizeUrl()
        if (clean.isEmpty()) return null
        val noScheme = clean.removePrefix("https://").removePrefix("http://").trimEnd('/')
        return extensions.values
            .firstOrNull { ext ->
                ext.enabled &&
                    ext.urlRegex.isNotBlank() &&
                    runCatching {
                        val re = Regex(ext.urlRegex)
                        re.matches(clean) ||
                            re.matches(noScheme) ||
                            Regex("(https?://)?${ext.urlRegex}").matches(clean)
                    }.getOrDefault(false)
            }
    }

    fun installFromUrl(zipUrl: String): InstalledExtension {
        val cacheDir = rootDir / "cache" / "plugins"
        val pkg = zipExtractor.downloadAndExtract(zipUrl, cacheDir)
        return persistPackage(pkg)
    }

    fun installFromLocalZip(zipPath: Path): InstalledExtension {
        val pkg = zipExtractor.extractFromZip(zipPath)
        return persistPackage(pkg)
    }

    fun installFromRegistryItem(item: VBookExtensionItem): InstalledExtension {
        val path =
            item.path?.takeIf { it.isNotBlank() }
                ?: throw EngineException(EngineErrorCode.VALIDATION_ERROR, "Extension '${item.name}' has no download path")
        return installFromUrl(path)
    }

    fun findAndInstallByUrl(
        bookUrl: String,
        registryUrls: List<String> = listOf(VBookRepositoryClient.DEFAULT_PLUGIN_INDEX_URL),
    ): InstalledExtension? {
        matchByUrl(bookUrl)?.let { return it }
        val targetHost = parseHost(bookUrl)?.removePrefix("www.") ?: return null
        val candidates =
            registryUrls
                .flatMap { url ->
                    runCatching { repositoryClient.fetchExtensions(url) }.getOrDefault(emptyList())
                }.filter { item ->
                    val srcHost = parseHost(item.source)?.removePrefix("www.")
                    srcHost != null && (srcHost == targetHost || targetHost.endsWith(".$srcHost"))
                }

        for (candidate in candidates) {
            val installed =
                runCatching { installFromRegistryItem(candidate) }
                    .onFailure { log.warn("Auto-discovery skipped ${candidate.name}: ${it.message}", tag = "ExtMgr") }
                    .getOrNull() ?: continue
            if (matchByUrl(bookUrl)?.id == installed.id) {
                return installed
            }
        }
        return null
    }

    fun setEnabled(
        extensionId: String,
        enabled: Boolean,
    ) {
        synchronized(lock) {
            val current = extensions[extensionId] ?: return
            extensions[extensionId] = current.copy(enabled = enabled)
            saveIndexLocked()
        }
    }

    fun updateUserConfig(
        extensionId: String,
        configUpdates: Map<String, String>,
    ) {
        synchronized(lock) {
            val current = extensions[extensionId] ?: return
            val merged = LinkedHashMap(current.userConfig).apply { putAll(configUpdates) }
            extensions[extensionId] = current.copy(userConfig = merged)
            saveIndexLocked()
        }
    }

    fun uninstall(extensionId: String) {
        synchronized(lock) {
            val removed = extensions.remove(extensionId) ?: return
            runCatching { fileSystem.deleteRecursively(rootDir / "extensions" / removed.id) }
            saveIndexLocked()
        }
    }

    fun loadScripts(extension: InstalledExtension): Map<String, String> {
        val dir = rootDir / "extensions" / extension.id / "src"
        if (!fileSystem.exists(dir)) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (file in fileSystem.list(dir)) {
            if (file.name.endsWith(".js")) {
                runCatching { fileSystem.read(file) { readUtf8() } }
                    .onSuccess { out[file.name] = it }
            }
        }
        return out
    }

    private fun persistPackage(pkg: VBookPluginPackage): InstalledExtension {
        val id = buildExtensionId(pkg.source, pkg.name)
        val extDir = rootDir / "extensions" / id
        val srcDir = extDir / "src"

        synchronized(lock) {
            fileSystem.createDirectories(srcDir)
            fileSystem.write(extDir / "plugin.json") { writeUtf8(pkg.rawMetadataJson) }
            pkg.iconBytes?.let { bytes ->
                fileSystem.write(extDir / "icon.png") { write(bytes) }
            }
            for ((fileName, code) in pkg.scriptContents) {
                fileSystem.write(srcDir / fileName) { writeUtf8(code) }
            }

            val configMap = LinkedHashMap<String, String>()
            val serializableSpecs = LinkedHashMap<String, JsonPluginConfigSpec>()
            pkg.configSpecs.forEach { (k, spec) ->
                configMap[k] = spec.default ?: ""
                serializableSpecs[k] =
                    JsonPluginConfigSpec(
                        title = spec.title,
                        subtitle = spec.subtitle,
                        default = spec.default,
                        values = spec.values,
                        mode = spec.mode,
                        format = spec.format,
                    )
            }
            configMap.putIfAbsent("thread_num", "3")
            configMap.putIfAbsent("delay", "0")
            configMap.putIfAbsent("timeout", "30000")

            val existingConfig = extensions[id]?.userConfig
            if (existingConfig != null) {
                configMap.putAll(existingConfig)
            }

            val installed =
                InstalledExtension(
                    id = id,
                    name = pkg.name,
                    author = pkg.author,
                    version = pkg.version,
                    sourceUrl = pkg.source,
                    urlRegex = pkg.regexp,
                    type = mapType(pkg.type),
                    locale = pkg.locale,
                    description = pkg.description,
                    scriptsDir = srcDir.toString(),
                    scriptMap = pkg.scriptMap,
                    configSpecsJson = json.encodeToString(serializableSpecs),
                    userConfig = configMap,
                    enabled = extensions[id]?.enabled ?: true,
                )
            extensions[id] = installed
            saveIndexLocked()
            log.add("Installed extension '${installed.name}' v${installed.version} ($id)", tag = "ExtMgr")
            return installed
        }
    }

    private fun mapType(raw: String): ExtensionType =
        when (raw.lowercase()) {
            "novel" -> ExtensionType.NOVEL
            "chinese_novel" -> ExtensionType.CHINESE_NOVEL
            "comic" -> ExtensionType.COMIC
            else -> ExtensionType.OTHER
        }

    private fun buildExtensionId(
        source: String,
        name: String,
    ): String {
        val seed = "${parseHost(source) ?: source.lowercase()}:${name.lowercase().trim()}"
        return sha256Hex(seed.encodeToByteArray()).take(16)
    }

    fun reloadFromDisk() {
        synchronized(lock) {
            extensions.clear()
            loadFromDisk()
        }
    }

    private fun loadFromDisk() {
        runCatching {
            if (!fileSystem.exists(indexFile)) return
            val raw = fileSystem.read(indexFile) { readUtf8() }
            val list = json.decodeFromString<List<InstalledExtension>>(raw)
            extensions.clear()
            list.forEach { extensions[it.id] = it }
        }
    }

    private fun saveIndexLocked() {
        runCatching {
            indexFile.parent?.let { fileSystem.createDirectories(it) }
            val tmp = indexFile.parent?.resolve("index.json.tmp") ?: indexFile
            val payload = json.encodeToString(extensions.values.toList())
            fileSystem.write(tmp) { writeUtf8(payload) }
            if (tmp != indexFile) {
                fileSystem.atomicMove(tmp, indexFile)
            }
        }
    }
}
