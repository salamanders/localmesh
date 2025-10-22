# **Technical Specification for com.google.android.gms.nearby.connection.Strategy.P2P\_CLUSTER**

## **Strategy.P2P\_CLUSTER \- Core Principles**

### **Definition and Topology**

Strategy.P2P\_CLUSTER is a peer-to-peer (P2P) connection strategy within the Google Nearby
Connections API. It is engineered to support an M-to-N, amorphous, or cluster-shaped network
topology.1 This strategy facilitates the creation of flexible, unstructured networks of devices
operating within a nominal radio range of approximately 100 meters.1

It functions as the default strategy for the API. Consequently, legacy API calls that do not
explicitly specify a Strategy parameter operate implicitly using the P2P\_CLUSTER model.1 The core
architectural principle is the absence of a predefined hierarchy; any device can connect to any
other device, enabling a decentralized communication mesh.

### **Functional Characteristics**

The defining functional characteristic of P2P\_CLUSTER is its support for a symmetric, many-to-many
connection model. Each device participating in the network can simultaneously perform two roles:

1. Initiate outgoing connections to 'M' other devices.
2. Accept incoming connections from 'N' other devices.1

This M-to-N capability is fundamental to its mesh-like nature. Furthermore, the API permits a single
device to engage in advertising and discovery operations concurrently when using this strategy,
which is essential for peer discovery in a fully decentralized network where no device has a
pre-assigned role.5

### **Optimal Use Cases**

The P2P\_CLUSTER strategy is optimized for applications that require a dynamic, "mesh-like
experience" and primarily exchange smaller data payloads.2 The topological flexibility makes it
highly suitable for scenarios where the network composition changes frequently or is unknown in
advance.

Identified optimal use cases include:

* **Local Multiplayer Gaming:** Devices can join and leave a game session without a central server,
  communicating game state directly with peers.2
* **Collaborative Applications:** Tools such as shared virtual whiteboards where multiple users
  contribute simultaneously benefit from the decentralized structure.8
* **Offline Data Synchronization:** Small-scale data sharing or synchronization in environments
  without internet connectivity, where the data packets are relatively small (e.g., control
  messages, status updates).8

The emphasis on "smaller payloads" is a direct consequence of the underlying communication
technology and its inherent bandwidth limitations, which are detailed in a subsequent section.

### **Comparative Analysis of Strategies**

The selection of a connection strategy involves a direct trade-off between topological flexibility
and data throughput. P2P\_CLUSTER prioritizes flexibility over performance. In contrast, the
P2P\_STAR and P2P\_POINT\_TO\_POINT strategies impose stricter topological constraints to enable the
use of higher-bandwidth communication mediums.2

The choice of strategy directly influences which underlying physical layer technologies the API can
leverage. The strict, hierarchical topologies of P2P\_STAR (1-to-N hub-and-spoke) and
P2P\_POINT\_TO\_POINT (1-to-1) are compatible with the technical constraints of Wi-Fi Hotspots,
where a device can function as either an access point or a client, but not both simultaneously.10
The role-agnostic, M-to-N model of P2P\_CLUSTER is incompatible with this limitation, thereby
restricting it to the more universally flexible but lower-performing Bluetooth medium when no
external router is present.

The following table provides a comparative analysis of the three available strategies.

| Feature             | Strategy.P2P\_CLUSTER                                   | Strategy.P2P\_STAR                                                       | Strategy.P2P\_POINT\_TO\_POINT                     |
|:--------------------|:--------------------------------------------------------|:-------------------------------------------------------------------------|:---------------------------------------------------|
| **Topology**        | M-to-N (Cluster/Mesh)                                   | 1-to-N (Star/Hub-and-Spoke)                                              | 1-to-1 (Point-to-Point)                            |
| **Bandwidth**       | Lower                                                   | Higher                                                                   | Highest                                            |
| **Primary Medium**  | Bluetooth (Classic/BLE)                                 | Bluetooth, Wi-Fi Hotspot                                                 | Bluetooth, Wi-Fi Hotspot                           |
| **Practical Limit** | 3–4 devices per node                                    | Up to 7-10 devices connected to a single hub                             | 1 device                                           |
| **Ideal Use Case**  | Multiplayer gaming, collaborative apps (small payloads) | Group file sharing, classroom quizzes (one advertiser, many discoverers) | Large file transfers, high-quality media streaming |

