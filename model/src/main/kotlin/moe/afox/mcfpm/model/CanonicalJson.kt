package moe.afox.mcfpm.model

import java.nio.charset.StandardCharsets
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement

public object CanonicalJson {
    public val format: Json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = false
    }

    public fun encodeManifest(manifest: PackageManifest): ByteArray =
        encode(PackageManifest.serializer(), manifest.normalized())

    public fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray {
        val element = format.encodeToJsonElement(serializer, value)
        return canonicalize(element).toString().toByteArray(StandardCharsets.UTF_8)
    }

    public fun <T> decode(
        deserializer: DeserializationStrategy<T>,
        bytes: ByteArray,
    ): T = format.decodeFromString(deserializer, bytes.toString(StandardCharsets.UTF_8))

    private fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedBy(Map.Entry<String, JsonElement>::key)
                .associate { (key, value) -> key to canonicalize(value) },
        )
        is JsonArray -> JsonArray(element.map(::canonicalize))
        else -> element
    }
}
