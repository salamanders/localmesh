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
  The `handleIncomingData` and `listenForIncomingData` functions create new, unmanaged
  `CoroutineScope`s for every incoming message. These coroutines are not tied to the lifecycle of
  the service and will continue to run in the background, leading to a resource leak. A high volume
  of messages will cause a large number of coroutines to be created, which will likely lead to an
  `OutOfMemoryError` or thread exhaustion.
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
  The `neighborPeerLists` map in `TopologyOptimizer` is populated with the peer lists of connected
  nodes. However, there is no mechanism to remove entries from this map when a peer disconnects.
  This will cause the map to grow indefinitely as the network topology changes, leading to a memory
  leak and eventually an `OutOfMemoryError`.
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
  The `analyzeAndPerformRewiring` and `analyzeAndPerformIslandDiscovery` functions both modify the
  network topology by disconnecting from peers. These two functions can run concurrently and are not
  synchronized, which can lead to a race condition. For example, `analyzeAndPerformRewiring` could
  disconnect from a peer that `analyzeAndPerformIslandDiscovery` is also about to disconnect from,
  leading to unexpected behavior and potential network instability.
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


# Skeptic's Report: Analysis of `TopologyOptimizer.kt`

After a line-by-line review of the P2P logic, I have identified several critical bugs that prevent
the `TopologyOptimizer` from functioning as intended. The core issue is a complete absence of the
necessary gossip mechanism to share network topology information.

## 1. CRITICAL: `TopologyOptimizer` is non-functional due to missing gossip data.

- **The Defect:** The `TopologyOptimizer`'s primary functions, `analyzeAndPerformRewiring()` and
  `analyzeAndPerformIslandDiscovery()`, depend entirely on the `neighborPeerLists` map. This map is
  supposed to contain the list of peers for each of the current node's neighbors. However, **the
  code to populate this map is missing.** The `listenForIncomingPayloads` function only processes
  `httpRequest` messages to track hop counts and does not handle incoming peer lists.
- **The Impact:** Because `neighborPeerLists` is always empty, the `findRedundantPeer()` function
  always returns `null`. This disables the entire rewiring and island discovery logic, rendering the
  `TopologyOptimizer` useless. The network cannot heal itself, form a "small-world" topology, or
  merge islands.

## 2. CRITICAL: `BridgeService` never sends peer lists.

- **The Defect:** The `TopologyOptimizer` fails because it never receives peer data. The reason it
  never receives this data is that `BridgeService.kt`, which manages all P2P communication, **never
  sends it.** There is no timer or trigger that periodically gossips the node's current list of
  connected peers to its neighbors. The existing `broadcast` and `sendFile` methods only handle HTTP
  requests and file chunks, respectively.
- **The Impact:** This is the root cause of the `TopologyOptimizer`'s failure. Without this
  essential gossip message, no node can build a picture of the surrounding network topology, making
  any kind of optimization impossible.

## 3. BUG: Race condition can lead to exceeding connection limits.

- **The Defect:** In `TopologyOptimizer.kt`, the `listenForDiscoveredEndpoints` function checks the
  number of connected peers and then attempts to connect to a new one. This check-then-act pattern
  is not atomic. If multiple new peers are discovered in rapid succession, the code can initiate
  connections to all of them before the `connectedPeers` state is updated.
- **The Impact:** This can cause a node to temporarily exceed its `targetConnections` limit.
  According to `P2P_DOCS.md`, exceeding the practical limit of 3-4 connections can cause significant
  network instability and disconnections. This bug directly undermines the goal of maintaining a
  stable network core.

# Display Logic and WebView Handling

## 4. SECURITY RISK: WebView auto-grants all permission requests.

- **The Defect:** In `WebViewScreen.kt`, the `WebChromeClient`'s `onPermissionRequest` callback
  immediately grants any permission requested by the loaded web page (
  `request.grant(request.resources)`). This includes potentially sensitive permissions like camera,
  microphone, and geolocation.
- **The Impact:** A malicious or compromised web page served to the WebView could activate a
  device's camera or microphone without any user interaction or consent, enabling it to spy on the
  user. This is a significant security vulnerability.

## 5. BUG: Unclear WebView lifecycle management can lead to resource leaks.

