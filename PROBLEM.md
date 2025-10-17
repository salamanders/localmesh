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