package info.benjaminhill.localmesh.service

import kotlinx.serialization.Serializable

/**
 * Represents the different events that can be broadcast from the P2PBridgeService.
 * A structured and type-safe communication contract between the background P2PBridgeService and MainActivity UI.
 */
@Serializable
sealed class P2PBridgeEvent {
    /**
     * Represents a status update from the service.
     * @param status The new status message.
     * @param peerCount The number of connected peers.
     */
    @Serializable
    data class StatusUpdate(val status: String, val peerCount: Int) : P2PBridgeEvent()

    /**
     * Represents a log message from the service.
     * @param message The log message.
     */
    @Serializable
    data class LogMessage(val message: String) : P2PBridgeEvent()
}