- **The Defect:** In `DisplayActivity.kt`, the `WebView` instance is created within the
  `WebViewScreen` Composable. While the `DisplayActivity` has `onCreate` and `onDestroy` lifecycle
  methods, the `WebView` itself is managed by Compose. It's not explicitly clear if the `WebView` is
  being properly destroyed and its resources (like the heartbeat handler) are being fully released
  when the activity is destroyed, especially in complex lifecycle scenarios. The heartbeat
  `Runnable` is posted to a `Handler`, and while `removeCallbacks` is called in `onDestroy`, complex
  interactions between the Activity lifecycle, Compose recomposition, and the Handler could
  potentially lead to leaks.
- **The Impact:** This could lead to subtle resource leaks or unexpected behavior, where a `WebView`
  or its associated handlers continue to consume resources in the background even after the UI is no
  longer visible.

## 6. USABILITY BUG: `onNewIntent` race condition can lead to incorrect URL loading.

- **The Defect:** In `DisplayActivity.kt`, the `pathState` is a `mutableStateOf<String?>`. The
  `onNewIntent` method updates this state, which triggers a recomposition in `WebViewScreen`. The
  `update` block of the `AndroidView` then calls `it.loadUrl(url)`. However, if multiple intents
  arrive in quick succession, there's no guarantee about the order of recompositions and `loadUrl`
  calls. The `WebView` might not have finished loading the first URL before it's instructed to load
  the second.
- **The Impact:** The user might see a flicker of the first page before the second one loads, or in
  a more severe case, the WebView could get into a confused state. The final displayed page might
  not correspond to the last received intent if the system is under heavy load.

# Skeptic's Report: Analysis of WebView Implementation

Following a review of the WebView implementation in `WebViewScreen.kt` and `DisplayActivity.kt`, I
have identified a critical security vulnerability and two additional bugs related to resource
management and state handling.

## 1. CRITICAL: WebView automatically grants all permission requests.

- **The Defect:** In `WebViewScreen.kt`, the `WebChromeClient`'s `onPermissionRequest` callback
  immediately grants any permission requested by the loaded web content by calling
  `request.grant(request.resources)`. This includes potentially sensitive permissions like camera,
  microphone, and geolocation.
- **The Impact:** This creates a significant security risk. A malicious webpage could exploit this
  to gain access to the device's hardware without any user interaction or consent, leading to
  privacy violations.
- **The Fix:** The `onPermissionRequest` handler should be removed or modified to require explicit
  user approval before granting permissions. A dialog could be presented to the user to allow or
  deny the request.
- **Proof of Fix:** A test page with a script that requests a permission (e.g.,
  `navigator.mediaDevices.getUserMedia`) would be loaded. The fix is verified if the permission is
  not automatically granted and, ideally, a prompt is shown to the user.

## 2. BUG: Potential for WebView resource leak in Jetpack Compose.

- **The Defect:** The `WebView` is created within the `AndroidView` factory in `WebViewScreen.kt`.
  While `AndroidView` handles basic view lifecycle, a `WebView` can have a complex internal state
  and ongoing processes (like JavaScript execution or network requests). The current implementation
  does not explicitly handle the destruction of the WebView (e.g., calling `webView.destroy()`) when
  the composable leaves the composition.
- **The Impact:** This can lead to a resource leak. The WebView instance might not be properly
  garbage collected, retaining memory and potentially continuing background processes even after the
  user has navigated away from the screen, which can degrade app performance and increase battery
  consumption.
- **The Fix:** An `onRelease` block should be added to the `AndroidView` to properly clean up the
  WebView. This would involve stopping its loading, clearing its history, and calling `destroy()` on
  the WebView instance.
- **Proof of Fix:** This is difficult to prove with a simple automated test. Manual verification
  using Android Studio's memory profiler would be required to confirm that the WebView instance is
  garbage collected after navigating away from the `WebViewScreen`.

## 3. BUG: Race condition in `onNewIntent` can cause incorrect URL loading.

- **The Defect:** In `DisplayActivity.kt`, a new URL is loaded by updating `pathState`, which
  triggers a recomposition of `WebViewScreen`. However, the `update` block of the `AndroidView` (
  where `it.loadUrl(url)` is called) might be invoked before the `factory` block (which creates the
  WebView) has completed, especially during the initial creation of the activity. If a new intent
  arrives in `onNewIntent` very quickly after `onCreate`, the `pathState` could be updated, but the
  `WebView` instance might not be ready to load the new URL, or it might load the initial URL from
  `onCreate` after the new URL has already been set.
