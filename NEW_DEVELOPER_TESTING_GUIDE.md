# New Developer Testing Guide: Topology Optimizer

**Audience:** Developers
**Purpose:** This document provides a technical reference for understanding and testing the self-optimization and island-merging logic within the `TopologyOptimizer`. It consolidates and replaces the previous `ISLAND_MERGING_INVESTIGATION.md` and `P2P_WELL_CONNECTED.md` documents.

---

## 1. The Problems Being Solved

The `TopologyOptimizer` addresses two fundamental challenges in maintaining a healthy and efficient mesh network.

### Problem A: Network Islands

A "network island" occurs when two or more separate clusters of nodes form stable, internally well-connected groups but remain unaware of each other, even when in physical proximity.

- **Root Cause:** A node would stop actively discovering new peers once its connection count reached `TARGET_CONNECTIONS`. Saturated clusters would not listen for new connections, preventing them from merging.

### Problem B: Network Inefficiency

The initial topology of the mesh is formed by random connections. This can lead to inefficient message routing.

- **Root Cause:** The network might form connections that are locally redundant (e.g., three nodes all connected to each other in a "triangle") while lacking efficient "long-range" links to distant nodes. This results in a high average "hop count" for messages to cross the network, increasing latency.

## 2. The Implemented Solutions

Both solutions are implemented as periodic, asynchronous tasks within `TopologyOptimizer.kt`, which resides in the platform-agnostic `mesh-logic` module.

### Solution A: Proactive Island Merging

To solve the network island problem, the optimizer intentionally introduces a small amount of churn to saturated clusters, creating opportunities for discovery.

- **Logic:** A timer runs a function (`analyzeAndPerformIslandDiscovery`) every `ISLAND_DISCOVERY_ANALYSIS_INTERVAL_MS` (5 minutes).
- **Trigger:** This function checks if the node is "saturated" (at `TARGET_CONNECTIONS`) and has a "redundant" local connection (part of a local triangle).
- **Action:** If both conditions are met, the optimizer drops the redundant peer. This frees up a connection slot and triggers a special, time-limited "discovery mode" in the `ConnectionManager`, allowing the node to listen for and connect to previously unknown nodes, merging the islands.

### Solution B: Small-World Rewiring

To improve efficiency, the optimizer continuously rewires the network to favor long-range connections over redundant local ones, creating a "small-world" topology.

- **Logic:** A separate timer runs a function (`analyzeAndPerformRewiring`) every `REWIRING_ANALYSIS_INTERVAL_MS` (1 minute).
- **Trigger:** This function identifies a redundant local connection (a "triangle").
- **Action:** It then identifies the most distant known node in the network (based on hop counts from gossiped messages). The optimizer **swaps** the redundant local connection for a new connection to the distant node. This dramatically reduces the average message path length across the network.

## 3. How to Test the Topology Optimizer

Testing this complex, time-dependent, emergent behavior is non-trivial. The following sections describe the available methods and their limitations. This guide is written with the goal of being converted into reliable, automated unit tests in the future.

### Section 3.1: Automated Testing (Unit Tests)

The primary automated tests for this logic reside in the `mesh-logic` module.

- **How to Run:** Execute `./gradlew -p mesh-logic test` from the project root.
- **Key Test File:** `mesh-logic/src/test/kotlin/info/benjaminhill/localmesh/logic/IslandMergingTest.kt`

#### **Critical Warning: Flaky Test**

The `test network islands can merge` test is **known to be flaky and is expected to fail frequently**.

- **Why it Fails:** This test simulates the complex interaction of multiple concurrent optimizers, simulated discovery, and asynchronous gossip. This environment is prone to subtle timing-based race conditions that are difficult to control in a test environment. The original investigation concluded that the production code in `TopologyOptimizer.kt` is robust and that failures are an artifact of the simulation, not the logic itself.
- **Recommendation:** Do not treat this test as a reliable pass/fail indicator. Instead, use it as a reference for how to construct a network simulation using `SimulatedConnectionManager`. The primary value of this test is in its structure, not its outcome.

### Section 3.2: Manual Verification

Manual verification on actual devices (or emulators) is the most reliable way to confirm the `TopologyOptimizer` is behaving correctly.

#### **Tool Clarification: The `visualization-tester` App**

The project includes a module named `visualization-tester`. It is critical to understand its purpose:

- **What it IS:** A lightweight Android app that acts as a simple host for the web-based visualizations located in `app/src/main/assets/web`. Its purpose is to allow for rapid UI development and testing of the **frontend JavaScript only**.
- **What it IS NOT:** It **does not** run the `BridgeService`, the `NearbyConnectionsManager`, or the `TopologyOptimizer`. It has no P2P networking capabilities. **It cannot be used to test or verify the island-merging or network-rewiring logic.**

#### **Procedure for Manual Testing**

To manually test the network logic, you must build and run the full **`app` module** on multiple devices.

**Scenario: Testing Island Merging**

1.  **Setup:** Install and run the `app` APK on at least 6 devices.
2.  **Create Islands:**
    - Physically separate the devices into two groups of 3 (Group A and Group B).
    - In each group, ensure the devices connect to each other, forming two distinct, stable network islands. You can observe this in the web visualization UI.
3.  **Initiate Merge:**
    - Place Group A and Group B in close physical proximity so they are within discovery range.
4.  **Observe:**
    - Monitor the device logs (`logcat`). Within 5 minutes (the `ISLAND_DISCOVERY_ANALYSIS_INTERVAL_MS`), you should see a log from a saturated node in one of the islands stating: `"Initiating island discovery: Dropping redundant peer '...' to search for new islands."`
    - Check the web visualization on the devices. The two separate network graphs should merge into a single, fully connected graph of all 6 nodes. This provides definitive visual proof that the island merging logic was successful.
