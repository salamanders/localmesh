# Physical Device Test Plan

This plan outlines the steps to verify the P2P mesh network functionality on two physical Android
devices.

Start by reading all *.md and @.kt files for context, there aren't many of them.

## RULES

1. Never try to get the full unfiltered logcat, it will be much too large to fit into context.
   Always use a filter (either the localmesh package, or Exceptions and Warnings)
2. You often get confused by empty logs when you try to download the log to a temp file.  It is almost always better to clear the logcat, re-try the action, get the **filtered** log directly.
3. Never use hacks like replacing logging with 'System.out' or reverting to 'Log.e'
4. Never make code changes based on guesswork. Often the LLM will say "I think it is reason X, so
   I'm going to refactor things" and it turns out to not be reason X. Always make a list of
   possibilities and how you check if it is true BEFORE attempting to fix the bug.
5. Keep logging. While debugging the LLM will often add then immediately remove logging. If the
   logging won't completely flood the logcat, leave it in.

## Logging Debugging

If logs are not appearing as expected, do not assume the logging framework is broken. Instead,
follow this systematic process of elimination:

1. **Verify the app is running:** Use `adb shell pidof <package_name>` or
   `adb shell ps -A | grep <package_name>` to confirm the app process is active.
2. **Check for crashes:** Use `adb logcat -d -b crash` to check for any fatal exceptions that might
   be causing the app to crash before it can log.
3. **Broaden the logcat filter:** Start with a broad filter like the package name (
   `adb logcat -d -s <package_name>`) and then gradually narrow it down to specific tags.
4. **Check logcat buffer size:** Use `adb logcat -g` to check the buffer size and
   `adb logcat -G <size>` to increase it if necessary.
5. **Check for device-specific logging issues:** If all else fails, halt and ask the user. Do not
   start refactoring the logging system.

## Devices

Start the physical debugging session with `adb devices` to get the latest list of "device IDs" to
use in the rest of the steps.

## Test Steps

1. Build the debug APK
2. Install the APK on both devices
3. Start the app on both devices using the `auto_start` intent extra
4. Forward the port for one device to test API communication
5. Check connections
    1. Check the status of the first device to verify connection (the result of the status page)
       contains `"peerCount":N`.
    2. If the peer count is anything other than "total devices - 1" this is THE MOST CRITICAL THING
       TO DIAGNOSE. Don't move on until we have figured out why the network isn't fully connecting.
6. Check chat
    1. Clear the logcat on the second device
    2. Send a chat message from the first device
    3. Verify the chat message was received on the second device
7. Check display
    1. Clear the logcat on the second device (again)
    2. Trigger a remote display from the first device to the second
    3. Verify the `DisplayActivity` was launched on the second device. look for a log entry
       indicating the `DisplayActivity` was started with the "eye" path.
8. Check file transfer
    1. Create a dummy file named `dummy_file.txt` on your host machine with content "Hello from transferred file!".
    2. Push this `dummy_file.txt` to the first device's `/data/data/info.benjaminhill.localmesh/files/web/` directory.
    3. Clear the logcat on the second device.
    4. Send the `dummy_file.txt` from the first device using a `curl` command that simulates a multipart form data upload to the `/send-file` endpoint, specifying `destinationPath=dummy_file.txt`.
    5. Verify the file was received on the second device by checking its logcat for the message "File received and saved: dummy_file.txt".
    6. Finally, use `curl` to request `http://localhost:8099/dummy_file.txt` from the second device's local HTTP server and confirm its content matches "Hello from transferred file!".

Testing the forming of the mesh network. (fill in device IDs)

```bash
./gradlew assembleDebug
adb -s {each_device_id} install -r -g app/build/outputs/apk/debug/app-debug.apk
adb -s {each_device_id} shell am start -n info.benjaminhill.localmesh/.MainActivity --ez auto_start true
adb -s {first_device_id} forward tcp:8099 tcp:8099
sleep 30
curl http://localhost:8099/status
adb -s {second_device_id} logcat -c
curl -X POST -d "message=Hello from device 1" http://localhost:8099/chat
sleep 2
adb -s {second_device_id} logcat -d -s "LocalHttpServer"
adb -s {second_device_id} logcat -c
```