## **Technology and Performance Metrics**

### **Primary Communication Medium**

When operating in a fully offline, peer-to-peer mode without an intermediary network
infrastructure (e.g., a Wi-Fi router), the P2P\_CLUSTER strategy exclusively utilizes **Bluetooth
** (Bluetooth Classic and Bluetooth Low Energy) for discovery, connection establishment, and data
transfer.10

Unlike P2P\_STAR and P2P\_POINT\_TO\_POINT, this strategy does not and cannot upgrade connections to
use Wi-Fi Hotspots (Wi-Fi Direct).10 This limitation is a direct result of its flexible M-to-N
topology, which is architecturally incompatible with the single-host nature of a Wi-Fi Hotspot.

A notable exception exists: if all participating devices are connected to the same Wi-Fi LAN, the
Nearby Connections API may use mDNS for discovery over the existing network. If a connection is
initiated, it can be established directly over the LAN, which is significantly more performant than
Bluetooth.11

### **Bandwidth Characteristics**

The reliance on Bluetooth as the primary communication medium means that P2P\_CLUSTER inherently
provides lower bandwidth compared to the other strategies.2 This performance characteristic makes it
suitable for transferring small payloads, such as control messages, metadata, or text-based data,
which are typically under 32 KB.8 It is not well-suited for high-throughput use cases like streaming
high-quality video or transferring large files, as such attempts may lead to performance degradation
or connection instability.12

### **Connection Limits: Theoretical vs. Practical**

The connection limits of P2P\_CLUSTER are dictated by the constraints of the underlying Bluetooth
hardware on mobile devices.

* **Theoretical Limit:** The Bluetooth standard specifies a theoretical maximum of 7 connected
  devices in a piconet.10
* **Practical Limit:** In real-world applications on consumer Android devices, the practical limit
  for maintaining stable connections from a single device is significantly lower, averaging between
  **3 to 4 devices**.10

Attempting to exceed this practical limit can lead to connection failures, high latency, and
frequent, unpredictable disconnections.14 System designs must be built around this practical
limitation.

### **Factors Impacting Connection Limits**

The practical connection limit is not a fixed API constant but a soft, environment-dependent
variable influenced by several factors.

1. **Hardware Constraints:** Mobile Bluetooth chips are designed for low power consumption and a
   small physical footprint, not for high-density networking, which limits their capacity for
   simultaneous connections.10
2. **Shared Resources:** The Bluetooth controller's available connection slots are a finite system
   resource. The Nearby Connections API must compete for these slots with other applications and
   system services. Peripherals already connected to the device, such as smartwatches, wireless
   headphones, or fitness trackers, consume available slots and directly reduce the number of
   connections an application can establish via P2P\_CLUSTER.10

An application's connection stability is therefore dependent not only on its own logic but also on
the user's external device ecosystem. A robust implementation must be designed defensively,
anticipating a worst-case scenario where only one or two connection slots are available.

## **Configuration and Permissions**

### **Gradle Dependency Specification**

To utilize the Nearby Connections API, the Google Play Services dependency must be included in the
application's module-level build.gradle file. It is recommended to use a specific, stable version
number in place of LATEST\_VERSION for production builds to ensure deterministic behavior.

Groovy

implementation 'com.google.android.gms:play-services-nearby:LATEST\_VERSION'

3

### **Android Manifest Permissions by API Level**

Correctly declaring permissions in AndroidManifest.xml and handling runtime permission requests is
critical for the API to function. The required permissions are dependent on the specific operation (
advertising, discovery, connecting) and the target Android SDK level. Failure to provide the correct
permissions for the device's OS version is a common source of implementation errors, often resulting
in cryptic status codes like 8036 (MISSING\_PERMISSION\_ACCESS\_FINE\_LOCATION).15

Furthermore, there can be discrepancies between official documentation and the behavior observed on
specific devices, particularly concerning NEARBY\_WIFI\_DEVICES and the maxSdkVersion attributes for
Wi-Fi state permissions.16 This necessitates a robust, conditional runtime permission request flow
based on Build.VERSION.SDK\_INT and empirical testing across target devices.

