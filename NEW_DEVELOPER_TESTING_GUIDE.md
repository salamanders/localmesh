# New Developer Testing Guide

This guide contains two tests for our mesh network.

**Your only job is to follow these steps and report the results. Do not try to fix the code.**

---

## Part A: Two-Device "Simple Connection" Test

This first test is a simple check to make sure two devices can connect to each other.

### If you get stuck

If something goes wrong, **do not change any code.** Instead, do this:

1. **Stop the app on both devices.**
2. **Uninstall the app from both devices.**
3. **Start over from Part A, Step 1.**
4. If it fails again, use the Troubleshooting steps below and record the log output as described in
   the Reporting section.

### Running the Test

You will need two physical Android devices. We will call them **Device 1** and **Device 2**.

**IMPORTANT:** You must replace `{device_1_id}` and `{device_2_id}` in the commands below with the
actual device IDs. Find them by running `adb devices`.

#### Step 1: Install the app

Run these commands from your computer's terminal.

```bash
# Build the test version of the app
./gradlew assembleDebug

# Install on Device 1
adb -s {device_1_id} install -r -g app/build/outputs/apk/debug/app-debug.apk

# Install on Device 2
adb -s {device_2_id} install -r -g app/build/outputs/apk/debug/app-debug.apk
```

#### Step 2: Start the app

```bash
# Start on Device 1
adb -s {device_1_id} shell am start -n info.benjaminhill.localmesh/.MainActivity --ez auto_start true

# Start on Device 2
adb -s {device_2_id} shell am start -n info.benjaminhill.localmesh/.MainActivity --ez auto_start true
```

#### Step 3: Check the connection

```bash
# Set up a connection to Device 1
adb -s {device_1_id} forward tcp:8099 tcp:8099

# Wait 30 seconds for the devices to find each other
sleep 30

# Ask Device 1 how many devices it sees
curl http://localhost:8099/status
```

#### Step 4: Verify the result

Look at the output. You should see `{"peerCount":1, ...}`.

* If you see `"peerCount":1`, the test is a **SUCCESS**.
* If you see `"peerCount":0` or an error, the test has **FAILED**.

---

## Part B: Multi-Device "Well-Connected" Test

This test checks that a larger group of devices (3 to 20) forms a healthy, stable mesh network.

### Understanding the Goal

We are testing the "well-connected" strategy. This means each device will **not** connect to every
other device. Instead, it will try to maintain about **3 connections** to form an efficient mesh.

So, a `peerCount` of 3 is a **good thing**, even if there are 10 devices in the test.

### Running the Test

You will need at least 3 physical Android devices.

#### Step 1: Get all device IDs

Run `adb devices` and make a list of all your device IDs.

#### Step 2: Install and start the app on ALL devices

For each device ID in your list, run the following commands.

```bash
# Replace {device_id} with the real ID
export DEVICE_ID={device_id}

# Install the app
adb -s $DEVICE_ID install -r -g app/build/outputs/apk/debug/app-debug.apk

# Start the app
adb -s $DEVICE_ID shell am start -n info.benjaminhill.localmesh/.MainActivity --ez auto_start true
```

#### Step 3: Check the connection status on EACH device

Wait about **2 minutes** for the network to form. Then, for each device, check its status.

```bash
# Replace {device_id} with the real ID
export DEVICE_ID={device_id}

# Set up connection to the device
adb -s $DEVICE_ID forward tcp:8099 tcp:8099

# Check its status
echo "Status for device $DEVICE_ID:"
curl http://localhost:8099/status

# Clean up the connection for the next device
adb forward --remove-all
```

#### Step 4: Verify the result

Look at the output for each device.

* The test is a **SUCCESS** if most devices report a `peerCount` between 2 and 4.
* The test is a **FAILURE** if many devices have a `peerCount` of 0 or 1.

---

## Troubleshooting

If a test fails, these commands can help you figure out why. Run them on a device that is having
problems.

* **Has the app crashed?**
  ```bash
  # Replaces {device_id} with the real ID
  adb -s {device_id} logcat -d -b crash
  ```
  *This will show you if the app has crashed, which is a common reason for failure.*

