Question: com.google.android.gms.nearby.connection Strategy.P2P_CLUSTER - how many phones can it
handle, realistically speaking? Try not to guess, I'd like a page that says for sure.

Answer from Gemini:

Based on information from a Google engineer working on the Nearby Connections team, the realistic
limit for a single phone using **`Strategy.P2P_CLUSTER`** is **3 to 4 simultaneous connections** at
a time.

Here is a more detailed breakdown based on the technical limitations:

* **Official Documentation vs. Realistic Limits:** The official Google Developers documentation
  describes `P2P_CLUSTER` as supporting an "M-to-N, or cluster-shaped, connection topology," where
  each device can connect to "M" other devices and accept connections from "N" other devices.
  However, this documentation does not provide a specific number.
* **The Technical Bottleneck:** The practical limitation comes from the underlying Bluetooth radio
  in the phones. According to a Stack Overflow answer provided by a member of the Google Nearby
  Connections team, a phone's Bluetooth radio can only reliably hold about 3 to 4 simultaneous
  connections.
* **How `P2P_CLUSTER` Handles More Devices:** The `P2P_CLUSTER` strategy is designed to create a "
  mesh-like" network. While a single device can only connect to 3-4 peers, you can create a much
  larger cluster by having devices forward messages for each other. For example, to connect a large
  group, the engineer suggested forming a "snake-like" connection, where devices connect to each
  other in a chain to pass information along.

In summary, while a `P2P_CLUSTER` network can theoretically contain many devices, any individual
phone within that cluster is realistically limited to **3-4 active, simultaneous connections**. This
is confirmed by other developer reports of connections becoming unstable and dropping when
attempting to connect more than 4 devices to a single host.

***

**Source:**
This information is synthesized from a Stack Overflow discussion where a user named "Xlythe,"
self-identified as working on Google's Nearby Connections team, answered this question. In a thread
titled "Be able to send Messages/Bytes Simultaneous to multiple devices using Nearby Connections" (
Stack Overflow question 52773197), they state:

> "The Bluetooth radio inside phones is weak and can only hold 3~4 connections at a time. To be able
> to connect all ~30 devices, I'd recommend forming a 'snake-like' connection."

***

## Solution

The original implementation used `Strategy.P2P_CLUSTER` and attempted to connect to every discovered device. This created a dense, fully-connected network that quickly exceeded the 3-4 simultaneous connection limit of the underlying Bluetooth radio, making the mesh unstable beyond a handful of devices.

The new implementation addresses this limitation with a two-part strategy:

### 1. Sparse Mesh Connection Strategy

Instead of connecting to every available peer, each device now aims to maintain a small, constant number of connections (currently set to 3). This is managed by the `NearbyConnectionsManager`. When a new device is discovered, the manager checks its current number of active connections. If it is already at its target, it will ignore the discovery and not attempt to form a new connection.

This approach prevents any single device from being overloaded, allowing the network to scale to a much larger number of devices.

### 2. Gossip Protocol for Message Forwarding

A sparse mesh requires a mechanism to ensure messages can reach all nodes, not just a node's immediate neighbors. A "gossip" or "flooding" protocol was implemented to solve this.

- **Message Wrapping:** When a device broadcasts a message, it is wrapped in a container that includes a unique message ID (a UUID).
- **Forwarding Logic:** When a node receives a message, it checks its local cache of seen message IDs.
  - If the ID is new, the node processes the message, adds the ID to its cache, and then re-broadcasts the *entire wrapped message* to all of its connected peers.
  - If the ID is already in the cache, the message is ignored, preventing infinite broadcast loops.

This ensures that a message sent from any node will rapidly propagate throughout the entire mesh, reaching every participant while respecting the hardware connection limits of each individual device.