The following table consolidates the required permissions for P2P\_CLUSTER.

| Permission                                  | Operation(s)            | Required For SDK Levels | Notes                                                                    |
|:--------------------------------------------|:------------------------|:------------------------|:-------------------------------------------------------------------------|
| android.permission.BLUETOOTH                | Advertising, Connecting | Up to 30 (Android 11\)  | Deprecated for SDK 31+. Use BLUETOOTH\_ADVERTISE and BLUETOOTH\_CONNECT. |
| android.permission.BLUETOOTH\_ADMIN         | Advertising             | Up to 30 (Android 11\)  | Deprecated for SDK 31+.                                                  |
| android.permission.BLUETOOTH\_ADVERTISE     | Advertising             | 31+ (Android 12+)       | Replaces BLUETOOTH and BLUETOOTH\_ADMIN for advertising.                 |
| android.permission.BLUETOOTH\_SCAN          | Discovery               | 31+ (Android 12+)       | Required for discovering nearby devices.                                 |
| android.permission.BLUETOOTH\_CONNECT       | Connecting              | 31+ (Android 12+)       | Required for accepting/requesting connections.                           |
| android.permission.ACCESS\_WIFI\_STATE      | Advertising             | All                     | Required by the API for medium selection.                                |
| android.permission.CHANGE\_WIFI\_STATE      | Advertising             | All                     | Required by the API for medium selection.                                |
| android.permission.ACCESS\_COARSE\_LOCATION | Discovery               | 23-30 (Android 6-11)    | Required for Bluetooth scanning on these versions.                       |
| android.permission.ACCESS\_FINE\_LOCATION   | Discovery               | 29+ (Android 10+)       | Required for Bluetooth scanning on these versions.                       |
| android.permission.NEARBY\_WIFI\_DEVICES    | Discovery               | 33+ (Android 13+)       | Required for Wi-Fi-based discovery features.                             |

## **Implementation: Pre-Connection Phase**

### **AdvertisingOptions Configuration**

The AdvertisingOptions object configures the behavior of a device when it is broadcasting its
presence. It is constructed using the AdvertisingOptions.Builder and passed as an argument to the
startAdvertising() method.7

Key configuration methods:

* setStrategy(Strategy.P2P\_CLUSTER): This is the mandatory method call to select the cluster
  strategy.7
* setLowPower(boolean): An optional flag. If set to true, advertising will be restricted to
  low-power mediums like Bluetooth Low Energy (BLE), which conserves battery at the potential cost
  of discovery speed and reliability. The default value is false.17

### **DiscoveryOptions Configuration**

The DiscoveryOptions object configures the behavior of a device when it is scanning for nearby
advertisers. It is constructed using the DiscoveryOptions.Builder and passed to startDiscovery().4

Key configuration methods and rules:

* setStrategy(Strategy.P2P\_CLUSTER): This is the mandatory method call to select the cluster
  strategy.5
* **Strategy Match Rule:** The Strategy object used in DiscoveryOptions **must be identical** to the
  one used in the AdvertisingOptions of the target devices. A mismatch will prevent discovery
  entirely.18
* setLowPower(boolean): An optional flag that mirrors the functionality in AdvertisingOptions,
  restricting discovery to low-power mediums. The default is false.4

### **Initiating Advertising**

A device begins advertising its presence by invoking the startAdvertising() method on an instance of
ConnectionsClient.

Java

AdvertisingOptions advertisingOptions \=  
new AdvertisingOptions.Builder().setStrategy(Strategy.P2P\_CLUSTER).build();

connectionsClient.startAdvertising(  
userName,  
serviceId,  
connectionLifecycleCallback,  
advertisingOptions);

7

Parameters:

* userName: A human-readable name for the local endpoint.
* serviceId: A unique string identifier for the application. All devices that wish to connect must
  use the same serviceId. The application's package name is the recommended value.9
* connectionLifecycleCallback: An instance of ConnectionLifecycleCallback that will handle events
  related to incoming connection requests.5
* advertisingOptions: The configured AdvertisingOptions object.

### **Initiating Discovery and Callback Handling**

A device begins scanning for advertisers by invoking the startDiscovery() method.

Java

DiscoveryOptions discoveryOptions \=  
new DiscoveryOptions.Builder().setStrategy(Strategy.P2P\_CLUSTER).build();

