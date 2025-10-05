# Android Offline P2P Messaging Bridge: App Requirements

## 1\. Project Overview

Project Title: P2P Web Bridge  
Objective: To create a native Android application that acts as a bridge between a standard mobile web browser (running on the same device) and the Android Nearby Connections API. The app will enable a web page, which has no direct access to peer-to-peer hardware, to send and receive short messages with other nearby devices running the same app, without needing an internet or cellular connection.  
Core Use Case: A user runs the P2P Web Bridge app in the background. They then open a specific web page in Chrome. JavaScript on that web page can then communicate with other nearby phones by making simple HTTP requests to the local P2P Web Bridge app.

## 2\. Core Architecture

The system consists of three main components operating on each phone:

1. Web Page (Frontend): The user interface, running in Chrome. It handles message display and user input. It communicates only with the Local HTTP Server.  
2. P2P Web Bridge App (Backend/Middleware): The native Android app, and the part this doc describes in detail.  
   * Local HTTP Server: Listens on a localhost port for requests from the Web Page.  
   * Nearby Connections Manager: Manages device discovery, connection, and data transfer with other phones.  
3. Other Phones: Other devices running the same P2P Web Bridge app.

### Data Flow (Sending a Message):

Web Page JS \-\> HTTP POST request \-\> Local HTTP Server \-\> Nearby Connections Manager \-\> Broadcasts to Peers

### Data Flow (Receiving a Message):

Peer Device \-\> Nearby Connections Manager \-\> Stores message in a queue \-\> Local HTTP Server \-\> HTTP GET request \-\> Web Page JS

## 3\. Key Features & Functionality

### 3.1. Nearby Connections Management

* Strategy: The app must use the Strategy.P2P\_CLUSTER strategy. This creates a many-to-many mesh network, which is ideal for a multi-user chatroom scenario.  
* Service ID: The app will use a hardcoded, unique service ID string (e.g., com.example.p2pwebbridge.v1) to ensure it only connects to other instances of itself.  
* Automatic Operation: Upon starting the service, the app must simultaneously begin both advertising its presence and discovering other peers.  
* Connection Handling: The app must be configured to automatically accept any incoming connection request from a discovered peer. There should be no user prompt for connection acceptance.  
* Payloads: The app will handle byte payloads, which will contain UTF-8 encoded JSON strings for messages.  
* Disconnection: The app should gracefully handle peer disconnections and update the list of connected peers.

### 3.2. Local HTTP Server

* The app must run a lightweight HTTP server that listens only on the local loopback address (localhost / 127.0.0.1).  
* Port: The server should listen on a configurable, but defaulted, port (e.g., 8080).  
* API Endpoints: The server will expose the RESTful endpoints specified in the Appendix: Local API Specification.

### 3.3. Foreground Service & Power Management

* Requirement: All networking logic (HTTP server and Nearby Connections) must run within a Foreground Service. This is critical to prevent the OS from killing the app when it is in the background and to ensure connections are maintained.  
* Notification: The foreground service must display a persistent notification to the user, indicating that the service is active and providing a button to stop the service.  
* Wake Locks: The app should acquire a partial wake lock to ensure the CPU remains active to process network events while the service is running, even if the screen is off. This should be used judiciously.

### 3.4. User Interface (UI)

The native app's UI should be minimal, serving only as a control panel for the background service.

