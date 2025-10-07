# Message Flow Documentation

This document outlines the message flow from the user selecting a folder in the UI on one device to the content being displayed in a `WebView` on a peer device.

## Abstract Flow

1.  **UI Interaction:** The user selects a folder from a dropdown menu in the app's main UI.
2.  **UI to App:** The UI sends a message to the application logic indicating the user's selection.
3.  **App to P2P Command:** The application logic converts this message into a P2P command.
4.  **Broadcast:** The app broadcasts this command to all connected peers.
5.  **Peer Reception:** A peer device receives the P2P command.
6.  **P2P Command to HTTP Request:** The peer's application logic converts the P2P command back into an internal HTTP request.
7.  **HTTP Request Processing:** The peer's internal web server processes the HTTP request.
8.  **Display:** The corresponding folder's content is opened in a full-screen `WebView`.

## Detailed Implementation Flow

Here is the detailed flow with specific classes, methods, and message types:

1.  **`MainActivity` and `MainScreen` (UI Layer)**
    *   **Event Trigger:** The user selects a folder (e.g., "eye") from the `FolderSelector` composable within `MainScreen.kt`.
    *   **Action:** The `onFolderSelected` callback is invoked inside the `FolderSelector`.
    *   **Message Creation:** This triggers the `onAction` callback passed to `MainScreen`, which is the `startP2PBridgeService` method in `MainActivity.kt`.
    *   **Action:** `MainScreen.kt` calls `onAction(BridgeAction.BroadcastCommand("display", folderName))`.
    *   **Class:** `info.benjaminhill.localmesh.ui.MainScreen`
    *   **Message Type:** `info.benjaminhill.localmesh.mesh.BridgeAction.BroadcastCommand`

2.  **`MainActivity` to `BridgeService` (Intent Communication)**
    *   **Action:** The `startP2PBridgeService` method in `MainActivity.kt` creates an `Intent` to start the `BridgeService`.
    *   **Intent Action:** The `Intent`'s action is set to the class name of the `BridgeAction`, which is `info.benjaminhill.localmesh.mesh.BridgeAction$BroadcastCommand`.
    *   **Extras:** The "display" command and the selected folder name are added as extras to the `Intent` under the keys `BridgeService.EXTRA_COMMAND` and `BridgeService.EXTRA_PAYLOAD`.
    *   **Class:** `info.benjaminhill.localmesh.MainActivity`

3.  **`BridgeService` (P2P Broadcasting) - THE GAP**
    *   **Action:** The `BridgeService` receives the `Intent` in its `onStartCommand` method.
    *   **GAP:** The `when` statement in `onStartCommand` **only** checks for `BridgeAction.Start` and `BridgeAction.Stop`. It does not have a case to handle `BridgeAction.BroadcastCommand`.
    *   **Expected Behavior (The Fix):** The service should have a case for `BroadcastCommand`. It would extract the command and payload from the `Intent`, create an `HttpRequestWrapper`, and broadcast it.
        *   `val wrapper = HttpRequestWrapper(method = "GET", path = "/$command", params = "path=$payload", sourceNodeId = endpointName)`
        *   `broadcast(wrapper.toJson())`
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

6.  **Peer: `LocalHttpServer` (Internal HTTP Request)**
    *   **Action:** `dispatchRequest` makes an HTTP request to its own Ktor server using an `HttpClient`.
    *   **URL:** The URL is constructed from the wrapper's contents: `http://localhost:8099/display?sourceNodeId=PEER_ID&path=eye`.
    *   **Class:** `info.benjaminhill.localmesh.LocalHttpServer`

7.  **Peer: `LocalHttpServer` (Processing the Request)**
    *   **Routing:** The Ktor server's routing block matches the `GET "/display"` endpoint.
    *   **Action:** The handler for this route is executed. It extracts the `path` parameter ("eye").
    *   **Intent Creation:** It creates an `Intent` to start the `WebViewActivity`.
    *   **URL for WebView:** The `Intent` includes the URL to be loaded in the `WebView`, which is `http://localhost:8099/eye`.
    *   **Class:** `info.benjaminhill.localmesh.LocalHttpServer`

8.  **Peer: `WebViewActivity` (Display)**
    *   **Action:** `WebViewActivity` is started.
    *   **Content Loading:** It retrieves the URL from the `Intent` and loads it into a full-screen `WebView`.
    *   **Result:** The `LocalHttpServer` serves the `index.html` file from the `assets/web/eye/` directory, and it is rendered in the `WebView`.
    *   **Class:** `info.benjaminhill.localmesh.WebViewActivity`