Testing the display command being executed on all other devices

```bash
curl -X GET "http://localhost:8099/display?path=eye"
sleep 2
adb -s {second_device_id} logcat -d -s "DisplayActivity"
```

Testing file transfers.

```bash
echo "Hello from transferred file!" > dummy_file.txt
adb -s {first_device_id} push dummy_file.txt /data/data/info.benjaminhill.localmesh/files/web/dummy_file.txt
adb -s {second_device_id} logcat -c
curl -X POST -H "Content-Type: multipart/form-data" -F "file=@dummy_file.txt" http://localhost:8099/send-file?destinationPath=dummy_file.txt
sleep 2
adb -s {second_device_id} logcat -d -s "BridgeService"
curl http://localhost:8099/dummy_file.txt
rm dummy_file.txt
```

When the bug is fully resolved and the user says there is no more testing to do, clean up:

```bash
adb forward --remove-all
```

## Debugging Notes: `UninitializedPropertyAccessException`

### Discovery

During the initial execution of the test plan, the `curl http://localhost:8099/status` command
failed with an "Empty reply from server". Upon inspecting the `logcat` for Device 1, a
`kotlin.UninitializedPropertyAccessException: lateinit property topologyOptimizer has not been initialized`
error was found in `BridgeService.onCreate()`.

The relevant log entries were:

```
10-17 14:02:26.461  9449  9449 E BridgeService: Exception caught: lateinit property topologyOptimizer has not been initialized
10-17 14:02:26.461  9449  9449 E BridgeService: kotlin.UninitializedPropertyAccessException: lateinit property topologyOptimizer has not been initialized
...
10-17 14:02:26.461  9449  9449 E BridgeService: FATAL: Service crashed on create.
```

This indicated a circular dependency issue: `NearbyConnectionsManager` was being initialized and
passed `topologyOptimizer` as a callback, but `topologyOptimizer` itself had not yet been
initialized.

### Fix

To resolve this, the following changes were made:

1. **Modified `TopologyOptimizer.kt`:** The constructor was updated to accept a
   `connectionsManagerProvider: () -> NearbyConnectionsManager` lambda instead of a direct
   `NearbyConnectionsManager` instance. This allows for lazy access to the
   `NearbyConnectionsManager` once it's initialized.
2. **Updated `TopologyOptimizer.kt` usage:** All internal calls to `connectionsManager` within
   `TopologyOptimizer` were updated to use `connectionsManagerProvider().` to invoke the lambda and
   get the `NearbyConnectionsManager` instance.
3. **Reordered initialization in `BridgeService.kt`:** The initialization order within
   `BridgeService.onCreate()` was reversed. `topologyOptimizer` is now initialized first, receiving
   the `connectionsManagerProvider` lambda. Then, `nearbyConnectionsManager` is initialized,
   receiving the now-initialized `topologyOptimizer` as its callback.

This ensures that `topologyOptimizer` is fully constructed before `nearbyConnectionsManager`
attempts to use it, breaking the circular dependency and resolving the
`UninitializedPropertyAccessException`.

## KNOWN ISSUES

* **File Transfer UI Gap:** The `GEMINI.md` documentation explicitly describes a file transfer flow initiated from a file input on the `index.html` page. However, the `index.html` file in the codebase does not contain a file input element, making it impossible to test the file transfer functionality as described. This is a gap between the documentation and the implementation.

