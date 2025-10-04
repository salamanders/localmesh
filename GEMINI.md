# Gemini LocalMesh Development Context

This document provides a comprehensive overview of the LocalMesh Android project to guide future development interactions.

## Project Overview

LocalMesh is a native Android application that functions as a "P2P Web Bridge." It enables a standard mobile web browser to communicate with other nearby devices over a peer-to-peer mesh network, without requiring an internet or cellular connection.

The core functionality is to bridge a local web application (running in the user's browser) with the Android Nearby Connections API, allowing for offline, local-first communication between devices running the app.

### Architecture

The system is composed of three main parts:

1.  **Web Page (Frontend):** A user-facing interface running in a mobile web browser. It interacts with the LocalHttpServer for sending and receiving messages.
2.  **P2P Web Bridge App (Middleware):** The native Android application that runs as a background service. It consists of:
    *   **`P2PBridgeService`:** A foreground `Service` that orchestrates the networking components, ensuring the app remains active. It manages the service lifecycle and communication between the other components.
    *   **`LocalHttpServer`:** A lightweight Ktor-based HTTP server running on `http://localhost:8099`. It exposes a simple REST API for the web frontend to interact with.
    *   **`NearbyConnectionsManager`:** The core of the P2P functionality. It uses the Google Play Services Nearby Connections API with the `P2P_CLUSTER` strategy to manage a many-to-many mesh network. It handles device discovery, connection management, and payload transfer automatically.
3.  **Other Peers:** Other Android devices on the same local network running the LocalMesh app.

### Key Technologies

*   **Language:** Kotlin
*   **UI:** Jetpack Compose
*   **P2P Networking:** Google Play Services Nearby Connections API
*   **Local Web Server:** Ktor (CIO engine)
*   **Serialization:** Kotlinx Serialization (for JSON)
*   **Build System:** Gradle

## Building and Running

### Building the App

The project is a standard Android application built with Gradle.

*   **To build a debug APK from the command line:**
    ```bash
    ./gradlew assembleDebug
    ```
*   **To install the debug APK on a connected device:**
    ```bash
    ./gradlew installDebug
    ```

### Running the App

1.  Build and install the app on two or more nearby Android devices.
2.  Launch the app on each device.
3.  Tap the "Start Service" button. This will request necessary permissions (Bluetooth, Location, etc.) and then start the `P2PBridgeService` in the foreground.
4.  Once the service is running, the app will begin advertising and discovering peers. Connections are accepted automatically.
5.  The UI will display the status, including the number of connected peers.
6.  The local HTTP server will be active at `http://localhost:8099`. A web page can now be loaded in a browser on the same device to interact with the service's API.

### Local API Endpoints

*   `GET /status`: Retrieves the current status of the service, including the device's ID and a list of connected peers.
*   `POST /send`: Sends a message to all connected peers. The request body should be a JSON object: `{"message": "your message"}`.
*   `GET /messages`: Retrieves a list of messages received from peers since the last poll.

## Development Conventions

The project follows modern Android and Kotlin development practices.

*   **Asynchronous Operations:** Coroutines are used for managing background tasks, especially for networking operations in `NearbyConnectionsManager`. All networking calls are explicitly moved off the main thread using `Dispatchers.IO`.
*   **State Management:** The application uses a sealed class `ServiceState` (`Idle`, `Starting`, `Running`, `Stopping`, `Error`) to manage the lifecycle of the `P2PBridgeService` robustly.
*   **UI/Service Communication:** Communication from the service to the UI is handled via `BroadcastReceiver` and a sealed `P2PBridgeEvent` class, ensuring a decoupled and type-safe flow of information (status updates, log messages). Actions from the UI to the service are sent via Intents with a sealed `P2PBridgeAction` class.
*   **Error Handling:** The code includes `try-catch` blocks for network operations and API calls to prevent crashes and provide useful logging. The `NearbyConnectionsManager` implements a connection retry mechanism with exponential backoff.
*   **Logging:** A `LogFileWriter` provides persistent, on-device logging to a file, which is crucial for debugging real-world P2P interactions. Log messages are also broadcast to the UI.
*   **Code Style:** The codebase favors modern Kotlin idioms, such as callable references (`::`) and a fluent API style where it enhances readability.
*   **Immutability:** Data classes are used for modeling data (e.g., `Message`, API responses), promoting immutability.