- **The Impact:** The user might see an outdated or incorrect page. A command from a peer to display
  a specific page could be ignored or overridden by the default "index.html" page, leading to a
  confusing and inconsistent user experience.
- **The Fix:** The URL loading logic should be decoupled from the composable's state update. One
  approach is to use a `LaunchedEffect` that listens for changes in the URL state and loads the new
  URL into the WebView instance only after the WebView has been created and is ready. This ensures
  that URL updates are processed sequentially and only when the WebView is in a valid state.
- **Proof of Fix:** A test could be written to launch the `DisplayActivity`, immediately send a new
  intent with a different URL, and then verify that the WebView's final loaded URL is the one from
  the second intent, not the initial one from `onCreate`.


# Skepticism Report 2

This document outlines major logical concerns with the new code in
`mesh-logic/src/main/kotlin/info/benjaminhill/localmesh/logic`.

## 1. `TopologyOptimizer` is Non-Functional

The core logic of `TopologyOptimizer.kt` is fundamentally broken and cannot perform its primary
functions of network rewiring or island merging.

* **Cause:** The optimization heuristics in `analyzeAndPerformRewiring()` and
  `analyzeAndPerformIslandDiscovery()` are entirely dependent on the `findRedundantPeer()` method.
  This method, in turn, relies on the `neighborPeerLists` map to identify network triangles (where
  two of our peers are also connected to each other).
* **The Gap:** A review of the code shows that **the `neighborPeerLists` map is never populated.**
  The `listenForIncomingPayloads` function only processes `httpRequest` messages to populate hop
  counts; it does not handle any kind of gossip message for sharing peer lists.
* **Impact:** Since `neighborPeerLists` is always empty, `findRedundantPeer()` will always return
  `null`. As a result, the rewiring and island discovery logic will never be triggered. The
  optimizer is effectively dead code.

## 2. No Gossip Mechanism

Related to the above, there is no corresponding mechanism for a node to broadcast its own peer list
to its neighbors.

* **The Gap:** The constant `GOSSIP_INTERVAL_MS` is defined but never used to schedule a periodic
  task for sending topology information.
* **Impact:** Without a gossip mechanism, nodes have no way to learn about the network structure
  beyond their immediate neighbors, making any topology optimization impossible.

## 3. `NetworkMessage` Deserialization Will Fail

The current implementation for receiving payloads in `TopologyOptimizer.listenForIncomingPayloads`
will corrupt any `NetworkMessage` that contains a `FileChunk`.

* **Cause:** The code converts the incoming `ByteArray` payload directly to a UTF-8 string:
  `val jsonString = payload.toString(Charsets.UTF_8)`.
* **The Gap:** The `FileChunk` data class contains a `data: ByteArray` field. Converting raw binary
  data (like an image chunk) to a UTF-8 string will result in data loss and malformed JSON. The
  comment in `FileChunk.kt` even notes that this requires special handling, but that handling has
  not been implemented.
* **Impact:** This will cause `Json.decodeFromString` to fail with a serialization exception for any
  message that is part of a file transfer, effectively breaking the file-sharing feature. The
  message should be decoded directly from the payload `ByteArray` using a format that supports
  binary data.


# Skepticism Report: `mesh-logic`

This document outlines critical, logical flaws in the `mesh-logic` module that will prevent it from
functioning as intended.

---

### Issue 1: Topology Optimization is Non-Functional

**1. What is wrong?**
The core features of `TopologyOptimizer.kt`—network rewiring and island merging—are currently dead
code. The logic to identify opportunities for optimization will never be triggered.

**2. How I know it is wrong:**

- The methods `analyzeAndPerformRewiring()` and `analyzeAndPerformIslandDiscovery()` both depend on
  `findRedundantPeer()` to identify a connection that can be safely dropped.
- `findRedundantPeer()` works by searching for "triangles" in the network graph, where this node is
  connected to two peers (`peerA`, `peerB`) that are also connected to each other.
- To find these triangles, it checks `neighborPeerLists[peerA]` to see if `peerB` is in `peerA`'s
  list of neighbors.