* **Inconsistent Peer Counts and Connection Errors:** After enabling Bluetooth on Device 2, the peer
  count on Device 1 is still 2 (instead of 3). Detailed logcat analysis reveals inconsistent
  connection behavior across devices:
    * **Device 1 (BNU2):** Connected to 2 peers (`A6ZC`, `6KR3`). Failed to connect to others with
      `STATUS_ENDPOINT_IO_ERROR` and `EOFException`.
    * **Device 2 (RSWD):** Connected to 4 peers (`A6ZC`, `UG70`, `6KR3`, `BNU2`).
    * **Device 3 (CW5U):** Connected to 2 peers (`FWCS`, `CNQY`). Failed to connect to others with
      `STATUS_ENDPOINT_IO_ERROR` and `EOFException`.
    * **Device 4 (CNQY):** Connected to 3 peers (`A6ZC`, `CW5U`, `BNU2`). Failed to connect to
      others with `STATUS_ENDPOINT_IO_ERROR` and `EOFException`.
      This indicates that the `NearbyConnectionsManager`'s logic for managing `TARGET_CONNECTIONS` (
      3) and `MAX_CONNECTIONS` (4) is not robust, leading to some devices exceeding the target while
      others fall short. The recurring `EOFException` and `STATUS_ENDPOINT_IO_ERROR` suggest
      underlying issues with the Nearby Connections API or its interaction with the network
      conditions, possibly exacerbated by the connection management logic.
* **Bluetooth Disabled on Device 2 (98281FFBA003TM):** The logcat for device `98281FFBA003TM` shows
  `E BridgeService: Bluetooth is not enabled`. This prevents the `BridgeService` from starting
  correctly on this device, explaining why it is not participating in the Nearby Connections
  network.
* **ForegroundServiceStartNotAllowedException Crash:** The app is crashing with
  `android.app.ForegroundServiceStartNotAllowedException` when the `ServiceHardener` attempts to
  restart the `BridgeService`. This occurs because Android disallows starting a foreground service
  when the app is in the background. This crash is likely preventing proper peer discovery and
  communication, leading to the low peer count and subsequent failures in chat and display
  functionality.
    * **Theory:** The `ServiceHardener` is too aggressive in its checks and is attempting to restart
      the `BridgeService` even when it's not strictly necessary, or when the app is not in a state
      where a foreground service can be started. This leads to a cycle of crashes and restarts,
      preventing the app from functioning correctly.

## 2025-10-17 Four-Device Test

A test was conducted with four devices: `15141FDD40035D`, `98281FFBA003TM`, `99151FFAZ001CM`, and
`99201FFAZ0020W`.

### Initial Status

* Device `15141FDD40035D` reported a `peerCount` of 2, indicating it was not connected to all other
  devices.

### Key Findings

* **Bluetooth Disabled on Device 2:** Logcat analysis of device `98281FFBA003TM` revealed the error
  `E BridgeService: Bluetooth is not enabled`. This is the primary cause for the incomplete mesh, as
  this device cannot participate in Nearby Connections.
* **Widespread Connection Errors:** All four devices showed numerous errors related to the Nearby
  Connections API, including `STATUS_RADIO_ERROR`, `STATUS_ENDPOINT_IO_ERROR`, and
  `java.io.EOFException`. This points to a fundamental instability in the connection layer.
* **Redundant Connection Attempts:** Multiple devices logged `STATUS_ALREADY_CONNECTED_TO_ENDPOINT`
  errors, suggesting a flaw in the connection management logic where the app attempts to reconnect
  to already-established peers.

### Next Steps

1. **Enable Bluetooth on Device `98281FFBA003TM`**. This is a manual action required by the user.
2. Re-run the test to verify if the peer count increases and to further diagnose the underlying
   connection instability.

## 2025-10-18 Five-Device Test

A test was conducted with five devices: `15141FDD40035D`, `98281FFBA003TM`, `99151FFAZ001CM`, `99201FFAZ0020W`, and `9B191FFAZ0047Z`.

### Initial Status

* Device `15141FDD40035D` (ID: `Ayh5c`) reported a `peerCount` of 4, indicating it was connected to all other devices. The peer IDs were `AHOU`, `3LE0`, `SB6H`, `FI8A`.

### Key Findings

* **Successful Mesh Formation:** After increasing the wait time to 30 seconds, the primary device successfully connected to all other four devices, forming a complete mesh from its perspective.

### Next Steps

1. Proceed with checking chat, display, and file transfer functionality.