connectionsClient.startDiscovery(  
serviceId,  
endpointDiscoveryCallback,  
discoveryOptions);

5

The EndpointDiscoveryCallback is an abstract class that notifies the application of discovery
events:

* onEndpointFound(String endpointId, DiscoveredEndpointInfo info): This method is invoked when a new
  advertiser with a matching serviceId is found. This is the primary trigger for the application to
  initiate a connection request using the provided endpointId.5
* onEndpointLost(String endpointId): Invoked when a previously discovered endpoint is no longer
  advertising or is out of range.5

## **Implementation: Connection Lifecycle Management**

### **Connection Request Protocol**

After a discoverer finds an advertiser via onEndpointFound, it must initiate the connection
handshake by calling requestConnection().

Java

connectionsClient.requestConnection(  
userName,  
endpointId,  
connectionLifecycleCallback);

5

This call transmits a connection request from the discoverer to the advertiser.

### **ConnectionLifecycleCallback Event Handling**

The ConnectionLifecycleCallback is the central component for managing the state of a connection. An
instance of this callback must be provided by both the advertiser (in startAdvertising) and the
discoverer (in requestConnection). It handles the symmetric flow of events on both devices.5

Key callback methods:

* onConnectionInitiated(String endpointId, ConnectionInfo connectionInfo): This is the most critical
  callback. It is invoked on **both** the advertiser and the discoverer when a connection request
  has been successfully transmitted. At this point, no connection is established. Both devices must
  independently decide whether to proceed.5
* onConnectionResult(String endpointId, ConnectionResolution result): Invoked on both devices after
  they have made their decision. The result object indicates whether the connection was successful (
  STATUS\_OK) or failed.20
* onDisconnected(String endpointId): Invoked on a device when an established connection to the
  specified endpoint has been terminated, either intentionally or due to a network error.21

### **Connection Acceptance and Rejection Protocol**

Within the onConnectionInitiated callback, each device must explicitly signal its intent by calling
one of two methods:

1. acceptConnection(String endpointId, PayloadCallback payloadCallback): Signals acceptance of the
   connection. A PayloadCallback must be provided to handle subsequent data transfers.20
2. rejectConnection(String endpointId): Signals rejection of the connection.20

A connection is only considered fully established when **both** devices have called
acceptConnection. If either device calls rejectConnection or fails to respond, the connection
attempt is terminated and onConnectionResult will report a failure on both sides.

### **Security Protocol: Authentication Token Verification**

The Nearby Connections API provides a mechanism for authenticating connections to prevent
man-in-the-middle attacks, but it does not enforce its use. Implementing this protocol is critical
for any application handling sensitive data.

The ConnectionInfo object received in onConnectionInitiated contains a method,
getAuthenticationToken(), which returns a short, random string.9 For any given connection attempt,
this token will be identical on both the requesting and advertising device.

The API does not perform any validation of this token. The security protocol is a manual, UI-driven
process that must be implemented by the application developer:

1. Upon onConnectionInitiated, both devices extract the authentication token.
2. Each device displays the token in its user interface.
3. The application prompts the users of both devices to visually compare the tokens and confirm that
   they match.
4. Only after receiving explicit user confirmation on both devices should the application proceed to
   call acceptConnection.

Connections established without this manual verification step are insecure and vulnerable to
impersonation and data interception.20

## **Implementation: Post-Connection Data Exchange**

### **Supported Payload Types**

After a connection is successfully established, the distinction between "advertiser" and "
discoverer" is dissolved. The connection becomes symmetric and full-duplex, meaning both endpoints
can send and receive data simultaneously.5 All data is encapsulated in Payload objects.

The API supports three distinct payload types:

* Payload.Type.BYTES: A simple byte array, limited to a maximum size of 32 KB. This type is highly
  efficient for sending metadata, control signals, or short messages.8
* Payload.Type.FILE: Represents a file stored on the device's local storage. The API handles the
  efficient streaming of the file data, making it suitable for transfers of any size.8
* Payload.Type.STREAM: Represents a stream of data that is generated dynamically, where the total
  size is not known in advance. This is ideal for use cases like streaming audio from a device's
  microphone.8

### **sendPayload Method**