- A full review of `TopologyOptimizer.kt` shows that the `neighborPeerLists` map is **never
  populated with data**. The `listenForIncomingPayloads` function, which is the only place incoming
  data is processed, has no logic to handle peer lists or topology information. It only processes
  `httpRequest` data to update `nodeHopCounts`.
- Since `neighborPeerLists` is always empty, `findRedundantPeer()` will always return `null`, and
  therefore no optimization will ever occur.

**3. How to fix it:**
A gossip protocol must be implemented.

1. **Extend `NetworkMessage`:** Add a new field to `NetworkMessage.kt`, such as
   `gossip: Map<String, List<String>>? = null`, to carry the peer lists of gossiping nodes.
2. **Implement Gossip Broadcast:** In `TopologyOptimizer.kt`, create a new coroutine that runs every
   `GOSSIP_INTERVAL_MS`. This coroutine will get the current list of connected peers from
   `connectionManager.connectedPeers.value`, wrap it in a `NetworkMessage`, serialize it to a
   `ByteArray`, and send it to all connected peers.
3. **Process Incoming Gossip:** In `listenForIncomingPayloads`, add logic to check for the `gossip`
   field in incoming messages. When present, it should update the `neighborPeerLists` map with the
   received peer information.

**4. How to prove the fix worked:**
A unit test can be created using `SimulatedConnectionManager`.

1. Set up a network of 4-5 nodes in a line or other non-optimal topology.
2. Inject a mock logger into the `TopologyOptimizer` instances to monitor their actions.
3. Let the simulation run for a duration longer than `REWIRING_ANALYSIS_INTERVAL_MS`.
4. Assert that `connectionManager.disconnectFrom()` and `connectionManager.connectTo()` were called,
   which would prove that `findRedundantPeer()` returned a non-null value and the rewiring logic was
   executed. The log messages would confirm the reason for the rewiring.

---

### Issue 2: File Transfers Will Be Corrupted

**1. What is wrong?**
The current implementation for deserializing `NetworkMessage` payloads will corrupt any message
containing a `FileChunk`. This breaks the file transfer feature.

**2. How I know it is wrong:**

- In `TopologyOptimizer.kt`, the `listenForIncomingPayloads` function receives a
  `payload: ByteArray`.
- The first action it takes is `val jsonString = payload.toString(Charsets.UTF_8)`.
- The `FileChunk` data class contains a field `data: ByteArray`, which holds the raw binary data of
  the file segment.
- Converting arbitrary binary data (like an image or executable) into a UTF-8 string is a lossy,
  corrupting operation. Invalid byte sequences will be replaced or dropped, and the resulting string
  will not represent the original data.
- The comment in `FileChunk.kt` (`// Note: Requires special handling for ByteArray serialization.`)
  indicates awareness of this problem, but the solution was not implemented.
- When this malformed `jsonString` is passed to `Json.decodeFromString<NetworkMessage>(jsonString)`,
  it will either fail with a `SerializationException` or, worse, succeed but with a corrupted `data`
  field in the `FileChunk`.

**3. How to fix it:**
The serialization and deserialization must handle `ByteArray`s correctly. `kotlinx.serialization`
does not have a built-in `ByteArray` serializer for JSON.

1. **Create a `ByteArraySerializer`:** Implement a custom `KSerializer<ByteArray>` that encodes the
   byte array to a Base64 string and decodes it from Base64.
2. **Apply the Serializer:** Annotate the `data` field in `FileChunk` with
   `@Serializable(with = ByteArraySerializer::class)`.
3. **Fix Deserialization:** Change the `listenForIncomingPayloads` function to use
   `Json.decodeFromByteArray<NetworkMessage>(payload)` instead of converting the payload to a string
   first. This will correctly deserialize the Base64-encoded file chunk data.

**4. How to prove the fix worked:**
A unit test can verify this.

1. Create a `FileChunk` object with a sample `ByteArray` (e.g., `byteArrayOf(0x01, 0x02, 0x03)`).
2. Wrap it in a `NetworkMessage`.
3. Serialize it to a `ByteArray` using the corrected method (`Json.encodeToByteArray`).
4. Deserialize it back into a `NetworkMessage` object using the corrected method (
   `Json.decodeFromByteArray`).
5. Assert that the deserialized `fileChunk.data` is identical to the original `ByteArray` using
   `assertArrayEquals`.
