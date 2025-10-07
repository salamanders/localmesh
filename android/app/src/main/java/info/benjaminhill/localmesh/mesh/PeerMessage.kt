package info.benjaminhill.localmesh.mesh

import kotlinx.serialization.Serializable

/**
 * A structured, serializable message exchanged between peers on the mesh network.
 *
 * This data class defines the application-level protocol for all inter-peer communication.
 * It is serialized to JSON and sent as a [com.google.android.gms.nearby.connection.Payload].
 *
 * ## What it does
 * - Encapsulates the data for a single peer-to-peer message, including sender (`from`),
 *   ordering (`sequence`), the `command` to be executed, and its `payload`.
 *
 * ## What it doesn't do
 * - It does not execute commands; the `command` string is dispatched by the [CommandRouter].
 * - It is not a UI action; local UI actions are defined by [P2PBridgeAction].
 *
 * ## Comparison to other classes
 * - **[P2PBridgeAction]:** A `P2PMessage` is for *inter-device* (peer-to-peer) communication, while
 *   a [P2PBridgeAction] is for *intra-device* (UI-to-Service) communication.
 * - **[com.google.android.gms.nearby.connection.Payload]:** `Payload` is the raw data container
 *   for the network, while `P2PMessage` is the structured application data inside it.
 */
@Serializable
data class P2PMessage(
    // Source node ID
    val from: String,
    // Counter to keep messages in order
    val sequence: Long,
    // Sender's timestamp in milliseconds
    val timestamp: Long,
    // The command to execute.
    val command: String,
    // Additional info (like file name, or file name plus part).  Optional.
    val metadata: String = "",
    // Contents (like the file's contents). Optional.
    val payload: String = "",
)