Any connected device can transmit data to one or more connected peers using the sendPayload()
method.

Java

// To a single endpoint  
connectionsClient.sendPayload(endpointId, payload);

// To multiple endpoints  
connectionsClient.sendPayload(listOfEndpointIds, payload);

9

### **PayloadCallback Handling**

Incoming payloads and transfer status updates are handled by the PayloadCallback, an abstract class
instance that is passed during the acceptConnection call.9

Key abstract methods to implement:

* onPayloadReceived(String endpointId, Payload payload): Invoked when a payload from a remote
  endpoint has been completely received. The application is responsible for processing the payload
  based on its type (e.g., reading bytes, accessing the received file).
* onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update): Provides status updates
  during a long-running transfer (for FILE or STREAM payloads). The PayloadTransferUpdate object
  contains information such as bytes transferred and total bytes, enabling the implementation of
  progress indicators.

## **Advanced Topics and Known Issues**

### **Connection Instability and Disconnection Vectors**

Implementations using P2P\_CLUSTER may encounter several known instability issues.

* **Historical Race Condition:** Versions of Google Play Services prior to 11.6.0 contained a race
  condition that would cause a successful connection to be followed immediately by a disconnection
  event. The workaround was to introduce an artificial delay of 100-200ms before calling
  acceptConnection. While the issue is officially fixed, this behavior might still be observed when
  interacting with devices running very old Play Services versions.21
* **Large Payload Failures:** Attempting to send large FILE payloads over P2P\_CLUSTER can trigger
  unexpected disconnections, likely due to the inherent instability and low bandwidth of a
  multi-device Bluetooth network. The connection may drop before the receiving device's
  onPayloadReceived callback is even triggered.12
* **Simultaneous Connection Request Failures:** In a true peer-to-peer scenario where both devices
  are advertising and discovering, it is possible for both to discover each other and call
  requestConnection at nearly the same time. This race condition often results in both connection
  attempts failing.24 A robust solution requires application-level logic, such as a randomized
  backoff before retrying, or a simple leader election protocol where the device with the
  lexicographically smaller endpoint ID is the one to initiate the request.

### **Device Limit Management and Mesh Networking Solutions**

The practical limit of 3-4 simultaneous connections per device is a hard constraint of the
underlying technology. To build larger networks, developers must implement their own mesh networking
logic on top of the Nearby Connections API.10

P2P\_CLUSTER should be viewed as a building block for a mesh, not a complete solution. A creative
solution involves:

1. Each device maintains connections to only a small subset (e.g., 3-4) of its neighbors.
2. The application implements a routing layer to forward messages through the network. A message
   from Node A to Node Z might be relayed through Nodes D and K.
3. The application must include logic to manage the connection graph, periodically discovering new
   neighbors and pruning stale connections to prevent the formation of isolated network "islands".13

### **Battery Consumption Optimization Techniques**

Continuous advertising and discovery are power-intensive operations that can significantly drain a
device's battery.25

* **Limit Operation Duration:** Do not advertise or discover indefinitely. Start these operations
  only when the user is in a relevant section of the application and stop them as soon as they are
  no longer needed. For example, it is a good practice to call stopDiscovery() immediately before
  calling requestConnection().13
* **Use Low Power Mode:** When the application context allows for slower discovery, enable the
  lowPower flag in both AdvertisingOptions and DiscoveryOptions to force the use of BLE, which is
  more energy-efficient.4
* **Background Operation:** To run Nearby Connections while the app is in the background, a
  foreground Service is required to prevent the OS from terminating the process. However, this will
  lead to continuous battery drain and should be used judiciously, with clear notifications to the
  user.27

### **Simultaneous Advertising and Discovery Considerations**

While the API technically permits a device to advertise and discover simultaneously with
P2P\_CLUSTER 6, empirical reports suggest that this mode of operation is a frequent source of errors
and connection instability.29 The underlying radio hardware must time-slice its activity between
broadcasting and scanning, which can lead to missed advertisements, increased latency, and a higher
rate of failed connection attempts.

For applications requiring maximum stability, it is more robust to treat advertising and discovery
as distinct operational states. A creative solution is to implement a state machine that cycles
between modes:

1. Advertise for a short duration (e.g., 5 seconds).
2. Stop advertising and start discovering for a duration (e.g., 10 seconds).
3. Repeat the cycle.

