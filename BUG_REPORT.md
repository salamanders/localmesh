# Bug Report

This document outlines and consolidates bugs, code smells, and other issues found in the LocalMesh codebase. Issues are sorted by severity.

---

## 1. `TopologyOptimizer` is Non-Functional Due to Missing Gossip Mechanism
* **Severity:** High
* **Status:** Closed
* **Description:** The `TopologyOptimizer`'s core logic for network rewiring and island merging is currently non-functional. Its algorithms depend on having a map of neighbors' peers (`neighborPeerLists`), but this map is never populated. The root cause is that the `BridgeService` never broadcasts its own peer list, and there is no logic in `TopologyOptimizer` to process such a broadcast. This renders all topology optimization logic, such as `findRedundantPeer()`, useless.
* **Likely Files:**
    * `mesh-logic/src/main/kotlin/info/benjaminhill/localmesh/logic/TopologyOptimizer.kt`
    * `app/src/main/java/info/benjaminhill/localmesh/mesh/BridgeService.kt`
* **Resolution:** Implement a gossip protocol. `BridgeService` needs a periodic task to broadcast its connected peer list. `TopologyOptimizer` needs to process these broadcasts and populate the `neighborPeerLists` map.

---

## 2. Security Vulnerability: WebView Auto-Grants All Permissions
* **Severity:** High
* **Status:** Won't Fix - needed for the demo.
* **Description:** The `WebChromeClient` in `WebViewScreen.kt` is configured to automatically grant any permission requested by a web page, including camera, microphone, and location. This is a major security risk, as a malicious page could spy on the user without their consent.
* **Likely Files:** `app/src/main/java/info/benjaminhill/localmesh/display/WebViewScreen.kt`
* **Resolution:** Remove the automatic grant. The `onPermissionRequest` handler should be removed or modified to prompt the user for a decision.

---

## 3. File Transfers Corrupted by Incorrect Deserialization
* **Severity:** High
* **Status:** Open
* **Description:** When receiving a `NetworkMessage`, the incoming `ByteArray` is converted to a UTF-8 string before being decoded. This corrupts the `data: ByteArray` field within any `FileChunk`, as raw binary data is not valid UTF-8. This will break the file sharing feature.
* **Likely Files:**
    * `mesh-logic/src/main/kotlin/info/benjaminhill/localmesh/logic/TopologyOptimizer.kt`
    * `mesh-logic/src/main/kotlin/info/benjaminhill/localmesh/logic/NetworkMessage.kt`
* **Resolution:** Use a binary-safe serialization format or encode the `ByteArray` to Base64. A custom `KSerializer` for `ByteArray` can be created to handle Base64 conversion automatically. Deserialization should be done directly from the payload `ByteArray` (`Json.decodeFromByteArray`) instead of from a string.

---

## 4. Resource Leak from Unmanaged Coroutine Scopes in `BridgeService`
* **Severity:** High
* **Status:** Closed
* **Description:** The `handleIncomingData` and `listenForIncomingData` functions launch new coroutines using a new `CoroutineScope` for each incoming message. These scopes are not tied to the service's lifecycle, causing a resource leak that can lead to `OutOfMemoryError` under a high message load.
* **Likely Files:** `app/src/main/java/info/benjaminhill/localmesh/mesh/BridgeService.kt`
* **Resolution:** Use a single, lifecycle-bound `CoroutineScope` within the `BridgeService`.

---

## 5. Memory Leak in `TopologyOptimizer` from Stale Peer Lists
* **Severity:** High
* **Status:** Closed
* **Description:** The `neighborPeerLists` map in `TopologyOptimizer` is populated but never cleared. When a peer disconnects, its entry is not removed, causing the map to grow indefinitely and leading to a memory leak.
* **Likely Files:** `mesh-logic/src/main/kotlin/info/benjaminhill/localmesh/logic/TopologyOptimizer.kt`
* **Resolution:** Implement a mechanism to remove entries for disconnected peers. This could be tied into the `onDisconnected` event from the `ConnectionManager`.

