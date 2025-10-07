package info.benjaminhill.localmesh.mesh

/**
 * Represents the set of well-defined, type-safe actions the UI can send to the [P2PBridgeService].
 *
 * These actions are sent from the [info.benjaminhill.localmesh.MainActivity] to the service via Android Intents.
 *
 * ## What it does
 * Defines the contract for UI-to-Service communication within the app. It's
 * used for controlling the lifecycle of the service (e.g., starting and stopping) and triggering
 * high-level, user-initiated operations like sharing a folder.
 *
 * ## What it doesn't do
 * This is NOT for commands sent between peers on the network.
 * Inter-peer communication is handled by the [CommandRouter] and its [CommandRouter.AbstractCommand]
 * implementations, which are triggered by incoming payloads from other devices.
 *
 * ## Comparison to other classes
 * - **[CommandRouter.AbstractCommand]:** `P2PBridgeAction` is for *intra-device* communication (UI to
 *   Service), while `AbstractCommand` is for *inter-device* communication (peer to peer).
 * - **[ServiceState]:** `P2PBridgeAction` represents *requests* to change the service's state,
 *   while [ServiceState] represents the *actual current state* of the service.
 */
sealed class P2PBridgeAction {
    /**
     * Starts the P2P bridge service.
     */
    object Start : P2PBridgeAction()

    /**
     * Stops the P2P bridge service.
     */
    object Stop : P2PBridgeAction()

    /**
     * Tells the service to broadcast a command to all peers.
     * This is for sending commands from the local UI to remote devices.
     * It is NOT for handling commands received from other peers; that is done by [CommandRouter].
     */
    data class BroadcastCommand(val command: String, val payload: String) : P2PBridgeAction()
}
