# Message Flow Documentation

This document outlines the message flows for key actions in the LocalMesh application.

## Remote Display Flow

This flow outlines how a user tapping a folder in the web UI on one device causes the content to be displayed in a `WebView` on a peer device.

1.  **UI Interaction:** The user taps a folder in the web UI (e.g., "eye").
2.  **UI to Local Server:** The web UI sends a `GET /display?path=eye` request to the `LocalHttpServer` on the same device.
3.  **Local Server Interception & Broadcast:** The `p2pBroadcastInterceptor` in `LocalHttpServer` intercepts the request.
    *   **Strategy:** It identifies that `/display` is a broadcast-only path.
    *   **Wrapper Creation:** It creates an `HttpRequestWrapper` containing the method and parameters of the original request.
    *   **Broadcast:** It calls `service.broadcast()` to send the wrapper to all peers.
    *   **Stop Local Execution:** The interceptor immediately responds to the local request and stops the pipeline. This is intentional, as the display command should only execute on remote peers, not the originating device.
4.  **Peer Reception:** A peer device receives the broadcasted `HttpRequestWrapper`.
5.  **P2P Command to HTTP Request:** The peer's `BridgeService` dispatches the wrapper to its own local `LocalHttpServer`.
6.  **HTTP Request Processing:** The peer's `LocalHttpServer` processes the synthetic `GET /display` request. The `p2pBroadcastInterceptor` sees the `sourceNodeId` parameter and ignores the request, preventing a broadcast loop.
7.  **Display on Peer:** The corresponding route handler executes, starting a `WebViewActivity` on the peer device to display the content.

---

## File Transfer Flow

This flow describes how a file is sent from the web UI on one device and received by a peer.

1.  **UI Interaction:** The user selects a file in the web UI.
2.  **Local `POST /send-file`:** The UI sends a `multipart/form-data` POST request to the local `LocalHttpServer`.
3.  **Local Server (`/send-file` route):**
    *   The server saves the uploaded file to a temporary location on the device.
    *   It calls `BridgeService.sendFile()` with the temporary file.
4.  **`BridgeService.sendFile()`:**
    *   Creates a `Payload.Stream` from the file.
    *   Creates an `HttpRequestWrapper` for a `POST /send-file` request. This wrapper is crucial as it contains the `filename` and the unique `payloadId` of the stream as query parameters.
    *   Broadcasts the `HttpRequestWrapper` (as a `BYTES` payload) to all peers.
    *   Sends the `Payload.Stream` to all peers.
5.  **Peer: `BridgeService` (Receiving the Command):**
    *   The peer's `handleBytesPayload` method receives the `HttpRequestWrapper` for `/send-file`.
    *   It parses the `filename` and `payloadId` from the wrapper's `queryParams`.
    *   It stores this `payloadId` -> `filename` mapping in the `incomingFilePayloads` map. This prepares the device to receive the upcoming stream.
    *   It then dispatches the wrapper to its local `LocalHttpServer` for logging purposes.
6.  **Peer: `LocalHttpServer` (Receiving the Command):**
    *   The `/send-file` route receives the request. Since it has a `sourceNodeId`, it knows it's from a peer and simply logs that it is awaiting the stream.
7.  **Peer: `BridgeService` (Receiving the Stream):**
    *   The peer's `handleStreamPayload` method is triggered when the `Payload.Stream` arrives.
    *   It uses the `payload.id` to look up the `filename` in the `incomingFilePayloads` map.
    *   It saves the incoming stream to a file with the correct name in its `web_cache` directory.