---

## 6. ConcurrentModificationException in `TopologyOptimizer`
* **Severity:** High
* **Status:** Closed
* **Description:** The `cleanupNodeHopCounts` function iterates and removes entries from `nodeHopCounts` while other functions might be modifying it, leading to a `ConcurrentModificationException`. The use of `ConcurrentHashMap` is not sufficient to protect against concurrent iteration and modification.
* **Likely Files:** `mesh-logic/src/main/kotlin/info/benjaminhill/localmesh/logic/TopologyOptimizer.kt`
* **Resolution:** The original report mentions a flaky test (`IslandMergingTest`) blocking the fix. The fix would likely involve using a concurrent-safe iteration method or proper locking.

---

## 7. Race Condition in `TopologyOptimizer` Can Exceed Connection Limits
* **Severity:** Medium
* **Status:** Open
* **Description:** The `listenForDiscoveredEndpoints` function has a non-atomic check-then-act pattern. If multiple endpoints are discovered in quick succession, it can initiate more connections than the `targetConnections` limit, potentially causing network instability.
* **Likely Files:** `mesh-logic/src/main/kotlin/info/benjaminhill/localmesh/logic/TopologyOptimizer.kt`
* **Resolution:** Synchronize access to the connection logic or use a queueing mechanism to handle discovered endpoints one at a time.

---

## 8. Race Condition Between Rewiring and Island Discovery
* **Severity:** Medium
* **Status:** Open
* **Description:** The `analyzeAndPerformRewiring` and `analyzeAndPerformIslandDiscovery` functions can run concurrently and both can modify network connections. This can lead to race conditions and unexpected network behavior.
* **Likely Files:** `mesh-logic/src/main/kotlin/info/benjaminhill/localmesh/logic/TopologyOptimizer.kt`
* **Resolution:** Use a mutex or other synchronization mechanism to ensure only one of these analysis functions can modify the network at a time.

---

## 9. Potential Race Condition in `ServiceHardener` Initialization
* **Severity:** Medium
* **Status:** Open
* **Description:** The check to see if the `scheduler` is already running in `ServiceHardener.start()` is not atomic. Multiple rapid calls to `start()` could lead to multiple schedulers being created.
* **Likely Files:** `app/src/main/java/info/benjaminhill/localmesh/mesh/ServiceHardener.kt`
* **Resolution:** The original report mentions a flaky test (`CacheAndDisplayTest`) blocking the fix. The fix would likely involve using `synchronized` blocks or a more robust state management mechanism.

---

## 10. Potential Resource Leak from Unclear WebView Lifecycle
* **Severity:** Medium
* **Status:** Open
* **Description:** The `WebView` instance in `DisplayActivity.kt` is managed by Jetpack Compose. It's not clear if it's being properly destroyed (e.g., `webView.destroy()`) when the composable is removed from the screen, which could lead to resource leaks.
* **Likely Files:**
    * `app/src/main/java/info/benjaminhill/localmesh/display/DisplayActivity.kt`
    * `app/src/main/java/info/benjaminhill/localmesh/display/WebViewScreen.kt`
* **Resolution:** Use the `onRelease` block within the `AndroidView` to explicitly call `webView.destroy()`.

---

## 11. Usability Bug: Race Condition in `onNewIntent` Can Load Incorrect URL
* **Severity:** Medium
* **Status:** Open
* **Description:** In `DisplayActivity`, multiple intents arriving in quick succession can cause a race condition, where the `WebView` may not load the URL from the most recent intent, leading to a confusing user experience.
* **Likely Files:** `app/src/main/java/info/benjaminhill/localmesh/display/DisplayActivity.kt`
* **Resolution:** Decouple URL loading from state updates. Use a `LaunchedEffect` that listens for URL changes and ensures the `WebView` loads them sequentially and only when it's ready.