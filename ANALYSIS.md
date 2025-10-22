# Camera-to-Slideshow Workflow Analysis

This document details the end-to-end process of a user taking a picture on one device and having it appear in the slideshow on a peer device.

## Step-by-Step Breakdown

1.  **Initiation (Device A):**
    *   A user opens the `/camera/index.html` page.
    *   They select an image using the `<input type="file">`.
    *   The `camera.js` script fetches the device's unique ID from the `/status` endpoint.

2.  **Image Processing and Local Upload (Device A):**
    *   The selected image is resized in the browser to a maximum dimension of 800px.
    *   A unique filename is generated using the format `photos/camera_{deviceId}_{timestamp}.jpg`.
    *   The resized image is packaged into a `FormData` object.
    *   A `POST` request is sent to the `/send-file` endpoint on the local Ktor server.

3.  **Backend File Handling and Broadcast (Device A):**
    *   `LocalHttpServer` receives the multipart file upload.
    *   It saves the image to a temporary local file.
    *   Crucially, it makes two calls:
        1.  `AssetManager.saveFile()`: The image is immediately saved to the final local destination (`web_cache/photos/`). This makes it instantly available to Device A's own slideshow.
        2.  `BridgeService.sendFile()`: This initiates the P2P transfer.

4.  **P2P File Transfer (Gossip Protocol):**
    *   `BridgeService.sendFile()` reads the temporary file.
    *   It breaks the file into 32KB chunks.
    *   Each chunk is wrapped in a `FileChunk` object within a `NetworkMessage`.
    *   Each `NetworkMessage` is sent to all directly connected peers, starting the gossip process.

5.  **Peer Reception and Reassembly (Device B):**
    *   `BridgeService.handleIncomingData()` on the peer device receives each `NetworkMessage`.
    *   It identifies and ignores any duplicate messages based on `messageId`.
    *   The `FileChunk` payload from each new message is passed to the `FileReassemblyManager`.
    *   The `FileReassemblyManager` collects the chunks. Once all chunks for a `fileId` have arrived, it reassembles them and saves the complete file to `web_cache/photos/` on Device B.

6.  **Slideshow Activation (Remote Trigger):**
    *   After the successful upload in step 2, `camera.js` on Device A sends a `GET` request to `/display?path=slideshow`.
    *   This request is intercepted by the `p2pBroadcastInterceptor` on Device A, which wraps it in a `NetworkMessage` and broadcasts it to all peers.
    *   `BridgeService.handleIncomingData()` on Device B receives this message and dispatches it to its local `LocalHttpServer`.
    *   The `/display` route handler on Device B is executed, launching the `DisplayActivity` and showing the slideshow.

7.  **Image Discovery and Display (Device B):**
    *   `slideshow.js` starts on Device B.
    *   It immediately calls `fetchImages()`, which requests a list of files from the local `/list?path=photos&type=files` endpoint.
    *   A random image from the fetched list is displayed.
    *   The slideshow continues to poll for new images every 15 seconds (`IMAGE_FETCH_INTERVAL`) and cycles through the available images every 5 seconds (`SLIDESHOW_INTERVAL`).

## Identified Gaps and Inefficiencies

Based on the workflow, two primary issues are apparent:

1.  **Race Condition:**
    *   The `display` command (Step 6) and the file transfer (Step 4) are initiated almost simultaneously. The `display` command is a single, small message and will likely arrive and execute on Device B *before* the file transfer is complete, especially for larger images that are broken into many chunks.
    *   **Result:** The slideshow will open on Device B and fetch the list of images *before* the new image has been fully reassembled and saved. The new picture will not appear until the slideshow's next polling interval (`IMAGE_FETCH_INTERVAL`), leading to a noticeable delay of up to 15 seconds.

2.  **Inefficient Image Discovery:**
    *   The slideshow relies exclusively on polling (`setInterval(fetchImages, ...)`). While simple, this is inefficient. The slideshow has no direct notification that a new file has arrived.
    *   **Result:** This leads to the delay mentioned above and causes unnecessary network traffic as the web view continuously polls the local Ktor server for updates, even when none exist. A more robust solution would involve a push-based mechanism (e.g., WebSocket) to notify the slideshow of new images in real-time.
