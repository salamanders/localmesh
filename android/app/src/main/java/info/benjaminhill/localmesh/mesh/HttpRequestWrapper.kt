package info.benjaminhill.localmesh.mesh

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A serializable wrapper for an HTTP request, used for sending requests between peers.
 *
 * ## What it does
 * Encapsulates the essential parts of a local HTTP request (method, path, parameters) into a
 * data class that can be serialized to JSON and broadcast to other devices on the mesh network.
 *
 * ## What it doesn't do
 * It does not contain the actual response to the HTTP request. It is only for transmitting the
 * request itself.
 *
 * ## Comparison to other classes
 * - **[BridgeAction]:** `HttpRequestWrapper` is for *inter-device* (peer-to-peer) communication,
 *   representing a replicated HTTP call. `BridgeAction` is for *intra-device* (UI-to-Service)
 *   commands.
 */
@Serializable
data class HttpRequestWrapper(
    val method: String,
    val path: String,
    val params: String, // URL-encoded string of all query and/or body parameters
    val sourceNodeId: String
) {
    fun toJson(): String {
        return Json.encodeToString(serializer(), this)
    }

    companion object {
        fun fromJson(jsonString: String): HttpRequestWrapper {
            return Json.decodeFromString(serializer(), jsonString)
        }
    }
}
