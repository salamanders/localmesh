# Message Flow Documentation

This document outlines the message flow from the user selecting a folder in the web UI on one device to the content being displayed in a `WebView` on a peer device.

## Abstract Flow

1.  **UI Interaction:** The user taps a folder in the web UI running in their browser.
2.  **UI to Local Server:** The web UI sends a `GET /display?path=<folder>` request to the `LocalHttpServer` on the same device.
3.  **Local Server Interception & Broadcast:** An interceptor in the `LocalHttpServer` catches the request, wraps it as an `HttpRequestWrapper`, and broadcasts it to all peers.
4.  **Local Server Execution:** The interceptor allows the original request to proceed, and the local server opens the content in a `WebView` on the originating device.
5.  **Peer Reception:** A peer device receives the broadcasted `HttpRequestWrapper`.
6.  **P2P Command to HTTP Request:** The peer's `BridgeService` dispatches the wrapper to its own local `LocalHttpServer`.
7.  **HTTP Request Processing:** The peer's `LocalHttpServer` processes the synthetic HTTP request.
8.  **Display on Peer:** The corresponding folder's content is opened in a full-screen `WebView` on the peer device.

## Detailed Implementation Flow

Here is the detailed flow with specific classes, methods, and message types:

1.  **Web UI (`index.html`)**
    *   **Event Trigger:** The user taps on a folder item in the list (e.g., "eye").
    *   **Action:** The `onclick` handler for the folder item calls a JavaScript function, e.g., `displayFolder('eye')`.
    *   **HTTP Request:** The `displayFolder` function executes `fetch('/display?path=eye')`. This sends a `GET` request to the local server running on `http://localhost:8099`.

2.  **`LocalHttpServer` (`p2pBroadcastInterceptor`)**
    *   **Interception:** The `p2pBroadcastInterceptor` intercepts the incoming `GET /display` request.
    *   **Strategy:** It identifies the path and determines the strategy is `BroadcastAndExecute`.
    *   **Wrapper Creation:** It creates an `HttpRequestWrapper` containing the method, path, and parameters of the original request.
        *   `val wrapper = HttpRequestWrapper(method = "GET", path = "/display", queryParams = "path=eye", body = "", sourceNodeId = ...)`
    *   **Broadcast:** It calls `service.broadcast(wrapper.toJson())` to send the command to all peers.
    *   **Execution:** Because the strategy is `BroadcastAndExecute`, the interceptor allows the request to continue down the pipeline to the route handler.

3.  **`LocalHttpServer` (Routing and Local Execution)**
    *   **Routing:** The Ktor server's routing block matches the `GET "/display"` endpoint.
    *   **Action:** The handler for this route is executed. It extracts the `path` parameter ("eye").
    *   **Local Display:** It creates an `Intent` to start the `WebViewActivity` on the *originating* device, loading the URL `http://localhost:8099/eye`.

4.  **`NearbyConnectionsManager` (Peer-to-Peer Communication)**
    *   **Action:** `BridgeService.broadcast()` calls `nearbyConnectionsManager.broadcastBytes()`.
    *   **Mechanism:** `NearbyConnectionsManager` sends the JSON string of the `HttpRequestWrapper` to all connected peers.

5.  **Peer: `BridgeService` (Receiving the Command)**
    *   **Action:** On a peer device, the `NearbyConnectionsManager`'s `onPayloadReceived` callback is triggered.
    *   **Payload Handling:** The `handleBytesPayload` method deserializes the JSON string back into an `HttpRequestWrapper` object.
    *   **Dispatch:** It calls `localHttpServer.dispatchRequest(wrapper)` to process the command locally on the peer.

6.  **Peer: `LocalHttpServer` (Internal HTTP Request Dispatch)**
    *   **Action:** `dispatchRequest` receives the `HttpRequestWrapper`. It correctly parses the `params` to extract query parameters.
    *   **URL Construction:** It constructs the URL for the local `HttpClient`: `http://localhost:8099/display?sourceNodeId=PEER_ID&path=eye`.
    *   **Request Execution:** It makes a synthetic `GET` request to its own Ktor server.

7.  **Peer: `LocalHttpServer` (Routing and Peer Execution)**
    *   **Routing:** The Ktor server's routing block matches `GET "/display"`. The `p2pBroadcastInterceptor` sees the `sourceNodeId` and ignores the request, preventing a broadcast loop.
    *   **Action:** The handler executes, extracts the `path` parameter, and creates an `Intent` to start `WebViewActivity`.
    *   **Peer Display:** The `WebViewActivity` is started on the *peer* device, loading the content.