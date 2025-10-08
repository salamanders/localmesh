# Message Flow Documentation

This document outlines the message flow from the user selecting a folder in the UI on one device to the content being displayed in a `WebView` on a peer device.

## Abstract Flow

1.  **UI Interaction:** The user selects a folder from a dropdown menu in the app's main UI.
2.  **UI to Service:** The UI sends a `BroadcastCommand` action to the `BridgeService`.
3.  **Service to P2P Command:** The `BridgeService` converts this action into an `HttpRequestWrapper` object.
4.  **Broadcast:** The service broadcasts this wrapper to all connected peers via `NearbyConnectionsManager`.
5.  **Peer Reception:** A peer device receives the `HttpRequestWrapper`.
6.  **P2P Command to HTTP Request:** The peer's `BridgeService` dispatches the wrapper to its local `LocalHttpServer`.
7.  **HTTP Request Processing:** The peer's `LocalHttpServer` processes the synthetic HTTP request.
8.  **Display:** The corresponding folder's content is opened in a full-screen `WebView`.

## Detailed Implementation Flow

Here is the detailed flow with specific classes, methods, and message types:

1.  **`MainActivity` and `MainScreen` (UI Layer)**
    *   **Event Trigger:** The user selects a folder (e.g., "eye") from the `FolderSelector` composable within `MainScreen.kt`.
    *   **Action:** The `onFolderSelected` callback is invoked, which calls the `onAction` function passed to `MainScreen`.
    *   **Message Creation:** `onAction` (which is `startP2PBridgeService` in `MainActivity.kt`) is called with `BridgeAction.BroadcastCommand("display", folderName)`.
    *   **Class:** `info.benjaminhill.localmesh.ui.MainScreen`
    *   **Message Type:** `info.benjaminhill.localmesh.mesh.BridgeAction.BroadcastCommand`

2.  **`MainActivity` to `BridgeService` (Intent Communication)**
    *   **Action:** The `startP2PBridgeService` method in `MainActivity.kt` creates an `Intent` to start the `BridgeService`.
    *   **Intent Action:** The `Intent`'s action is set to the class name of the `BridgeAction`, which is `info.benjaminhill.localmesh.mesh.BridgeAction$BroadcastCommand`.
    *   **Extras:** The "display" command and the selected folder name are added as extras to the `Intent` under the keys `BridgeService.EXTRA_COMMAND` and `BridgeService.EXTRA_PAYLOAD`.
    *   **Class:** `info.benjaminhill.localmesh.MainActivity`

3.  **`BridgeService` (P2P Broadcasting)**
    *   **Action:** The `BridgeService` receives the `Intent` in its `onStartCommand` method.
    *   **Handling:** The `when` statement correctly handles the `BridgeAction.BroadcastCommand`.
    *   **Wrapper Creation:** It extracts the command ("display") and payload ("eye") from the `Intent`, creates an `HttpRequestWrapper`, and serializes it to JSON.
        *   `val wrapper = HttpRequestWrapper(method = "GET", path = "/display", params = "path=eye", sourceNodeId = endpointName)`
    *   **Broadcast:** It calls `broadcast(wrapper.toJson())` to send the command to all peers.
    *   **Class:** `info.benjaminhill.localmesh.mesh.BridgeService`

4.  **`NearbyConnectionsManager` (Peer-to-Peer Communication)**
    *   **Action:** `BridgeService.broadcast()` calls `nearbyConnectionsManager.broadcastBytes()`.
    *   **Mechanism:** `NearbyConnectionsManager` uses the Google Nearby Connections API to send the byte payload (the JSON string of the `HttpRequestWrapper`) to all connected peers.
    *   **Class:** `info.benjaminhill.localmesh.mesh.NearbyConnectionsManager`

5.  **Peer: `BridgeService` (Receiving the Command)**
    *   **Action:** On the peer device, the `NearbyConnectionsManager`'s payload callback is triggered when the bytes are received.
    *   **Payload Handling:** The callback in `BridgeService` receives the `Payload`. It identifies it as `Payload.Type.BYTES`.
    *   **Message Conversion:** The `handleBytesPayload` method is called. It deserializes the JSON string back into an `HttpRequestWrapper` object.
    *   **Dispatch:** It then calls `localHttpServer.dispatchRequest(wrapper)` to process the command locally.
    *   **Class:** `info.benjaminhill.localmesh.mesh.BridgeService`

6.  **Peer: `LocalHttpServer` (Internal HTTP Request Dispatch)**
    *   **Action:** `dispatchRequest` receives the `HttpRequestWrapper`. It parses the `params` string to separate query parameters from body content, using `%%BODY%%` as a delimiter.
    *   **URL Construction:** It constructs the URL for the local `HttpClient`, ensuring that query parameters are correctly appended. For a `/display` command, the URL will be `http://localhost:8099/display?sourceNodeId=PEER_ID&path=eye`.
    *   **Request Execution:** It uses an `HttpClient` to make a synthetic request to its own Ktor server.
    *   **Class:** `info.benjaminhill.localmesh.LocalHttpServer`

7.  **Peer: `LocalHttpServer` (Routing and Processing)**
    *   **Routing:** The Ktor server's routing block matches the `GET "/display"` endpoint. The `p2pBroadcastInterceptor` sees the `sourceNodeId` and allows the request to pass through without re-broadcasting.
    *   **Action:** The handler for this route is executed. It extracts the `path` parameter ("eye") from the request.
    *   **Intent Creation:** It creates an `Intent` to start the `WebViewActivity`.
    *   **URL for WebView:** The `Intent` includes the URL to be loaded in the `WebView`, which is `http://localhost:8099/eye/index.html` (or similar, depending on the static file server logic).
    *   **Class:** `info.benjaminhill.localmesh.LocalHttpServer`

8.  **Peer: `WebViewActivity` (Display)**
    *   **Action:** `WebViewActivity` is started.
    *   **Content Loading:** It retrieves the URL from the `Intent` and loads it into a full-screen `WebView`.
    *   **Result:** The `LocalHttpServer` serves the `index.html` file from the `assets/web/eye/` directory, and it is rendered in the `WebView`.
    *   **Class:** `info.benjaminhill.localmesh.WebViewActivity`
