# Island Merging Strategy for a Fragmented Network

**Audience:** Developers
**Purpose:** This document outlines a technical strategy to heal network partitions ("islands") in the LocalMesh network.

---

## 1. The Problem: Network Islands

The current mesh implementation, based on Google's Nearby Connections API with the `Strategy.P2P_CLUSTER`, is susceptible to a "split-brain" or "network island" problem. This occurs when groups of nodes boot up and form dense, stable clusters that are unaware of each other, even if they are all within physical proximity.

The root cause is the connection management logic in `NearbyConnectionsManager`:
- A node stops actively looking for new peers once it reaches `TARGET_CONNECTIONS` (currently 3).
- A node will reject incoming connection requests once it reaches `MAX_CONNECTIONS` (currently 4).

While this creates stable local clusters, it means that once a cluster is "full," it becomes closed off. Two separate clusters will never discover each other because no node in either cluster is actively listening for new endpoints. The existing `TopologyOptimizer` can only optimize connections between *known* nodes within a single island; it cannot discover new, unknown islands.

## 2. The Solution: Proactive Island Discovery

To solve this, we will introduce a new, proactive "Island Discovery" phase into the `TopologyOptimizer`. The core idea is to intentionally introduce a small amount of controlled churn into a stable, saturated cluster. By periodically dropping a redundant connection, a node creates a free slot and a reason to re-enter a discovery mode, creating an opportunity to find and connect to a node from a different island, thereby merging the two.

This strategy extends the existing self-optimization logic. While the primary goal of the optimizer is to improve efficiency within the known network, this new secondary goal is to expand the known network.

## 3. Implementation Plan

The implementation will require modifications to both `TopologyOptimizer` and `NearbyConnectionsManager`.

### 3.1. `TopologyOptimizer.kt` Modifications

The `TopologyOptimizer` will be responsible for initiating the discovery phase.

#### 3.1.1. Add a Dedicated Discovery Timer
The current rewiring analysis runs every 60 seconds. Island discovery should be less frequent to minimize disruption. A new, separate timer should be added to trigger the island discovery logic.

**Recommendation:** Run the island discovery analysis every **5 minutes**.

```kotlin
// In TopologyOptimizer.kt
private const val ISLAND_DISCOVERY_ANALYSIS_INTERVAL_MS = 300_000L // 5 minutes

// In start()
scope.launch {
    // ... existing timers
    startIslandDiscoveryAnalysis()
}

private fun CoroutineScope.startIslandDiscoveryAnalysis() = launch {
    while (true) {
        delay(ISLAND_DISCOVERY_ANALYSIS_INTERVAL_MS)
        analyzeAndPerformIslandDiscovery()
    }
}
```

#### 3.1.2. Implement the Island Discovery Logic (`analyzeAndPerformIslandDiscovery`)
This new function will contain the heuristic for triggering the discovery phase.

**Trigger Conditions:**
1. The node must be in a "saturated" state, meaning its connection count is at or above `TARGET_CONNECTIONS`.
2. The node must identify a "redundant" local connection. The existing logic for finding a "local triangle" is a perfect heuristic for this (i.e., the node is connected to Peer A and Peer B, who are also connected to each other).

**Action:**
If both conditions are met, the `TopologyOptimizer` will instruct the `NearbyConnectionsManager` to:
1. Disconnect from the identified redundant peer.
2. Immediately enter a time-limited "discovery mode."

```kotlin
// In TopologyOptimizer.kt
private fun analyzeAndPerformIslandDiscovery() {
    logger.log("Analyzing network for potential islands.")

    val myPeers = connectionsManager.connectedPeerIds
    if (myPeers.size < TARGET_CONNECTIONS) {
        logger.log("Not enough connections to justify island discovery. Skipping.")
        return
    }

    // Find a redundant local connection (triangle)
    val redundantPeer = findRedundantPeer() // Reuse or adapt existing logic
    if (redundantPeer == null) {
        logger.log("No redundant peer found to drop for island discovery. Skipping.")
        return
    }

    logger.log("Initiating island discovery: Dropping redundant peer '$redundantPeer' to search for new islands.")
    connectionsManager.disconnectFromEndpoint(redundantPeer)
    connectionsManager.enterDiscoveryMode()
}
```

### 3.2. `NearbyConnectionsManager.kt` Modifications

The `NearbyConnectionsManager` needs to be updated to support the new time-limited discovery mode.

#### 3.2.1. Add State for Discovery Mode
A new state variable is needed to track whether the manager is in its special discovery mode.

```kotlin
// In NearbyConnectionsManager.kt
private var isDiscoveryModeActive = false
private var discoveryModeJob: Job? = null
```

#### 3.2.2. Create the `enterDiscoveryMode()` Function
This function will be called by the `TopologyOptimizer`. It will set the discovery mode state and start a coroutine that automatically exits discovery mode after a timeout.

**Recommendation:** A discovery timeout of **20 seconds** provides a reasonable window to find new endpoints without leaving the node disconnected for too long.

```kotlin
// In NearbyConnectionsManager.kt
fun enterDiscoveryMode() {
    if (isDiscoveryModeActive) {
        logger.log("Already in discovery mode. Ignoring request.")
        return
    }
    logger.log("Entering time-limited island discovery mode for 20 seconds.")
    isDiscoveryModeActive = true

    // Cancel any previous job to be safe
    discoveryModeJob?.cancel()

    discoveryModeJob = CoroutineScope(Dispatchers.IO).launch {
        delay(20_000L)
        if (isDiscoveryModeActive) {
            logger.log("Island discovery timeout reached. Exiting discovery mode.")
            isDiscoveryModeActive = false
        }
    }
}
```

#### 3.2.3. Modify `onEndpointFound` Logic
The `onEndpointFound` callback must be updated to handle discovery mode. When in discovery mode, it should attempt to connect to *any* newly discovered endpoint, even if the node is at `TARGET_CONNECTIONS`.

```kotlin
// In NearbyConnectionsManager.kt, inside endpointDiscoveryCallback
override fun onEndpointFound(endpointId: String, discoveredEndpointInfo: DiscoveredEndpointInfo) {
    logger.log("onEndpointFound: ${discoveredEndpointInfo.endpointName} (id:$endpointId)")

    if (isDiscoveryModeActive) {
        logger.log("In discovery mode, attempting to connect to new endpoint '$endpointId' to merge islands.")
        isDiscoveryModeActive = false // Exit discovery mode immediately upon finding a candidate
        discoveryModeJob?.cancel()
        requestConnection(endpointId)
        return
    }

    if (connectedEndpoints.size < TARGET_CONNECTIONS) {
        logger.log("Attempting to connect to $endpointId (current connections: ${connectedEndpoints.size})")
        requestConnection(endpointId)
    } else {
        logger.log("Skipping connection to $endpointId (already at target connections: ${connectedEndpoints.size})")
    }
}
```

### 3.3. Fallback Behavior

As per the design, there is no automatic reconnection to the dropped peer if the discovery phase fails. If the 20-second discovery window times out, the node simply exits discovery mode and remains with a free connection slot. This behavior is intentional, as it prioritizes the potential for a future island merge over restoring a redundant local connection. The node will naturally form a new connection later, either through a peer initiating a connection or during its next regular discovery attempt when its connection count is below the target.

---

This strategy creates a robust mechanism for healing network partitions by leveraging existing topology analysis concepts and introducing a controlled discovery phase, ensuring the mesh network can grow and merge dynamically.