* A large "Start Service" / "Stop Service" toggle button.  
* A status indicator (e.g., "Inactive", "Running \- 0 Peers", "Running \- 3 Peers").  
* A simple, scrollable log view that displays key events (e.g., "Service started," "Peer X connected," "Peer Y disconnected," "Error: Bluetooth is off").  
* A non-editable field showing the local server address (e.g., http://localhost:8080).

## 4\. Technical Specifications

* Android Versioning:  
  * targetSdkVersion: 35 (Android 15\)  
  * compileSdkVersion: 35  
  * minSdkVersion: 33 (Android 13\)  
* Dependencies:  
  * com.google.android.gms:play-services-nearby for the Nearby Connections API.  
  * A lightweight embedded HTTP server library, such as Ktor or NanoHTTPD.  
* Permissions (AndroidManifest.xml): The app must declare and handle runtime requests for the following permissions. The reason for each permission should be clearly explained to the user.  
  * android.permission.BLUETOOTH\_SCAN (Needed for discovering peers on API 31+).  
  * android.permission.BLUETOOTH\_ADVERTISE (Needed for making this device visible to peers on API 31+).  
  * android.permission.BLUETOOTH\_CONNECT (Needed for establishing connections with peers on API 31+).  
  * android.permission.ACCESS\_WIFI\_STATE & android.permission.CHANGE\_WIFI\_STATE (Used by Nearby Connections to manage Wi-Fi Direct).  
  * android.permission.ACCESS\_COARSE\_LOCATION (Required for Bluetooth and Wi-Fi scanning on older Android versions).  
  * android.permission.ACCESS\_FINE\_LOCATION (Needed for Wi-Fi Direct functionality).  
  * android.permission.NEARBY\_WIFI\_DEVICES (Required on API 33+ for Wi-Fi Direct operations without needing location). Use the usesPermissionFlags="neverForLocation" attribute.  
  * android.permission.POST\_NOTIFICATIONS (Required on API 33+ to show the foreground service notification).  
  * android.permission.FOREGROUND\_SERVICE (Required to run the foreground service).  
  * android.permission.WAKE\_LOCK (Required to keep the CPU awake).1  
  * android.permission.INTERNET (Required to open a local socket for the HTTP server).2

## 5\. Error Handling & Edge Cases (Checks for the Prototype)

The app must be robust and handle the following scenarios gracefully:

* Permissions Denied: If the user denies necessary permissions, the app should display an informative message and disable the "Start Service" button.  
* Hardware State: Before starting the service, the app must check if Bluetooth and Wi-Fi are enabled. If not, it should prompt the user to enable them.  
* Connection Failures: The app should log and handle failures in connecting to a peer without crashing.  
* Server Port Conflict: The app should handle the case where the chosen port (8080) is already in use, displaying an error to the user.  
* App Lifecycle: The service must persist correctly when the app is backgrounded or the screen is turned off. It should be properly torn down when the user explicitly stops it or removes the app from the "recents" tray.  
* Invalid HTTP Requests: The local server should handle malformed JSON or invalid requests from the web page without crashing, returning an appropriate HTTP error code (e.g., 400 Bad Request).

---

## Appendix: Local API Specification

Base URL: http://localhost:**98080**

### Endpoint: GET /status

* Description: Provides the current status of the service.  
* Method: GET  
* Success Response (200 OK):  
  JSON  
  {  
    "status": "RUNNING",  
    "id":"jKLm",  
    "peerCount": 3,  
    "peerIds": \["aB1c", "dE2f", "gH3i"\]  
  }

### Endpoint: POST /send

* Description: Sends a message to all connected peers.  
* Method: POST  
* Request Body:  
  JSON  
  {  
    "message": "Hello from the web page\!"  
  }

* Success Response (202 Accepted): Contains an incremented sequence number (e.g. 17\) representing the internal count of how many messages this device has sent. This allows recipients to order the messages from this device.  
* Error Response (400 Bad Request): If the JSON is malformed or the message field is missing.

### Endpoint: GET /messages

* Description: Retrieves all messages received from peers since the last poll.  "sequence" is per-sender.  "timestamp" is when this device received the message.  
* Method: GET  
* Success Response (200 OK):  
  ```json
  [  
    {  
      "from": "dE2f",  
      "sequence": 5,  
      "timestamp": 1672531200000,  
      "payload": "This is a reply."  
    },  
    {  
      "from": "gH3i",  
     "sequence": 17,  
      "timestamp": 1672531201500,  
      "payload": "Another message!"  
    }  
  ]
  ```


  Note: The response is an array of messages. The array will be empty \[\] if no new messages have been received.