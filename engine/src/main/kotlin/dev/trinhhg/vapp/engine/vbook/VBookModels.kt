package dev.trinhhg.vapp.engine.vbook

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class VBookRepositoryEntry(
    val link: String? = null,
    val author: String? = null,
    val description: String? = null,
    val enabled: Boolean = true,
)

@Serializable
data class VBookExtensionItem(
    val name: String? = null,
    val author: String? = null,
    val path: String? = null,
    val version: Int? = null,
    val source: String? = null,
    val icon: String? = null,
    val description: String? = null,
    val type: String? = null,
    val locale: String? = null,
    val tag: String? = null,
)

data class VBookConfigSpec(
    val title: String? = null,
    val subtitle: String? = null,
    val default: String? = null,
    val values: List<String> = emptyList(),
    val mode: String? = null,
    val format: String? = null,
)

data class VBookPluginPackage(
    val name: String,
    val author: String,
    val version: Int,
    val source: String,
    val regexp: String,
    val description: String,
    val locale: String,
    val type: String,
    val tag: String?,
    val encrypted: Boolean,
    val scriptMap: Map<String, String>,
    val scriptContents: Map<String, String>,
    val configSpecs: Map<String, VBookConfigSpec>,
    val iconBytes: ByteArray?,
    val rawMetadataJson: String,
)

@Serializable
enum class ExtensionType {
    NOVEL,
    CHINESE_NOVEL,
    COMIC,
    OTHER,
}

@Serializable
data class InstalledExtension(
    val id: String,
    val name: String,
    val author: String,
    val version: Int,
    val sourceUrl: String,
    val urlRegex: String,
    val type: ExtensionType,
    val locale: String,
    val description: String,
    val scriptsDir: String,
    val scriptMap: Map<String, String>,
    val configSpecsJson: String,
    val userConfig: Map<String, String>,
    val enabled: Boolean = true,
)

@Serializable
data class VBookRegistryResponse(
    val metadata: RegistryMeta = RegistryMeta(),
    val data: List<VBookExtensionItem> = emptyList(),
)

@Serializable
data class RegistryMeta(
    val author: String = "Unknown",
    val description: String = "",
)

@Serializable
data class PersistedRepoList(
    val repos: List<VBookRepositoryEntry> = emptyList(),
)

@Serializable
data class JsonPluginConfigSpec(
    val title: String? = null,
    val subtitle: String? = null,
    val default: String? = null,
    val values: List<String> = emptyList(),
    val mode: String? = null,
    val format: String? = null,
    @SerialName("rawValue") val rawValue: String? = null,
)
