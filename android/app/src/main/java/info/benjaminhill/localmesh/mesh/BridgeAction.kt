package info.benjaminhill.localmesh.mesh

/**
 * Represents the set of well-defined, type-safe actions the UI can send to the [BridgeService].
 *
 * These actions are sent from the [info.benjaminhill.localmesh.MainActivity] to the service via Android Intents.
 *
 * ## What it does
 * Defines the contract for UI-to-Service communication within the app. It's
 * used for controlling the lifecycle of the service (e.g., starting and stopping).
 *
 * ## What it doesn't do
 * This is NOT for commands sent between peers on the network.
 * Inter-peer communication is handled by wrapping local HTTP requests into an [HttpRequestWrapper]
 * which is then broadcast to peers.
 *
 * ## Comparison to other classes
 * - **[HttpRequestWrapper]:** `BridgeAction` is for *intra-device* communication (UI to
 *   Service), while `HttpRequestWrapper` is for *inter-device* communication (peer to peer).
 * - **[BridgeState]:** `BridgeAction` represents *requests* to change the service's state,
 *   while [BridgeState] represents the *actual current state* of the service.
 */
sealed class BridgeAction {
    /**
     * Starts the P2P bridge service.
     */
    object Start : BridgeAction()

    /**
     * Stops the P2P bridge service.
     */
    object Stop : BridgeAction()

    /**
     * Tells the service to broadcast a command to all peers.
     * This is for sending commands from the local UI to remote devices.
     */
    data class BroadcastCommand(val command: String, val payload: String) : BridgeAction()
}
