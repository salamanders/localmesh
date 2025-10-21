### **Title: A Standardized, Multi-Hop Communication Protocol**

### **1. The Goal**

All messages (chat, display commands) and file transfers must propagate to all nodes in the
connected network graph. The mechanism to achieve this must be standardized, loop-free, and robust.

### **2. The Core Concept: A Unified Gossip Protocol**

The solution is to treat **all** data—commands and file data alike—as a standard `NetworkMessage`
that propagates through the mesh via a single, unified gossip protocol. This provides a robust and
standardized transport layer for any type of application data, rather than having separate logic for
different data types.

### **3. The Unified `NetworkMessage`**

To support file transfers within this new paradigm, the `NetworkMessage` in `mesh-logic` will be
updated to optionally include file chunk data. This avoids using the one-time-read `Payload.Stream`
for file transfers.

```kotlin
// In: mesh-logic/src/main/kotlin/info/benjaminhill/localmesh/logic/NetworkMessage.kt

@Serializable
data class FileChunk(
    val fileId: String,      // Unique ID for the entire file transfer
    val destinationPath: String, // Where to save the reassembled file
    val chunkIndex: Int,
    val totalChunks: Int,
    val data: ByteArray      // Note: Requires special handling for ByteArray serialization
)

@Serializable
data class NetworkMessage(
    val messageId: String = UUID.randomUUID().toString(),
    val hopCount: Int = 0,
    val httpRequest: HttpRequestWrapper? = null,
    val fileChunk: FileChunk? = null
)
```

### **4. The Gossip & Loop-Prevention Mechanism**

This section explicitly details how full propagation is achieved and how infinite loops are
prevented.

#### **The Rule: "Check, Process, Forward"**

Every node in the network will follow a single, simple rule for every `NetworkMessage` it receives
from a peer:

1. **Check ID:** Look up the `messageId` in a local `seenMessageIds` cache.
2. **If Seen, IGNORE:** If the ID is in the cache, the message is a duplicate from another path or a
   cycle. The process stops here. **This is the core mechanism that makes infinite loops impossible.
   **  There are not so many messages that this will fill up and cause memory issues.
3. **If New, Process & Forward:**
    * **Cache:** Add the `messageId` to the `seenMessageIds` cache.
    * **Process:** If the message contains an `httpRequest`, dispatch it to the local web server. If
      it contains a `fileChunk`, pass it to a new `FileReassemblyManager`.
    * **Forward:** Forward the *exact same `NetworkMessage`* to all connected peers, **except for
      the peer it was received from.**  Remember to increment the hop count.

This protocol guarantees that every message performs a controlled flood-fill of the network,
reaching every connected node exactly once.

### **5. Applying the Protocol**

#### **Chat & Display Commands**

These are already wrapped in `HttpRequestWrapper`. The existing `broadcast()` method in
`BridgeService` will be refactored. Instead of just sending to immediate neighbors, it will create a
`NetworkMessage` and send it into the gossip protocol, which will handle the full network
propagation.

#### **File Transfers (The New Flow)**

This is the biggest change. The `sendFile` method in `BridgeService` and the concept of
`Payload.Stream` for files will be completely replaced.

1. **`sendFile` Rewrite:** The new `sendFile` will:
    1. Take a `File` and `destinationPath` as input.
    2. Generate a unique `fileId`.
    3. Read the file and break it into small (e.g., 16KB) chunks.
    4. For each chunk, create a `NetworkMessage` containing a `FileChunk` object. Each message gets
       its own unique `messageId`.
    5. Feed all of these `NetworkMessage`s into the gossip protocol by sending them to all direct
       peers.

2. **New `FileReassemblyManager` Class:**
    * A new manager class will be created in the `mesh` package to handle incoming `fileChunk`s.
    * It will use a `Map<String, MutableMap<Int, ByteArray>>` to store chunks, keyed by `fileId`.
    * When a chunk arrives, it's stored. If `chunkIndex + 1 == totalChunks` for a given `fileId`,
      the manager reassembles the chunks in order (based on chunkIndex) into a byte array.
    * The reassembled data is written to a temporary file, which is then moved to its final
      `destinationPath` via `AssetManager.saveFile()`.
    * The manager will include a timeout mechanism to discard incomplete files after a certain
      period, preventing memory leaks.

### **6. Summary of Benefits**

* **Standardization:** A single, unified transport logic for all data types (chat, commands, and
  files).
* **Guaranteed Propagation:** All data is guaranteed to reach all nodes in a connected network
  graph.
* **Loop-Free by Design:** The `seenMessageIds` cache provides a simple and mathematically sound way
  to prevent broadcast storms and infinite loops.
* **Efficiency:** The chunking mechanism allows for pipelined file transfers. Intermediate nodes can
  begin forwarding chunks before they have received the entire file, reducing end-to-end latency.