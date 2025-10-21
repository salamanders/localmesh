# LocalMesh: Technical Deep Dive

**This document is the central technical reference for LocalMesh developers.**
It provides a comprehensive overview of the project's architecture, data flows, and development
practices. For a user-focused summary, please see the main [README.md](README.md).
---

## Core Mandate: The "Prove-It" Workflow

To prevent incorrect assumptions and premature declarations of success, the following workflow is
mandatory for all tasks.

**Root Cause Analysis Mandate:** For any error or bug, the primary goal is to identify and fix the
root cause, not just the symptom. Before implementing a solution (especially one suggested by a tool
or error message), first state your hypothesis for the underlying cause and use discovery tools (
`read_file`, `search_file_content`, etc.) to gather evidence that proves or disproves it. Announce
the confirmed root cause before proposing the fix.

1. **State the Hypothesis:** Before attempting any fix, explicitly state the hypothesis for the root
   cause of the problem. (e.g., "The build is failing because of an incorrect context in the
   Composable function.")
2. **Execute a Single, Atomic Change:** Make the smallest possible change to address the hypothesis.
3. **Immediately Verify with Proof:** After every single action, run a command to get positive proof
   of the outcome.
    * After a code change (`write_file`, `replace`): Immediately run the build (
      `./gradlew assembleDebug`). Do not proceed otherwise.
    * After an `adb` command to change app state: Immediately run `adb logcat -d -t 1` or a more
      specific `logcat` filter to find affirmative proof that the action succeeded.
    * **Absence of an error is not proof of success.** Only a log message or status confirming the
      intended outcome is proof.
4. **Announce Only After Proof:** Never state a task is "done," "fixed," or "complete" until you
   have the output from the verification command.
    * **Incorrect:** "I have fixed the build."
    * **Correct:** "I have applied the change. I will now run the build command to verify."
5. **Trust User Observation Over Logs:** If your log data or assumptions conflict with a direct
   observation from the user, the user's observation is the ground truth. Halt immediately, state
   that your understanding was wrong, and re-evaluate from the user's observation.

---

## Table of Contents

1. [Core Concept: The P2P Web Bridge](#1-core-concept-the-p2p-web-bridge)
2. [System Architecture](#2-system-architecture)
3. [Communication Protocol: Unified Gossip](#3-communication-protocol-unified-gossip)
4. [Technology Stack](#4-technology-stack)
5. [Build & Run Instructions](#5-build--run-instructions)
6. [API Reference](#6-api-reference)
7. [Development Guidelines](#7-development-guidelines)
8. [End-to-End Testing](#8-fully-automated-end-to-end-testing)

---

## 1. Core Concept: The P2P Web Bridge

LocalMesh is a native Android application that functions as a **"P2P Web Bridge."** It enables a
standard mobile web browser to communicate with other nearby devices over a peer-to-peer mesh
network, without requiring an internet or cellular connection.
The core architectural principle is a **Pass-Through Web Server**. All actions, whether initiated
locally or by a peer, are treated as standard HTTP requests to a local Ktor web server. The P2P
network layer is simply a transport mechanism for forwarding these HTTP requests between devices,
making the Ktor routing block the single source of truth for the application's entire API.

## 2. System Architecture

The system is composed of four main components:

* **Web Frontend:** A user-facing single-page application running in a `WebView`. It
  interacts with the `LocalHttpServer` for all actions.

* **`mesh-logic` Module (Shared Kotlin Library):** A platform-agnostic pure Kotlin module that
  contains the core networking intelligence.
    * `ConnectionManager`: A crucial interface that defines a platform-agnostic API for managing
      peer-to-peer connections (e.g., `connectTo`, `disconnectFrom`, `sendPayload`) and exposes
      Kotlin Flows for events like `discoveredEndpoints` and `incomingPayloads`.
    * `TopologyOptimizer`: The "brains" of the network. It consumes a `ConnectionManager`
      implementation and contains all the logic for analyzing network health, and making high-level
      decisions to optimize the network topology by instructing the `ConnectionManager` to rewire connections.
    * `SimulatedConnectionManager`: An in-memory implementation of the `ConnectionManager`
      interface, used for running unit and integration tests of the `TopologyOptimizer` on a
      standard JVM without needing Android devices.

* **LocalMesh Android App (Middleware):** The native Android application that provides the
  Android-specific implementations and UI.
    * `BridgeService`: A foreground `Service` that orchestrates all the components. It
      initializes the `TopologyOptimizer` and provides it with the `NearbyConnectionsManager`. It is
      the heart of the gossip protocol.
    * `NearbyConnectionsManager`: The Android-specific implementation of the `ConnectionManager`
      interface. It acts as the "hands" of the network, wrapping the Google Play Services
      Nearby Connections API and translating the `TopologyOptimizer`'s commands into actual
      hardware operations (Wi-Fi/Bluetooth). It emits all incoming data to a `SharedFlow` for
      other components to consume.
    * `LocalHttpServer`: A Ktor-based HTTP server that serves the web UI and provides an API for
      the frontend to interact with the system.
    * `FileReassemblyManager`: A new manager class that handles incoming file chunks, reassembles
      them, and saves them to disk.
    * `MainActivity` & `DisplayActivity`: The Android activities for launching the service and
      hosting the `WebView`.
    * `ServiceHardener`: A watchdog that monitors the health of the application.

* **Peers:** Other Android devices on the same local network running the LocalMesh app.

## 3. Communication Protocol: Unified Gossip

All messages (chat, display commands) and file transfers must propagate to all nodes in the
connected network graph. The mechanism to achieve this is a standardized, loop-free, and robust
gossip protocol.

### 3.1. The Core Concept

The solution is to treat **all** data—commands and file data alike—as a standard `NetworkMessage`
that propagates through the mesh via a single, unified gossip protocol. This provides a robust and
standardized transport layer for any type of application data, rather than having separate logic for
different data types.

The `NetworkMessage` in `mesh-logic` is updated to optionally include file chunk data.

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

### 3.2. The Gossip & Loop-Prevention Mechanism: "Check, Process, Forward"

Every node in the network follows a single, simple rule for every `NetworkMessage` it receives
from a peer, implemented in `BridgeService.handleIncomingData()`:

1. **Check ID:** Look up the `messageId` in a local `seenMessageIds` cache.
2. **If Seen, IGNORE:** If the ID is in the cache, the message is a duplicate from another path or a
   cycle. The process stops here. This is the core mechanism that makes infinite loops impossible.
3. **If New, Process & Forward:**
    * **Cache:** Add the `messageId` to the `seenMessageIds` cache.
    * **Process:**
        * If the message contains an `httpRequest`, dispatch it to the local web server.
        * If it contains a `fileChunk`, pass it to the `FileReassemblyManager`.
    * **Forward:** Create a new `NetworkMessage` with an incremented `hopCount` and forward it to all
      connected peers, **except for the peer it was received from.**

This protocol guarantees that every message performs a controlled flood-fill of the network,
reaching every connected node exactly once.

### 3.3. Applying the Protocol

#### Chat & Display Commands

When a user action triggers a broadcast (e.g., sending a chat message), the `LocalHttpServer` calls `BridgeService.broadcast()`. This method wraps the `HttpRequestWrapper` in a `NetworkMessage` and injects it into the gossip protocol by sending it to all directly connected peers. The gossip mechanism then ensures it propagates to the entire network.

#### File Transfers (The New Flow)

1. **`sendFile` Rewrite:** The `sendFile` method in `BridgeService`:
    1. Takes a `File` and `destinationPath` as input.
    2. Generates a unique `fileId`.
    3. Reads the file and breaks it into small (e.g., 16KB) chunks.
    4. For each chunk, creates a `NetworkMessage` containing a `FileChunk` object.
    5. Feeds all of these `NetworkMessage`s into the gossip protocol by sending them to all direct
       peers.

2. **`FileReassemblyManager`:**
    * This new manager class handles incoming `fileChunk`s.
    * It uses a `Map` to store chunks, keyed by `fileId`.
    * When all chunks for a file have arrived, it reassembles them in order and saves the result
      using `AssetManager.saveFile()`.
    * It includes a timeout mechanism to discard incomplete files after a certain period,
      preventing memory leaks.

## 4. Technology Stack

* **Language:** Kotlin
* **UI:** Jetpack Compose
* **P2P Networking:** Google Play Services Nearby Connections API
* **Local Web Server:** Ktor (CIO engine)
* **Serialization:** Kotlinx Serialization (for JSON)
* **Build System:** Gradle

## 5. Build & Run Instructions

### Automated Setup

For a fully automated setup and build process on a Linux-based environment, use the provided script: `bash JULES.sh`. It will download and configure all required Android SDK components.

### Manual Steps

1. Build and install the app on two or more nearby Android devices.
2. Launch the app on each device and tap **"Start Service"**.
3. The app will request permissions and then automatically start discovering and connecting to
   peers.
4. The local HTTP server will be active at `http://localhost:8099`.

## 6. API Reference

The Ktor server in [
`LocalHttpServer.kt`](app/src/main/java/info/benjaminhill/localmesh/LocalHttpServer.kt) defines the
following routes:

* `GET /list?type=folders`: Lists the available content folders in the `assets/web` directory.
* `GET /status`: Retrieves the current service status as a JSON object, including the device's ID
  and a list of connected peers.
* `POST /chat`: Sends a chat message to all peers. Expects a URL-encoded body (e.g.,
  `message=hello`). This is a broadcast-only endpoint.
* `GET /display`: Triggers the `DisplayActivity` to open a specific path from the app's assets on
  remote peers. Expects a `path` query parameter. This is a broadcast-only endpoint.
* `POST /send-file`: Initiates a file transfer. This is a multipart endpoint called from the local
  web UI.
* `GET /{path...}`: General-purpose route to serve static files from the `assets/web` directory.

## 7. Development Guidelines

* **Prefer Fluent Configuration:** Use scope functions (`apply`, `also`) for object configuration to
  improve readability.
  ```kotlin
  // Prefer this fluent style
  Intent(this, MyActivity::class.java).apply {
      putExtra("key", "value")
      action = "MY_ACTION"
  }.also { startActivity(it) }
  ```
* **Use Explicit Data Fields:** When modeling data for transfer, prefer explicit fields over
  combined ones that require special parsing. This improves clarity and maintainability.
* **Search Before Refactoring:** Before refactoring a shared class like `HttpRequestWrapper`,
  perform a global search to identify all usages. This prevents build failures by ensuring all
  dependent files are updated simultaneously.

## 8. Fully Automated End-to-End Testing

### Test Workflow

1. **Build and Grant Permissions:** Build a fresh debug APK. Then, install it using the `-g` flag,
   which automatically grants all
   permissions declared in the manifest. This is the critical first step to enabling a UI-less
   startup.
2. **Automated App Launch:** Use `adb` to start the `MainActivity`, passing the special `auto_start`
   boolean extra. This flag
   is the testing hook that tells the activity to bypass the "Start Service" button and immediately
   trigger the service launch sequence. The app will briefly flash on screen and then proceed
   directly to the main web UI, with the
   `BridgeService` running in the background.
3. **Forward the Device Port:** Forward the device's port to your local machine to enable `curl`
   commands.
4. **Trigger an Action via API:** Use `curl` to send commands to the app's local server. To test a
   peer command, you must include a
   `sourceNodeId`.
5. **Monitor for Proof:** Check `logcat` for logs confirming the action was received and executed
   correctly. Note: make
   sure you aren't reading a previous run's logcat. Clear the logcat if necessary.
6. **Clean Up:**
   Remove the port forwarding rule when you are finished. If the issue is still being debugged, skip
   this step.

```bash
./gradlew assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n info.benjaminhill.localmesh/.MainActivity --ez auto_start true
adb forward tcp:8099 tcp:8099
# Example: Trigger the 'motion' display on the connected device
curl -X GET "http://localhost:8099/display?path=motion&sourceNodeId=test-node"
adb logcat -d DisplayActivity:I WebViewScreen:I *:S
# adb forward --remove-all
```

## 9. Automated Testing

* Objective: Achieve a fully automated, end-to-end test script that can be executed by the
  gemini-cli.
* Initial Failures: Early attempts to start the BridgeService directly from adb were
  unsuccessful due to Android security policies (service not exported, background start
  restrictions).
* Successful Refactoring: To solve this, a testing hook was added to MainActivity. It now
  checks for a boolean auto_start Intent extra, which allows it to bypass the UI and trigger the
  service start sequence automatically. This brings the app to the foreground correctly while
  maintaining automation. **STILL NEEDS TO BE VERIFIED**
* Process Improvement: A series of incorrect assumptions during the testing phase led to the
  creation of a "Core Mandate: The 'Prove-It' Workflow," which has been added to this document to
  enforce a stricter, evidence-based development cycle.
* Current Status: We have identified what we think is the correct adb command (
  `adb shell am start -ez auto_start true`) to trigger the testing hook. The next step is to execute
  this command and verify that it successfully launches the app and service without manual
  interaction, finally clearing the path for the full end-to-end test.

### Additional Notes for Gemini

* Don't be obsequious. The user's ideas aren't "wonderful" or "fantastic" or "brilliant". Don't use
  phrases like "You are absolutely right." or "My apologies" At most say (if it is true) "I can
  confirm that is a better plan."
* **Document any reverts**: If you ever do something then revert it, this is valuable information
  and should be written down in this file so future gemini-cli doesn't repeat the same mistake.
* **Bias towards leaving in Logging statements**: If you add logging to a function, and get the
  issue resolved, consider leaving the logging lines in, in case the issue comes back or isn't fully
  resolved.
* **Absence of errors is not proof of success.** The only proof of success is an affirmative logcat
  message showing the expected behavior occurred.
* **Utilize `adb` for control.** It is a reliable way to interact with the app.
* **The process ID changes on every run.** Assume it has changed and filter logs accordingly.
* **Utilize the User**: If you have to make 10+ search-and-replace, ask the user to do it rather
  than eat up Gemini tokens. The same applies for any "easy to do with an IDE" bulk change.

### Sticking Points

Previous attempts by an automated agent failed due to the following. Do not repeat the same
mistakes. All strategies must be checked to avoid the following pitfalls:

* **GUESSING WITHOUT EVIDENCE**: Gemini has a bad habit of guessing and coding. Don't do this.
  Instead, take smaller evidence-backed steps.
* Misunderstanding the core broadcast-vs-local execution logic.
* Incorrectly using file modification tools.
* Misinterpreting logs and incorrectly announcing success.
* Failing to follow a step-by-step verification process.
* **KOTLINX.SERIALIZATION BINARY FORMATS**: Be aware that `kotlinx.serialization.json.Json` is a
  `StringFormat`, not a `BinaryFormat`. Direct `encodeToByteArray` and `decodeFromByteArray`
  functions are not available on `Json` without an intermediate `String` conversion. Attempting to
  use them directly will result in compilation errors like "Cannot infer type for type parameter '
  T'" or "Too many arguments". If binary serialization is required, consider using a `BinaryFormat`
  like `ProtoBuf` or explicitly converting to/from `String` for `Json`.

<!--
* **Not done until proven**: A feature, bug fix, or refactor is never done until we have **positive
  proof** that it worked. This means extra compile/run/tests. That is **always** worth it. Never
  say "Final Plan" or "Fixed Code" until you have proof.
-->

## 10. Main Application Flows

### Main Display Flow & Chat Flow
These flows remain largely the same at a high level. The key difference is that when the `p2pBroadcastInterceptor` calls `service.broadcast()`, the message is now propagated through the entire network via the gossip protocol, not just to direct neighbors.

### File Transfer Flow
This flow is completely replaced by the new gossip mechanism.

1. **File Selection & Upload**: The user selects a file, and the web UI sends a `multipart/form-data` `POST` to `/send-file`.
2. **Chunking and Gossiping**: The `LocalHttpServer` saves the file and calls `BridgeService.sendFile()`. `BridgeService` then breaks the file into numerous `FileChunk` messages and injects each one into the gossip protocol.
3. **Peer Reception and Reassembly**: As each `FileChunk` message arrives at a peer, the `FileReassemblyManager` collects and stores it. Once all chunks for a file are received, the manager reassembles them and saves the final file to the local cache.

### Camera to Slideshow Flow
This flow is also updated to use the new file transfer mechanism. The race condition identified in the old flow still exists but is handled by the slideshow's periodic refresh.
