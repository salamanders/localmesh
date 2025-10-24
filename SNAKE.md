# Snake Topology Design (`SNAKE.md`)

This document outlines a new, simplified network topology for the LocalMesh project. The goal is to move from a complex, self-optimizing mesh network to a simple, self-organizing linear chain (a "snake").

## 1. Goal

The primary goal is to replace the current complex and sometimes unstable mesh topology with a simple, predictable, and robust linear chain of devices. The current `TopologyOptimizer` and `ConnectionManager` sometimes "fight" over connections, and the practical limits of Bluetooth connections have proven lower than hoped.

By enforcing a strict maximum of two connections per device, the network will self-organize into a "snake" (e.g., `A-B-C-D`). This simplifies the logic, reduces network overhead, and creates a stable, albeit more fragile, network structure.

## 2. Success Criteria

The implementation will be considered a success when the following criteria are met:

1.  **Linear Chain Formation:** When multiple devices are brought online, they consistently form a single, non-looping chain where each "body" node has 2 connections and the two "endpoint" nodes have 1 connection.
2.  **Loop Prevention:** The network must never form a closed loop (e.g., the two endpoints of a chain must not connect to each other).
3.  **Self-Healing:** If a node in the middle of the snake disconnects, the two resulting shorter snakes should be able to find each other (or other snakes/isolated nodes) and eventually reform into a single, longer snake.
4.  **Component Simplification:** The `TopologyOptimizer.kt` class is successfully removed and replaced with a new, simpler `SnakeConnectionAdvisor.kt`.

## 3. Refactoring Plan

This section details the necessary code changes to implement the snake topology.

### Step 1: Remove `TopologyOptimizer.kt`

-   Delete the file `mesh-logic/src/main/java/com/example/mesh/logic/TopologyOptimizer.kt`.
-   Remove any references to `TopologyOptimizer` from other files, primarily `BridgeService.kt`.

### Step 2: Create `SnakeConnectionAdvisor.kt`

-   Create a new file: `mesh-logic/src/main/java/com/example/mesh/logic/SnakeConnectionAdvisor.kt`.
-   This class will be responsible for all the "snake" logic. It is **not** a coroutine-based service but a state manager and decision-making utility.

**Responsibilities of `SnakeConnectionAdvisor`:**

-   **State Tracking:**
    -   Maintain the current number of active connections.
    -   Store a cache of node IDs that are part of the current snake, built from received gossip messages. This cache must have a TTL (Time-To-Live) of 1 minute, as specified.
-   **Decision Making:**
    -   Provide a method `shouldStartDiscovery()` which returns `true` if the connection count is 0 or 1, and `false` if it is 2.
    -   Provide a method `canConnectToPeer(peerId: String)` which returns `true` only if:
        1.  The current connection count is less than 2.
        2.  The `peerId` is not present in the recently cached list of nodes already in the snake.
-   **Gossip Management:**
    -   Provide a method `createGossipMessage()` that creates a `NetworkMessage` containing the list of all known node IDs in the current snake.
    -   Provide a method `handleGossipMessage(message: NetworkMessage)` to update its internal cache of snake node IDs.

### Step 3: Modify `ConnectionManager` and its Implementations

The `ConnectionManager` interface and its concrete classes (`NearbyConnectionsManager`, `SimulatedConnectionManager`) need to be updated.

-   **`ConnectionManager.kt` (Interface):**
    -   Modify the `startDiscovery()` method to accept a `payload: ByteArray`. This payload will contain the node's current connection count.
-   **`NearbyConnectionsManager.kt`:**
    -   Update the `startDiscovery` implementation to pass the payload into the `startAdvertising` and `startDiscovery` calls of the underlying Google Nearby Connections API. The advertising payload will now include the node's connection count.
    -   When a peer is discovered (`onEndpointFound`), the `DiscoveredEndpointInfo` will contain the peer's advertising payload. This payload must be parsed to extract the peer's connection count.
    -   The connection logic should now only attempt to connect to peers whose advertised connection count is less than 2.
-   **`SimulatedConnectionManager.kt`:**
    -   Update the simulation logic to mimic the advertising payload behavior.

### Step 4: Update `BridgeService.kt`

`BridgeService` will act as the orchestrator, using the new `SnakeConnectionAdvisor` to direct the `ConnectionManager`.

-   Instantiate `SnakeConnectionAdvisor`.
-   On connection changes (connect/disconnect), update `SnakeConnectionAdvisor` with the new connection count.
-   Based on the output of `snakeConnectionAdvisor.shouldStartDiscovery()`, call `connectionManager.startDiscovery()` or `connectionManager.stopDiscovery()`. When starting, it will pass a payload containing the current connection count.
-   When a peer is discovered, `BridgeService` will ask the `snakeConnectionAdvisor.canConnectToPeer(peerId)` before initiating a connection.
-   `BridgeService` will be responsible for periodically creating and broadcasting the gossip message from the advisor.

## 4. Logic Flow

### Node States

A node's behavior is determined by its number of active connections.

-   **State 0: Isolated (0 connections)**
    -   **Action:** Continuously advertise with payload `[0]` and discover other nodes.
    -   **Goal:** Find any other node with < 2 connections and connect to it.
-   **State 1: Endpoint (1 connection)**
    -   **Action:** Continuously advertise with payload `[1]` and discover other nodes.
    -   **Goal:** Find another "Isolated" or "Endpoint" node to connect to, extending the snake.
-   **State 2: Body Segment (2 connections)**
    -   **Action:** Stop advertising and discovering. The node becomes a passive data relay.
    -   **Goal:** Maintain its two connections and pass gossip messages along the chain.

### Connection Handshake (Loop Prevention)

1.  Node `D` (an Endpoint) discovers Node `A` (also an Endpoint).
2.  `D`'s `ConnectionManager` sees from `A`'s advertising payload that `A` has 1 connection and is thus a potential candidate.
3.  `D`'s `BridgeService` asks its `SnakeConnectionAdvisor`: `canConnectToPeer("A")?`.
4.  The `SnakeConnectionAdvisor` checks its internal cache, which has been populated by gossip messages traveling down the snake. It sees that "A" is already part of its snake.
5.  The advisor returns `false`.
6.  `BridgeService` aborts the connection attempt, preventing a loop.

### Gossip Mechanism

-   **Content:** The gossip message is a `NetworkMessage` containing a timestamp and a list of all node IDs in the snake (e.g., `["A", "B", "C", "D"]`).
-   **Propagation:** When a node receives a gossip message, it updates its internal cache and forwards the message to its other connected peer (if it has one). This ensures the message travels the length of the snake.
-   **Timeout:** The `SnakeConnectionAdvisor` will only consider node IDs from gossip messages received within the last 60 seconds. This ensures that if a connection is dropped, the stale information is eventually discarded, allowing for reconnection.

## 5. Pros and Cons

### Pros

-   **Simplicity:** The logic is far simpler than a mesh optimizer. It removes complex algorithms for island merging and redundancy checks.
-   **Stability:** By strictly limiting connections, the network is less likely to enter a state of constant reorganization.
-   **Reduced Overhead:** "Body Segment" nodes turn off discovery, saving significant battery and reducing Bluetooth/Wi-Fi channel congestion.

### Cons

-   **Fragility:** The network is a single point of failure. If any "Body Segment" node disconnects, the snake is broken in two. While it can self-heal, message delivery will fail for the duration of the split.
-   **Higher Latency:** Messages may have to travel across many hops to get from one end of the snake to the other, increasing latency compared to a well-connected mesh.
