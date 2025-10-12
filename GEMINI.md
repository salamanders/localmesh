# Gemini LocalMesh Development Context

This document provides a comprehensive overview of the LocalMesh Android project to guide future development interactions.

## Project Overview

LocalMesh is a native Android application that functions as a "P2P Web Bridge." It enables a standard mobile web browser to communicate with other nearby devices over a peer-to-peer mesh network, without requiring an internet or cellular connection.

The core architectural principle is a **Pass-Through Web Server**. All actions, whether initiated locally or by a peer, are treated as standard HTTP requests to a local Ktor web server. The P2P network layer is simply a transport mechanism for forwarding these HTTP requests between devices, making the Ktor routing block the single source of truth for the application's entire API.

## Architecture

The system is composed of three main parts:

1.  **Web Page (Frontend):** A user-facing single-page application running in a `WebView` (or standard mobile browser). It interacts with the `LocalHttpServer` for all actions, including fetching status and triggering commands.
2.  **LocalMesh App (Middleware):** The native Android application that runs as a background service. It consists of:
    *   **`MainActivity`:** A simple activity with a single button to start the service and launch the `WebViewActivity`.
    *   **`WebViewActivity`:** An activity that hosts the full-screen `WebView` for the main UI.
    *   **`BridgeService`:** A foreground `Service` that orchestrates the networking components. Its primary role is to forward messages between the `NearbyConnectionsManager` and the `LocalHttpServer`.
    *   **`LocalHttpServer`:** A lightweight Ktor-based HTTP server running on `http://localhost:8099`. It contains all application logic in its routing block. It uses a custom interceptor to automatically broadcast any incoming local requests to all peers.
    *   **`NearbyConnectionsManager`:** The core of the P2P functionality. It uses the Google Play Services Nearby Connections API with the `P2P_CLUSTER` strategy to manage a many-to-many mesh network. It handles device discovery, connection management, and payload transfer.
3.  **Other Peers:** Other Android devices on the same local network running the LocalMesh app.

### Data Flow

1.  A local web page sends an HTTP request to `http://localhost:8099` (e.g., `POST /chat`).
2.  The `p2pBroadcastInterceptor` in `LocalHttpServer` sees the request has no `sourceNodeId` query parameter.
3.  The interceptor serializes the request into an `HttpRequestWrapper` object, adds its own `sourceNodeId`, and calls `service.broadcast()`.
4.  The `BridgeService` passes the serialized string to the `NearbyConnectionsManager`, which sends it to all peers as a `BYTES` payload.
5.  On a remote peer, `NearbyConnectionsManager` receives the `BYTES` payload and passes it to `BridgeService`.
6.  `BridgeService` deserializes the `HttpRequestWrapper` and calls `localHttpServer.dispatchRequest()`.
7.  `dispatchRequest` uses an `HttpClient` to make a synthetic request to its own local server (e.g., `http://localhost:8099/chat?sourceNodeId=...`), now including the `sourceNodeId`.
8.  The `p2pBroadcastInterceptor` sees the `sourceNodeId` and ignores the request, preventing a broadcast loop. The request is handled by the appropriate route (e.g., `post("/chat")`).

## Key Technologies

*   **Language:** Kotlin
*   **UI:** Jetpack Compose
*   **P2P Networking:** Google Play Services Nearby Connections API
*   **Local Web Server:** Ktor (CIO engine)
*   **Serialization:** Kotlinx Serialization (for JSON)
*   **Build System:** Gradle

## Building and Running

### Automated Build Environment Setup

For a fully automated setup and build process, use the provided `JULES.sh` script. This script is designed for a Linux-based environment and will download and configure all required Android SDK components and build the project.

```bash
bash JULES.sh
```

**Note for gemini-cli users:** If you are running in the gemini-cli environment, you should instead run the following command from the `android` directory:

```bash
./gradlew assembleDebug
```

NOTE: that the app is in the "android" subdirectory.

1. For ALL major changes, reload the target file if there is even one failed edit.
1. For ALL major changes, ensure that the android app still successfully compiles.  This takes priority over fixing Unit Tests. 
1. Read the @README.md file to ensure proper context.

### Running the App

