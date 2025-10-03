package info.benjaminhill.localmesh.service

import kotlinx.serialization.Serializable

/**
 * Represents the different states of the P2PBridgeService.
 */
@Serializable
sealed class ServiceState {
    /**
     * The service is not active.
     */
    object Idle : ServiceState()

    /**
     * The service is in the process of starting up.
     */
    object Starting : ServiceState()

    /**
     * The service is running and actively advertising/discovering.
     */
    object Running : ServiceState()

    /**
     * The service is in the process of shutting down.
     */
    object Stopping : ServiceState()

    /**
     * The service encountered an unrecoverable error.
     */
    @Serializable
    data class Error(val message: String) : ServiceState()
}
