package info.benjaminhill.localmesh.mesh

/**
 * Represents the different actions that can be sent to the P2PBridgeService.
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
}