1.  Build and install the app on two or more nearby Android devices.
2.  Launch the app on each device and tap "Start Service".
3.  The app will request permissions and then automatically start discovering and connecting to peers.
4.  The local HTTP server will be active at `http://localhost:8099`. A web page can now be loaded in a browser on the same device to interact with the service's API.

## Local API Endpoints

The Ktor server in `LocalHttpServer.kt` defines the following routes:

*   `GET /status`: Retrieves the current status of the service, including the device's ID and a list of connected peers.
*   `POST /chat`: Sends a chat message to all peers. Expects a URL-encoded body (e.g., `message=hello`).
*   `GET /display`: Triggers the `WebViewActivity` to open a specific path from the app's assets.
*   `POST /send-file`: Initiates a file transfer. This is a multipart endpoint expected to be called from the local web UI.
*   `POST /file-received`: A notification endpoint used internally to log when a file transfer is complete.
*   `GET /{path...}`: A general-purpose route to serve static files from the `assets/web` directory.

## Coding Guidelines

*   **Use Scope Functions for Fluent Configuration:** When creating an object and then immediately calling methods on it (e.g., setting properties on an `Intent`), prefer using scope functions like `apply` and `also`. This groups the configuration with the object's creation, making the code more readable and concise.

    *   **Avoid (Imperative Style):**
        ```kotlin
        val intent = Intent(this, MyActivity::class.java)
        intent.putExtra("key", "value")
        intent.action = "MY_ACTION"
        startActivity(intent)
        ```

    *   **Prefer (Fluent Style):**
        ```kotlin
        Intent(this, MyActivity::class.java).apply {
            putExtra("key", "value")
            action = "MY_ACTION"
        }.also { startActivity(it) }
        ```

*   When modeling data for transfer, prefer explicit fields over combined ones using special separators. This improves clarity and maintainability by reducing the need for custom parsing logic.
*   Before refactoring a shared class or data structure (e.g., `HttpRequestWrapper`), perform a global search for its name to identify all usages across the project. This ensures all dependent files (`LocalHttpServer`, `BridgeService`, tests, etc.) are updated simultaneously, preventing build failures.

## Integration Testing from the Command Line

It is possible to test the full end-to-end flow of the application—from receiving a peer request to displaying a custom WebView—directly from the command line using a combination of `adb` and `curl`. This is useful for verifying the behavior of the `LocalHttpServer` and `DisplayActivity` without needing to manually interact with the UI.

The key is to simulate a request coming from another peer by including the `sourceNodeId` query parameter.

### Test Workflow

1.  **Build and Install the App:**
    Ensure the latest version is built and installed on the target device or emulator.
    ```bash
    # From the android/ directory
    ./gradlew assembleDebug
    adb install -r app/build/outputs/apk/debug/app-debug.apk
    ```

2.  **Start the BridgeService:**
    The `LocalHttpServer` is started by the `BridgeService`. Manually start the service from the shell. This is the equivalent of the user tapping "Start Service" in the UI.
    ```bash
    adb shell am start-service info.benjaminhill.localmesh/.mesh.BridgeService
    ```

3.  **Forward the Device Port:**
    To allow your host machine to send requests to the app's server, forward the device's port `8099` to your local machine.
    ```bash
    adb forward tcp:8099 tcp:8099
    ```

4.  **Trigger a Display Change:**
    Use `curl` to send a `GET` request to the `/display` endpoint. **Crucially**, you must include a `sourceNodeId` to simulate the request coming from a peer. This bypasses the broadcast-only logic and ensures the request is handled locally.
    ```bash
    # Triggers the 'motion' display
    curl -X GET "http://localhost:8099/display?path=motion&sourceNodeId=test-node"
    ```

5.  **Monitor for Affirmative Proof:**
    Use `logcat` to check for the affirmative logging that was added to confirm the flow is working as expected.
    ```bash
    # Look for logs from DisplayActivity and WebViewScreen
    adb logcat -d DisplayActivity:I WebViewScreen:I *:S
    ```
    A successful run will show logs indicating that the `DisplayActivity` received the intent and the `WebViewScreen` loaded the correct URL.

6.  **Clean Up:**
    Remove the port forwarding rule when you are finished.
    ```bash
    adb forward --remove-all
    ```