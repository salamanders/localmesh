package info.benjaminhill.localmesh.mesh

import kotlinx.serialization.Serializable

/**
 * Represents the lifecycle states of the [BridgeService].
 *
 * ## What it does
 * Provides a clear, type-safe representation of the service's current status (e.g., Idle, Starting, Running).
 * This is used by the UI to show the correct state to the user.
 *
 * ## What it doesn't do
 * It does not represent the state of network connections or individual peers. It only reflects the
 * overall state of the local [BridgeService].
 *
 * ## Comparison to other classes
 * - **[BridgeAction]:** `BridgeState` represents the *current status* of the service, while `BridgeAction`
 *   represents a *request* to change that status.
 */
@Serializable
sealed class BridgeState {
    /**
     * The service is not active.
     */
    object Idle : BridgeState()

    /**
     * The service is in the process of starting up.
     */
    object Starting : BridgeState()

    /**
     * The service is running and actively advertising/discovering.
     */
    object Running : BridgeState()

    /**
     * The service is in the process of shutting down.
     */
    object Stopping : BridgeState()

    /**
     * The service encountered an unrecoverable error.
     */
    @Serializable
    data class Error(val message: String) : BridgeState()
}
