# Skepticism Report 2

This document outlines major logical concerns with the new code in `mesh-logic/src/main/kotlin/info/benjaminhill/localmesh/logic`.

## 1. `TopologyOptimizer` is Non-Functional

The core logic of `TopologyOptimizer.kt` is fundamentally broken and cannot perform its primary functions of network rewiring or island merging.

*   **Cause:** The optimization heuristics in `analyzeAndPerformRewiring()` and `analyzeAndPerformIslandDiscovery()` are entirely dependent on the `findRedundantPeer()` method. This method, in turn, relies on the `neighborPeerLists` map to identify network triangles (where two of our peers are also connected to each other).
*   **The Gap:** A review of the code shows that **the `neighborPeerLists` map is never populated.** The `listenForIncomingPayloads` function only processes `httpRequest` messages to populate hop counts; it does not handle any kind of gossip message for sharing peer lists.
*   **Impact:** Since `neighborPeerLists` is always empty, `findRedundantPeer()` will always return `null`. As a result, the rewiring and island discovery logic will never be triggered. The optimizer is effectively dead code.

## 2. No Gossip Mechanism

Related to the above, there is no corresponding mechanism for a node to broadcast its own peer list to its neighbors.

*   **The Gap:** The constant `GOSSIP_INTERVAL_MS` is defined but never used to schedule a periodic task for sending topology information.
*   **Impact:** Without a gossip mechanism, nodes have no way to learn about the network structure beyond their immediate neighbors, making any topology optimization impossible.

## 3. `NetworkMessage` Deserialization Will Fail

The current implementation for receiving payloads in `TopologyOptimizer.listenForIncomingPayloads` will corrupt any `NetworkMessage` that contains a `FileChunk`.

*   **Cause:** The code converts the incoming `ByteArray` payload directly to a UTF-8 string: `val jsonString = payload.toString(Charsets.UTF_8)`.
*   **The Gap:** The `FileChunk` data class contains a `data: ByteArray` field. Converting raw binary data (like an image chunk) to a UTF-8 string will result in data loss and malformed JSON. The comment in `FileChunk.kt` even notes that this requires special handling, but that handling has not been implemented.
*   **Impact:** This will cause `Json.decodeFromString` to fail with a serialization exception for any message that is part of a file transfer, effectively breaking the file-sharing feature. The message should be decoded directly from the payload `ByteArray` using a format that supports binary data.