* **Is the app running?**
  ```bash
  # Replaces {device_id} with the real ID
  adb -s {device_id} shell pidof info.benjaminhill.localmesh
  ```
  *If this command returns a number (a process ID), the app is running. If it returns nothing, the
  app is not running.*

* **Get the app's logs:**
  ```bash
  # Replaces {device_id} with the real ID
  adb -s {device_id} logcat -d -s "info.benjaminhill.localmesh"
  ```
  *This gets only the logs from our specific app, which is useful for focused debugging.*

## How to Report Your Results

1. Create a new text file named `testing_notes.txt`.
2. Copy the template below into the file and fill it out.

> ## Testing Report
> **Date:** YYYY-MM-DD
> **Which test did you run?** (Part A or Part B)
> **What was the result?** (SUCCESS or FAILED)
> ### Test Details
> **For Part A, list the two device IDs:**
> - Device 1:
> - Device 2:
    > **For Part B, list all device IDs:**
> -
> ### Failure Information (if the test failed)
> **Copy the output of the `curl ... /status` command here:**
> ```(paste status output here)```
> **Copy the output of the Troubleshooting commands here.**
> **Crash Log (`logcat -d -b crash`):**
> ``` (paste crash log here, or write "No crash") ```
> **App-specific Log (`logcat -d -s "info.benjaminhill.localmesh"`):**
> ```(paste app log here)```
> ### Final Notes
> (Add any other observations here. For example: "Device 2's screen was off.")

## Future Testing Strategies

Once you are comfortable with the connection tests, you can try these more advanced tests. These are
for a two-device setup (Device 1 and Device 2).

### Test 1: Chat

This test verifies that messages can be sent from one device to another.

1. **Forward the port for Device 1:**
   ```bash
   adb -s {device_1_id} forward tcp:8099 tcp:8099
   ```
2. **Clear the logs on Device 2:**
   ```bash
   adb -s {device_2_id} logcat -c
   ```
3. **Send a chat message from Device 1:**
   ```bash
   curl -X POST -d "message=Hello from device 1" http://localhost:8099/chat
   ```
4. **Check the logs on Device 2 for the message:**
   ```bash
   # Look for "Received chat message"
   adb -s {device_2_id} logcat -d -s "LocalHttpServer"
   ```

### Test 2: Remote Display

This test verifies that one device can trigger a command on another.

1. **Clear the logs on Device 2:**
   ```bash
   adb -s {device_2_id} logcat -c
   ```
2. **Send the display command from Device 1:**
   ```bash
   curl -X GET "http://localhost:8099/display?path=eye"
   ```
3. **Check the logs on Device 2 to see if the command was received:**
   ```bash
   # Look for "DisplayActivity started"
   adb -s {device_2_id} logcat -d -s "DisplayActivity"
   ```

### Test 3: File Transfer

This test verifies that a file can be sent from one device and received by another.

1. **Create a test file on your computer:**
   ```bash
   echo "Hello from transferred file!" > dummy_file.txt
   ```
2. **Push the file to Device 1:**
   ```bash
   adb -s {device_1_id} push dummy_file.txt /data/data/info.benjaminhill.localmesh/files/web/dummy_file.txt
   ```
3. **Clear the logs on Device 2:**
   ```bash
   adb -s {device_2_id} logcat -c
   ```
4. **Send the file from Device 1:**
   ```bash
   curl -X POST -H "Content-Type: multipart/form-data" -F "file=@dummy_file.txt" http://localhost:8099/send-file?destinationPath=dummy_file.txt
   ```
5. **Check the logs on Device 2 to confirm receipt:**
   ```bash
   # Look for "File received and saved"
   adb -s {device_2_id} logcat -d -s "BridgeService"
   ```
6. **Verify the file content on Device 2:**
   ```bash
   # Set up port forwarding for Device 2
   adb -s {device_2_id} forward tcp:8099 tcp:8099
   # Request the file
   curl http://localhost:8099/dummy_file.txt
   # The output should be "Hello from transferred file!"
   ```
7. **Clean up:**
   ```bash
   rm dummy_file.txt
   adb forward --remove-all
   ```

Thank you for your help!
