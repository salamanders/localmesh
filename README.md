# LocalMesh - P2P Web Bridge

LocalMesh is a native Android application that acts as a bridge between a standard mobile web browser and the Android Nearby Connections API. It allows a web page, running on the same device, to communicate with other nearby devices over a peer-to-peer mesh network without needing an internet or cellular connection.

## Project Overview

The core use case is to enable local, offline, multi-device web applications. A user runs the LocalMesh app, which starts a background service. They can then open a web application in their mobile browser (e.g., Chrome). JavaScript on that web page can send and receive messages with other nearby phones by making simple HTTP requests to the local server provided by the LocalMesh app.

## How it Works: The P2P Pass-Through Web Server

The architecture is designed to be simple and extensible. It treats the P2P network as a transparent transport layer for HTTP requests.

1.  **Local Server:** The app runs a lightweight Ktor server on `http://localhost:8099`.
2.  **HTTP as the API:** All actions, whether initiated locally from the web UI or remotely from a peer, are standard HTTP requests. The Ktor routing block in `LocalHttpServer.kt` is the single source of truth for the application's API.
3.  **Automatic Broadcasting:** A Ktor interceptor automatically examines every incoming local HTTP request. It serializes the request into an `HttpRequestWrapper` and broadcasts it to all peers on the mesh network.
4.  **Remote Execution:** When a peer receives a broadcasted `HttpRequestWrapper`, it synthesizes an identical HTTP request and dispatches it to its own local Ktor server. A `sourceNodeId` parameter prevents the request from being endlessly re-broadcast.

This design means any new feature added as a standard Ktor HTTP endpoint is automatically available to be triggered by peers with no additional P2P-specific code.

## How to Build

For a fully automated setup and build process, use the provided `JULES.sh` script. This script is designed for a Linux-based environment and will download the required Android command-line tools, set up the SDK, and build the project.

```bash
bash JULES.sh
```

Alternatively, you can open the `android` directory in Android Studio and build the project normally.

## How to Run

1.  Build and install the app on two or more nearby Android devices.
2.  Launch the app on each device.
3.  Tap the "Start Service" button. This will request the necessary permissions and start the background service.
4.  The app will then automatically launch the main web-based user interface.
5.  Once the service is running, the app will automatically discover and connect to peers. The web UI will show the status and available actions.
