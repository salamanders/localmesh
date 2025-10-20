# Bug Report

This document outlines potential bugs, code smells, and other issues found during a review of the
LocalMesh codebase.

---

## 1. ConcurrentModificationException in `TopologyOptimizer`

* **Severity:** High
* **File:** `mesh-logic/src/main/kotlin/info/benjaminhill/localmesh/logic/TopologyOptimizer.kt`
* **Status:** Still Open
* **Resolution:** A fix was attempted, but it caused a pre-existing flaky test (`IslandMergingTest`)
  to fail consistently. The test has a timing issue and needs to be fixed before the bug can be
  properly addressed.
* **Description:**
  The `cleanupNodeHopCounts` function iterates over `nodeHopCounts` and removes entries while the
  collection is potentially being modified by `listenForIncomingPayloads`. This can lead to a
  `ConcurrentModificationException` and crash the service. The `ConcurrentHashMap` is thread-safe
  for single operations, but not for concurrent iteration and modification.
* **Code Snippet:**
  ```kotlin
  // In listenForIncomingPayloads()
  nodeHopCounts[it.sourceNodeId] = Pair(networkMessage.hopCount.toInt(), System.currentTimeMillis())

  // In cleanupNodeHopCounts()
  nodeHopCounts.entries.removeIf { (_, pair) ->
      now - pair.second > NODE_HOP_COUNT_EXPIRY_MS
  }
  ```

---

## 2. Potential Race Condition in `ServiceHardener`

* **Severity:** Medium
* **File:** `app/src/main/java/info/benjaminhill/localmesh/mesh/ServiceHardener.kt`
* **Status:** Still Open
* **Resolution:** A fix was attempted, but it caused a pre-existing flaky test (
  `CacheAndDisplayTest`) to fail consistently due to a `BindException`. The test suite needs to be
  stabilized before this bug can be fixed.
* **Description:**
  The `scheduler` is initialized and started in `start()`, but it's not guaranteed to be stopped
  before `start()` is called again. The check
  `if (::scheduler.isInitialized && !scheduler.isShutdown)` is not sufficient to prevent a race
  condition where `start()` is called multiple times in quick succession, leading to multiple
  schedulers being created.
* **Code Snippet:**
  ```kotlin
  fun start() {
      // ...
      if (::scheduler.isInitialized && !scheduler.isShutdown) {
          logger.log("Hardener scheduler already running.")
          return
      }
      scheduler = Executors.newSingleThreadScheduledExecutor()
      // ...
  }
  ```
---

## 3. Unmanaged Coroutine Scopes in `BridgeService`

* **Severity:** High
* **File:** `app/src/main/java/info/benjaminhill/localmesh/mesh/BridgeService.kt`
* **Status:** Open
* **Description:**
  The `handleIncomingData` and `listenForIncomingData` functions create new, unmanaged `CoroutineScope`s for every incoming message. These coroutines are not tied to the lifecycle of the service and will continue to run in the background, leading to a resource leak. A high volume of messages will cause a large number of coroutines to be created, which will likely lead to an `OutOfMemoryError` or thread exhaustion.
* **Code Snippet:**
  ```kotlin
  // In handleIncomingData()
  CoroutineScope(ioDispatcher).launch {
      localHttpServer.dispatchRequest(wrapper)
  }

  // In listenForIncomingData()
  CoroutineScope(ioDispatcher).launch {
      nearbyConnectionsManager.incomingPayloads.collect { (endpointId, data) ->
          logger.log("Collected ${data.size} bytes from $endpointId from flow.")
          handleIncomingData(data)
      }
  }
  ```

---

## 4. Memory Leak in `TopologyOptimizer`

* **Severity:** High
* **File:** `mesh-logic/src/main/kotlin/info/benjaminhill/localmesh/logic/TopologyOptimizer.kt`
* **Status:** Open
* **Description:**
  The `neighborPeerLists` map in `TopologyOptimizer` is populated with the peer lists of connected nodes. However, there is no mechanism to remove entries from this map when a peer disconnects. This will cause the map to grow indefinitely as the network topology changes, leading to a memory leak and eventually an `OutOfMemoryError`.
* **Code Snippet:**
  ```kotlin
  // In listenForIncomingPayloads()
  networkMessage.gossip?.let {
      neighborPeerLists[endpointId] = it.peerList
  }
  ```

---

## 5. Race Condition in `TopologyOptimizer`

* **Severity:** Medium
* **File:** `mesh-logic/src/main/kotlin/info/benjaminhill/localmesh/logic/TopologyOptimizer.kt`
* **Status:** Open
* **Description:**
  The `analyzeAndPerformRewiring` and `analyzeAndPerformIslandDiscovery` functions both modify the network topology by disconnecting from peers. These two functions can run concurrently and are not synchronized, which can lead to a race condition. For example, `analyzeAndPerformRewiring` could disconnect from a peer that `analyzeAndPerformIslandDiscovery` is also about to disconnect from, leading to unexpected behavior and potential network instability.
* **Code Snippet:**
  ```kotlin
  // In startRewiringAnalysis()
  private fun CoroutineScope.startRewiringAnalysis() = launch {
      while (true) {
          delay(REWIRING_ANALYSIS_INTERVAL_MS)
          analyzeAndPerformRewiring()
      }
  }

  // In startIslandDiscoveryAnalysis()
  private fun CoroutineScope.startIslandDiscoveryAnalysis() = launch {
      // ...
      while (true) {
          delay(ISLAND_DISCOVERY_ANALISYS_INTERVAL_MS)
          analyzeAndPerformIslandDiscovery()
      }
  }
  ```