# LocalMesh: Technical Deep Dive

> **This document is the central technical reference for LocalMesh developers.** It provides a comprehensive overview of the project's architecture, data flows, and development practices. For a user-focused summary, please see the main [README.md](README.md).

---

## Table of Contents

1.  [Core Concept: The P2P Web Bridge](#1-core-concept-the-p2p-web-bridge)
2.  [System Architecture](#2-system-architecture)
3.  [Data & Message Flows](#3-data--message-flows)
4.  [Technology Stack](#4-technology-stack)
5.  [Build & Run Instructions](#5-build--run-instructions)
6.  [API Reference](#6-api-reference)
7.  [Development Guidelines](#7-development-guidelines)
8.  [End-to-End Testing](#8-end-to-end-testing)
9.  [Development Journal](#9-development-journal)

---

## 1. Core Concept: The P2P Web Bridge

LocalMesh is a native Android application that functions as a **"P2P Web Bridge."** It enables a standard mobile web browser to communicate with other nearby devices over a peer-to-peer mesh network, without requiring an internet or cellular connection.

The core architectural principle is a **Pass-Through Web Server**. All actions, whether initiated locally or by a peer, are treated as standard HTTP requests to a local Ktor web server. The P2P network layer is simply a transport mechanism for forwarding these HTTP requests between devices, making the Ktor routing block the single source of truth for the application's entire API.

## 2. System Architecture

The system is composed of three main components:

*   **Web Frontend:** A user-facing single-page application running in a `WebView` (or standard mobile browser). It interacts with the `LocalHttpServer` for all actions.

*   **LocalMesh Android App (Middleware):** The native Android application that runs as a background service. It consists of:
    *   `MainActivity`: A simple entry point with a single button to start the service and launch the `DisplayActivity`.
    *   `DisplayActivity`: An Activity that hosts the full-screen `WebView` for the main UI.
    *   `BridgeService`: A foreground `Service` that orchestrates the networking components. Its primary role is to forward messages between the `NearbyConnectionsManager` and the `LocalHttpServer`.
    *   `LocalHttpServer`: A lightweight Ktor-based HTTP server on `http://localhost:8099`. It contains all application logic in its routing block and uses a custom interceptor to automatically broadcast relevant local requests to all peers.
    *   `NearbyConnectionsManager`: The core of the P2P functionality. It uses the Google Play Services Nearby Connections API (`P2P_CLUSTER` strategy) to manage the mesh network, handling discovery, connections, and payload transfer.

*   **Peers:** Other Android devices on the same local network running the LocalMesh app.

## 3. Data & Message Flows

### 3.1. General Data Flow (Request Broadcasting)

This is the standard flow for a request originating from the local device that needs to be executed on all peers.

1.  A local web page sends an HTTP request to `http://localhost:8099` (e.g., `POST /chat`).
2.  The `p2pBroadcastInterceptor` in `LocalHttpServer` intercepts the request and sees it has no `sourceNodeId` query parameter, marking it as a local-origin request.
3.  The interceptor serializes the request into an `HttpRequestWrapper` object, adds its own `sourceNodeId`, and calls `service.broadcast()`.
4.  The `BridgeService` passes the serialized string to the `NearbyConnectionsManager`, which sends it to all connected peers as a `BYTES` payload.
5.  On a remote peer, `NearbyConnectionsManager` receives the `BYTES` payload and passes it to its `BridgeService`.
6.  The peer's `BridgeService` deserializes the `HttpRequestWrapper` and calls `localHttpServer.dispatchRequest()`.
7.  `dispatchRequest` uses an `HttpClient` to make a synthetic request to its *own* local server (e.g., `http://localhost:8099/chat?sourceNodeId=...`), now including the `sourceNodeId`.
8.  The `p2pBroadcastInterceptor` on the peer sees the `sourceNodeId` and ignores the request, preventing a broadcast loop. The request is then handled by the appropriate Ktor route.

### 3.2. Detailed Flow: Remote Display

This flow outlines how tapping a UI element on one device triggers a `WebView` to open on a peer device.

1.  **UI Interaction:** User taps a folder (e.g., "eye") in the web UI.
2.  **Local Request:** The UI sends a `GET /display?path=eye` request to the local server.
3.  **Intercept & Broadcast:** The `p2pBroadcastInterceptor` identifies `/display` as a broadcast-only path, creates an `HttpRequestWrapper`, and broadcasts it to all peers.
4.  **Stop Local Execution:** The interceptor immediately stops the request pipeline on the originating device. This is key: the display command should only execute on remote peers.
5.  **Peer Reception:** A peer device receives the `HttpRequestWrapper`.
6.  **Dispatch to Local Server:** The peer's `BridgeService` dispatches the wrapper to its own `LocalHttpServer` as a synthetic request.
7.  **Execute on Peer:** The peer's server processes the `GET /display` request. The route handler executes, starting a `DisplayActivity` on the peer device.

### 3.3. Detailed Flow: File Transfer

1.  **UI Interaction:** User selects a file in the web UI.
2.  **Local Upload:** The UI sends a `multipart/form-data` POST to `http://localhost:8099/send-file`.
3.  **Save & Prepare:** The local server saves the file to a temp location and calls `BridgeService.sendFile()`.
4.  **Broadcast Command & Stream:** The `BridgeService` does two things:
    *   Broadcasts an `HttpRequestWrapper` for `POST /send-file` containing the `filename` and a unique `payloadId`.
    *   Sends the file content itself as a `Payload.Stream` to all peers.
5.  **Peer Receives Command:** The peer's `BridgeService` receives the `HttpRequestWrapper` first. It parses the `filename` and `payloadId` and stores them in a map (`incomingFilePayloads`) to prepare for the stream.
6.  **Peer Receives Stream:** The `handleStreamPayload` method is triggered. It uses the `payload.id` to look up the `filename` from the map and saves the incoming stream to its local cache.

## 4. Technology Stack

*   **Language:** Kotlin
*   **UI:** Jetpack Compose
*   **P2P Networking:** Google Play Services Nearby Connections API
*   **Local Web Server:** Ktor (CIO engine)
*   **Serialization:** Kotlinx Serialization (for JSON)
*   **Build System:** Gradle

## 5. Build & Run Instructions

### Automated Setup

For a fully automated setup and build process on a Linux-based environment, use the provided script. It will download and configure all required Android SDK components.

```bash
bash JULES.sh
```

> **Note for gemini-cli users:** If you are running in the gemini-cli environment, you should instead run `./gradlew assembleDebug`.

### Manual Steps

1.  Build and install the app on two or more nearby Android devices.
2.  Launch the app on each device and tap **"Start Service"**.
3.  The app will request permissions and then automatically start discovering and connecting to peers.
4.  The local HTTP server will be active at `http://localhost:8099`.

## 6. API Reference

The Ktor server in `LocalHttpServer.kt` defines the following routes:

*   `GET /folders`: Lists the available content folders in the `assets/web` directory.
*   `GET /status`: Retrieves the current service status, including the device's ID and a list of connected peers.
*   `POST /chat`: Sends a chat message to all peers. Expects a URL-encoded body (e.g., `message=hello`).
*   `GET /display`: Triggers the `DisplayActivity` to open a specific path from the app's assets on remote peers.
*   `POST /send-file`: Initiates a file transfer. This is a multipart endpoint called from the local web UI.
*   `POST /file-received`: Internal notification endpoint to log when a file transfer is complete.
*   `GET /{path...}`: General-purpose route to serve static files from the `assets/web` directory.

## 7. Development Guidelines

*   **Prefer Fluent Configuration:** Use scope functions (`apply`, `also`) for object configuration to improve readability.

    ```kotlin
    // Prefer this fluent style
    Intent(this, MyActivity::class.java).apply {
        putExtra("key", "value")
        action = "MY_ACTION"
    }.also { startActivity(it) }
    ```

*   **Use Explicit Data Fields:** When modeling data for transfer, prefer explicit fields over combined ones that require special parsing. This improves clarity and maintainability.

*   **Search Before Refactoring:** Before refactoring a shared class like `HttpRequestWrapper`, perform a global search to identify all usages. This prevents build failures by ensuring all dependent files are updated simultaneously.

## 8. End-to-End Testing

It is possible to test the full application flow from the command line using `adb` and `curl`. This is useful for verifying server behavior without UI interaction.

The key is to simulate a request from a peer by including the `sourceNodeId` query parameter.

### Test Workflow

1.  **Build and Install:**
    ```bash
    ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    ```
2.  **Start the Service:**
    ```bash
    adb shell am start-service info.benjaminhill.localmesh/.mesh.BridgeService
    ```
3.  **Forward the Port:**
    ```bash
    adb forward tcp:8099 tcp:8099
    ```
4.  **Trigger an Action:** Send a request with a `sourceNodeId` to ensure it's handled locally and not broadcast.
    ```bash
    # Example: Trigger the 'motion' display on the connected device
    curl -X GET "http://localhost:8099/display?path=motion&sourceNodeId=test-node"
    ```
5.  **Monitor for Proof:** Check logcat for logs confirming the action was received and executed.
    ```bash
    adb logcat -d DisplayActivity:I WebViewScreen:I *:S
    ```
6.  **Clean Up:**
    ```bash
    adb forward --remove-all
    ```

---

## 9. Development Journal

> This section serves as a log of the current development state, goals, and challenges. It should be updated as work progresses.

### 9.1. Goal

To prove that a `WebView` in the app can use permission-gated APIs (like motion sensors) without a user click, by having the native code grant permissions and trigger the necessary JavaScript.

### 9.2. Strategy

The strategy is to add a text input box to the main `index.html` page. When text is entered, the JavaScript will use it as a `sourceNodeId` query parameter on its requests. This will simulate a request from a peer, causing the app's server to process the request locally instead of broadcasting it. This allows for end-to-end testing of the local display logic directly through the UI.

### 9.3. Sticking Points

Previous attempts by an automated agent failed due to:

*   Misunderstanding the core broadcast-vs-local execution logic.
*   Incorrectly using file modification tools.
*   Misinterpreting logs and incorrectly announcing success.
*   Failing to follow a step-by-step verification process.

### 9.4. Core Principles for Verification

*   **Absence of errors is not proof of success.** The only proof of success is an affirmative logcat message showing the expected behavior occurred.
*   **Utilize `adb` for control.** It is a reliable way to interact with the app.
*   **The process ID changes on every run.** Assume it has changed and filter logs accordingly.
