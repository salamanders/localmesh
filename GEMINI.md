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
3. [Data & Message Flows](#3-data--message-flows)
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
      implementation and contains all the logic for analyzing network health, gossiping with
      peers, and making high-level decisions to optimize the network topology by instructing the
      `ConnectionManager` to rewire connections.
    * `SimulatedConnectionManager`: An in-memory implementation of the `ConnectionManager`
      interface, used for running unit and integration tests of the `TopologyOptimizer` on a
      standard JVM without needing Android devices.

* **LocalMesh Android App (Middleware):** The native Android application that provides the
  Android-specific implementations and UI.
    * `BridgeService`: A foreground `Service` that orchestrates all the components. It
      initializes the `TopologyOptimizer` and provides it with the `NearbyConnectionsManager`.
    * `NearbyConnectionsManager`: The Android-specific implementation of the `ConnectionManager`
      interface. It acts as the "hands" of the network, wrapping the Google Play Services
      Nearby Connections API and translating the `TopologyOptimizer`'s commands into actual
      hardware operations (Wi-Fi/Bluetooth). It emits all incoming data to a `SharedFlow` for
      other components to consume.
    * `LocalHttpServer`: A Ktor-based HTTP server that serves the web UI and provides an API for
      the frontend to interact with the system.
    * `MainActivity` & `DisplayActivity`: The Android activities for launching the service and
      hosting the `WebView`.
    * `ServiceHardener`: A watchdog that monitors the health of the application.

* **Peers:** Other Android devices on the same local network running the LocalMesh app.

## 3. Data & Message Flows

### 3.1. General Data Flow (Request Broadcasting)

This is the standard flow for a request originating from the local device that needs to be executed
on all peers.

1. A local web page sends an HTTP request to `http://localhost:8099` (e.g., `POST /chat`).
2. The `p2pBroadcastInterceptor` in `LocalHttpServer` intercepts the request and sees it has no
   `sourceNodeId` query parameter, marking it as a local-origin request.
3. The interceptor serializes the request into an `HttpRequestWrapper` object.
4. The `BridgeService.broadcast()` method wraps the `HttpRequestWrapper` in a standard
   `NetworkMessage` (which includes a unique ID and hop count) and serializes it.
5. The `BridgeService` calls `nearbyConnectionsManager.sendPayload()` to send the serialized
   `NetworkMessage` to all connected peers.
6. On a remote peer, `NearbyConnectionsManager` receives the `BYTES` payload and emits it onto the
   `incomingPayloads` `SharedFlow`.
7. The remote peer's `BridgeService` collects this flow, deserializes the `NetworkMessage` and
   the `HttpRequestWrapper` inside it, and calls `localHttpServer.dispatchRequest()`.
8. `dispatchRequest` uses an `HttpClient` to make a synthetic request to its *own* local server (
   e.g., `http://localhost:8099/chat?sourceNodeId=...`), now including the `sourceNodeId`.
9. The `p2pBroadcastInterceptor` on the peer sees the `sourceNodeId` and ignores the request,
   preventing a broadcast loop. The request is then handled by the appropriate Ktor route.

### 3.2. Detailed Flow: Remote Display

This flow outlines how tapping a UI element on one device triggers a `WebView` to open on a peer
device.

1. **UI Interaction:** User taps a folder (e.g., "eye") in the web UI.
2. **Local Request:** The UI sends a `GET /display?path=eye` request to the local server.
3. **Intercept & Broadcast:** The `p2pBroadcastInterceptor` identifies `/display` as a
   broadcast-only path, creates an `HttpRequestWrapper`, and calls `service.broadcast()`.
4. **Stop Local Execution:** The interceptor immediately stops the request pipeline on the
   originating device. This is key: the display command should only execute on remote peers.
5. **Peer Reception:** A peer device receives the `NetworkMessage` via its
   `NearbyConnectionsManager` and the `BridgeService` collects it from the flow.
6. **Dispatch to Local Server:** The peer's `BridgeService` dispatches the wrapper to its own
   `LocalHttpServer` as a synthetic request.
7. **Execute on Peer:** The peer's server processes the `GET /display` request. The route handler
   executes, starting a `DisplayActivity` on the peer device.

### 3.3. Detailed Flow: File Transfer

1. **UI Interaction:** User selects a file in the web UI.
2. **Local Upload:** The UI sends a `multipart/form-data` POST to `http://localhost:8099/send-file`.
3. **Save & Prepare:** The local server saves the file to a temp location and calls
   `BridgeService.sendFile()`.
4. **Broadcast Command & Stream:** The `BridgeService` does two things:
    * Broadcasts a `NetworkMessage` containing an `HttpRequestWrapper` for `POST /send-file`.
      This wrapper contains the `filename` and a unique `payloadId`.
    * Sends the file content itself as a `Payload.Stream` to all peers.
5. **Peer Receives Command:** The peer's `BridgeService` collects the `NetworkMessage` from the
   `incomingPayloads` flow. It parses the `filename` and `payloadId` and stores them in a map
   (`incomingFilePayloads`) to prepare for the stream.
6. **Peer Receives Stream:** The `handleStreamPayload` method is triggered directly by the
   `NearbyConnectionsManager`'s callback. It uses the `payload.id` to look up the `filename` from
   the map and saves the incoming stream to its local cache.

### 3.4. Detailed Flow: Topology Optimization

This flow describes how the network self-optimizes its connections, driven by the `mesh-logic`
module.

1. **Payload Reception:** The Android `NearbyConnectionsManager` receives an incoming `BYTES`
   payload from a peer. It does not parse it. It simply emits the raw `ByteArray` onto the
   `incomingPayloads: SharedFlow<Pair<String, ByteArray>>`.
2. **Optimizer Collection:** The `TopologyOptimizer` (in the `mesh-logic` module) is actively
   collecting this `incomingPayloads` flow.
3. **Message Parsing:** For each payload, the `TopologyOptimizer` deserializes it into a
   `NetworkMessage`. It checks the message `type` to see if it's a data message or a
   peer-list gossip message.
4. **State Update:** Based on the message content, the optimizer updates its internal state:
    * For data messages, it updates the `nodeHopCounts` map with the source node's ID and hop
      count.
    * For gossip messages, it updates the `neighborPeerLists` map with the peer's list of its
      own neighbors.
5. **Periodic Analysis:** Independently, a timer in `TopologyOptimizer` periodically runs the
   `analyzeAndPerformRewiring()` method (e.g., every 60 seconds).
6. **Rewiring Decision:** The analysis method inspects the `nodeHopCounts` and
   `neighborPeerLists` to find opportunities for optimization (e.g., a redundant local "triangle"
   connection and a known distant node).
7. **Rewiring Action:** If an optimization is found, `TopologyOptimizer` calls methods on the
   `ConnectionManager` interface it holds (e.g., `connectionManager.disconnectFrom(redundantPeer)`
   and `connectionManager.connectTo(distantNode)`).
8. **Execution:** The `NearbyConnectionsManager`, being the concrete implementation of the
   `ConnectionManager`, receives these calls and executes them using the real Nearby Connections
   API, thus changing the physical network topology.

## 4. Technology Stack

* **Language:** Kotlin
* **UI:** Jetpack Compose
* **P2P Networking:** Google Play Services Nearby Connections API
* **Local Web Server:** Ktor (CIO engine)
* **Serialization:** Kotlinx Serialization (for JSON)
* **Build System:** Gradle

## 5. Build & Run Instructions

### Automated Setup

For a fully automated setup and build process on a Linux-based environment (e.g. within
jules.google.com), use the provided script: `bash JULES.sh`. It will download and configure all
required Android SDK components.

### Manual Steps

1. Build and install the app on two or more nearby Android devices.
2. Launch the app on each device and tap **"Start Service"**.
3. The app will request permissions and then automatically start discovering and connecting to
   peers.
4. The local HTTP server will be active at `http://localhost:8099`.

## 6. API Reference

The Ktor server in `LocalHttpServer.kt` defines the following routes:

* `GET /folders`: Lists the available content folders in the `assets/web` directory.
* `GET /status`: Retrieves the current service status, including the device's ID and a list of
  connected peers.
* `POST /chat`: Sends a chat message to all peers. Expects a URL-encoded body (e.g.,
  `message=hello`).
* `GET /display`: Triggers the `DisplayActivity` to open a specific path from the app's assets on
  remote peers.
* `POST /send-file`: Initiates a file transfer. This is a multipart endpoint called from the local
  web UI.
* `POST /file-received`: Internal notification endpoint to log when a file transfer is complete.
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

These are the primary user-facing flows in the application.

### Main Display Flow

1. **App Start**: The user opens the app and is presented with the `MainActivity`.
2. **Service Start**: The user clicks "Start Service", which starts the `BridgeService` and launches
   the `DisplayActivity`.
3. **WebView Load**: The `DisplayActivity` loads the main `index.html` page in a `WebView`.
4. **Folder List Request**: The `main.js` in the `WebView` makes a `GET` request to `/folders`.
5. **Folder List Response**: The `LocalHttpServer` receives the request and gets the list of folders
   from the `AssetManager`.
6. **Folder Display**: The `main.js` receives the list of folders and displays them on the page.
7. **Folder Click**: The user clicks on a folder (e.g., "eye").
8. **Display Request**: The `main.js` sends a `GET` request to `/display?path=eye`.
9. **Broadcast**: The `p2pBroadcastInterceptor` intercepts the request and broadcasts it to all
   peers.
10. **Peer Reception**: A peer receives the `HttpRequestWrapper` and its `BridgeService` dispatches
    it to its `LocalHttpServer`.
11. **Display Activity Launch**: The peer's `LocalHttpServer` receives the request and starts a
    `DisplayActivity` with the "eye" path.
12. **"eye" Display**: The `DisplayActivity` on the peer's device loads the `index.html` from the "
    eye" folder.

### Chat Flow

1. **User Input**: The user types a message in the chat input box on the `index.html` page and
   clicks "Send".
2. **Chat Request**: The `main.js` sends a `POST` request to `/chat` with the message in the body.
3. **Broadcast**: The `p2pBroadcastInterceptor` intercepts the request and broadcasts it to all
   peers.
4. **Peer Reception**: A peer receives the `HttpRequestWrapper` and its `BridgeService` dispatches
   it to its `LocalHttpServer`.
5. **Chat Message Handling**: The peer's `LocalHttpServer` receives the request and logs the chat
   message.
6. **UI Update**: The `main.js` on all devices (including the sender) will eventually be updated to
   display the new chat message (this part is not yet implemented).

### File Transfer Flow

1. **File Selection**: The user selects a file (e.g., `my_cool_visualization.html`) to send using
   the file input on the `index.html` page.
2. **File Upload**: The `main.js` sends a `multipart/form-data` `POST` request to `/send-file` with
   the file data, specifying a destination path (e.g., `/web/bats/index.html`).
3. **File Save and Broadcast**: The `LocalHttpServer` saves the file to a temporary location (e.g.,
   `app/src/main/assets/web/bats/index.html`), and then calls `BridgeService.sendFile()`.
4. **Broadcast Command and Stream**: The `BridgeService` broadcasts an `HttpRequestWrapper` for
   `POST /send-file` with the filename (`/web/bats/index.html`) and a unique `payloadId`, and also
   sends the file content as a `Payload.Stream`.
5. **Peer Receives Command**: The peer's `BridgeService` receives the `HttpRequestWrapper` and
   stores the filename and `payloadId`.
6. **Peer Receives Stream**: The peer's `BridgeService` receives the `Payload.Stream`, looks up the
   filename using the `payloadId`, and saves the file to its local cache at the specified path (
   e.g., `app/src/main/assets/web/bats/index.html`). This effectively adds a new visualization or
   content to the peer's available assets.
7. **UI Update**: The `main.js` on all devices will eventually be updated to show the newly
   transferred file (this part is not yet implemented).

### Status Flow

1. **Status Request**: The `main.js` in the `WebView` makes a `GET` request to `/status`.
2. **Status Response**: The `LocalHttpServer` receives the request and responds with a JSON object
   containing the service status, device ID, and a list of connected peers.
3. **Status Display**: The `main.js` receives the status and updates the UI to display the number of
   connected peers.
