# Physical Device Test Plan

This plan outlines the steps to verify the P2P mesh network functionality on two physical Android
devices.

Start by reading all *.md and @.kt files for context, there aren't many of them.

## RULES

1. Never try to get the full unfiltered logcat, it will be much too large to fit into context.
   Always use a filter (either the localmesh package, or Exceptions and Warnings)
2. Never use hacks like replacing logging with 'System.out' or reverting to 'Log.e'
3. Never make code changes based on guesswork. Often the LLM will say "I think it is reason X, so
   I'm going to refactor things" and it turns out to not be reason X. Always make a list of
   possibilities and how you check if it is true BEFORE attempting to fix the bug.
4. Keep logging. While debugging the LLM will often add then immediately remove logging. If the
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

Start the physical debugging session with `adb devices` to get the latest list of "device IDs" to use in the rest of the steps.

## Test Steps

1. Build the debug APK
2. Install the APK on both devices
3. Start the app on both devices using the `auto_start` intent extra
4. Forward the port for one device to test API communication
5. Check connections
    1. Check the status of the first device to verify connection (the result of the status page)
       contains `"peerCount":1`.
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
    1. Send a file from first device, verify it is in the content of the second device (TODO)

Combine these all into one script, filling in the device IDs.

```bash
./gradlew assembleDebug
adb -s {each_device_id} install -r -g app/build/outputs/apk/debug/app-debug.apk
adb -s {each_device_id} shell am start -n info.benjaminhill.localmesh/.MainActivity --ez auto_start true
adb -s {first_device_id} forward tcp:8099 tcp:8099
sleep 4
curl http://localhost:8099/status
adb -s {second_device_id} logcat -c
curl -X POST -d "message=Hello from device 1" http://localhost:8099/chat
sleep 2
adb -s {second_device_id} logcat -d -s "LocalHttpServer"
adb -s {second_device_id} logcat -c
curl -X GET "http://localhost:8099/display?path=eye"
sleep 2
adb -s {second_device_id} logcat -d -s "DisplayActivity"
```

When the bug is fully resolved and there is no more testing to do, clean up:

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