This approach can improve the reliability of both discovering peers and being discovered, at the
minor cost of increased discovery latency.

#### **Works cited**

1. Strategy | Google Play services, accessed October 20,
   2025, [https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/Strategy](https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/Strategy)
2. Strategies | Nearby Connections \- Google for Developers, accessed October 20,
   2025, [https://developers.google.com/nearby/connections/strategies](https://developers.google.com/nearby/connections/strategies)
3. (Deprecated) Two-way communication without internet \- Android Developers, accessed October 20,
   2025, [https://developer.android.com/codelabs/nearby-connections](https://developer.android.com/codelabs/nearby-connections)
4. DiscoveryOptions | Google Play services | Google for Developers, accessed October 20,
   2025, [https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/DiscoveryOptions](https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/DiscoveryOptions)
5. Nearby API pre-connection phase \- InnovationM Blog, accessed October 20,
   2025, [https://innovationm.com/blog/nearby-api-pre-connection-phase/](https://innovationm.com/blog/nearby-api-pre-connection-phase/)
6. Nearby Connections 2.0: When is simultaneous advertising/discovery possible?, accessed October
   20,
   2025, [https://stackoverflow.com/questions/46534103/nearby-connections-2-0-when-is-simultaneous-advertising-discovery-possible](https://stackoverflow.com/questions/46534103/nearby-connections-2-0-when-is-simultaneous-advertising-discovery-possible)
7. Nearby Connections for Android: Getting Started | Kodeco, accessed October 20,
   2025, [https://www.kodeco.com/35461793-nearby-connections-for-android-getting-started](https://www.kodeco.com/35461793-nearby-connections-for-android-getting-started)
8. Overview | Nearby Connections \- Google for Developers, accessed October 20,
   2025, [https://developers.google.com/nearby/connections/overview](https://developers.google.com/nearby/connections/overview)
9. Nearby Connection API \- ProAndroidDev, accessed October 20,
   2025, [https://proandroiddev.com/nearby-connection-api-b235529e6643](https://proandroiddev.com/nearby-connection-api-b235529e6643)
10. Google Nearby Connections 2.0 capabilities \- Stack Overflow, accessed October 20,
    2025, [https://stackoverflow.com/questions/51976470/google-nearby-connections-2-0-capabilities](https://stackoverflow.com/questions/51976470/google-nearby-connections-2-0-capabilities)
11. android \- Nearby Connections max connected devices \- clarification \- Stack Overflow, accessed
    October 20,
    2025, [https://stackoverflow.com/questions/65577640/nearby-connections-max-connected-devices-clarification](https://stackoverflow.com/questions/65577640/nearby-connections-max-connected-devices-clarification)
12. Nearby Connections disconnects when sending (larger) file payloads \- Stack Overflow, accessed
    October 20,
    2025, [https://stackoverflow.com/questions/52094445/nearby-connections-disconnects-when-sending-larger-file-payloads](https://stackoverflow.com/questions/52094445/nearby-connections-disconnects-when-sending-larger-file-payloads)
13. Multi peer connection using Google Nearby Connection \- Stack Overflow, accessed October 20,
    2025, [https://stackoverflow.com/questions/51177985/multi-peer-connection-using-google-nearby-connection](https://stackoverflow.com/questions/51177985/multi-peer-connection-using-google-nearby-connection)
14. Nearby networking really unstable with a large number of devices \#2452 \- GitHub, accessed
    October 20,
    2025, [https://github.com/google/nearby/discussions/2452](https://github.com/google/nearby/discussions/2452)
15. Nearby Connections discovery throws error 8036 on Android10 \- Stack Overflow, accessed October
    20,
    2025, [https://stackoverflow.com/questions/58921188/nearby-connections-discovery-throws-error-8036-on-android10](https://stackoverflow.com/questions/58921188/nearby-connections-discovery-throws-error-8036-on-android10)
16. Nearby Connections permissions documentation wrong/inconsistent · Issue \#297 \- GitHub,
    accessed October 20,
    2025, [https://github.com/android/connectivity-samples/issues/297](https://github.com/android/connectivity-samples/issues/297)
17. AdvertisingOptions | Google Play services | Google for Developers, accessed October 20,
    2025, [https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/AdvertisingOptions](https://developers.google.com/android/reference/com/google/android/gms/nearby/connection/AdvertisingOptions)
18. Nearby Connections 2.0: Mixing different strategies? \- Stack Overflow, accessed October 20,
    2025, [https://stackoverflow.com/questions/46509973/nearby-connections-2-0-mixing-different-strategies](https://stackoverflow.com/questions/46509973/nearby-connections-2-0-mixing-different-strategies)
19. Nearby show available connections list \- Stack Overflow, accessed October 20,
    2025, [https://stackoverflow.com/questions/67678114/nearby-show-available-connections-list](https://stackoverflow.com/questions/67678114/nearby-show-available-connections-list)
20. Manage connections \- Nearby \- Google for Developers, accessed October 20,
    2025, [https://developers.google.com/nearby/connections/android/manage-connections](https://developers.google.com/nearby/connections/android/manage-connections)
21. android \- Nearby Connections 2.0: Successful connection ..., accessed October 20,
    2025, [https://stackoverflow.com/questions/46533735/nearby-connections-2-0-successful-connection-immediately-followed-by-disconnec](https://stackoverflow.com/questions/46533735/nearby-connections-2-0-successful-connection-immediately-followed-by-disconnec)
22. Reversing, Analyzing, and Attacking Google's 'Nearby Connections' on Android \- University of
    Oxford Department of Computer Science, accessed October 20,
    2025, [https://www.cs.ox.ac.uk/files/10367/ndss19-paper367.pdf](https://www.cs.ox.ac.uk/files/10367/ndss19-paper367.pdf)
23. Two-way communication without internet: Nearby Connections (Part 2 of 3\) | by Isai Damier |
    Android Developers | Medium, accessed October 20,
    2025, [https://medium.com/androiddevelopers/two-way-communication-without-internet-nearby-connections-b118530cb84d](https://medium.com/androiddevelopers/two-way-communication-without-internet-nearby-connections-b118530cb84d)
24. Nearby Connections 2.0: simultaneous connection requests fail on both devices, accessed October
    20,
    2025, [https://stackoverflow.com/questions/63153282/nearby-connections-2-0-simultaneous-connection-requests-fail-on-both-devices](https://stackoverflow.com/questions/63153282/nearby-connections-2-0-simultaneous-connection-requests-fail-on-both-devices)
25. Settings \> Connections \> More Connection Settings \> Nearby Device Scanning; Toggling off
    seems to significant reduction battery drain on my S10e : r/galaxys10 \- Reddit, accessed
    October 20,
    2025, [https://www.reddit.com/r/galaxys10/comments/b3kllu/settings\_connections\_more\_connection\_settings/](https://www.reddit.com/r/galaxys10/comments/b3kllu/settings_connections_more_connection_settings/)
26. Constant nearby devices and location scanning causing battery drain? : r/samsunggalaxy, accessed
    October 20,
    2025, [https://www.reddit.com/r/samsunggalaxy/comments/1kxej49/constant\_nearby\_devices\_and\_location\_scanning/](https://www.reddit.com/r/samsunggalaxy/comments/1kxej49/constant_nearby_devices_and_location_scanning/)
27. In Google Nearby Connections API, is there any way to keep advertise on while the app is off? \-
    Stack Overflow, accessed October 20,
    2025, [https://stackoverflow.com/questions/65376512/in-google-nearby-connections-api-is-there-any-way-to-keep-advertise-on-while-th](https://stackoverflow.com/questions/65376512/in-google-nearby-connections-api-is-there-any-way-to-keep-advertise-on-while-th)
28. possible of using nearby connections api advertising in the background \- Stack Overflow,
    accessed October 20,
    2025, [https://stackoverflow.com/questions/70880678/possible-of-using-nearby-connections-api-advertising-in-the-background](https://stackoverflow.com/questions/70880678/possible-of-using-nearby-connections-api-advertising-in-the-background)
29. Error codes in Nearby Connections 2.0 \- android \- Stack Overflow, accessed October 20,
    2025, [https://stackoverflow.com/questions/46036191/error-codes-in-nearby-connections-2-0](https://stackoverflow.com/questions/46036191/error-codes-in-nearby-connections-2-0)