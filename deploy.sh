#!/bin/bash

# This script builds the debug APK and deploys it to all connected devices.

# 1. Build the debug APK
echo "Building the debug APK..."
./gradlew assembleDebug
if [ $? -ne 0 ]; then
    echo "Gradle build failed. Aborting."
    exit 1
fi

# 2. Get the list of connected device serial numbers
# The `tail -n +2` skips the "List of devices attached" header.
# The `cut -f1` extracts the first column (the serial number).
devices=$(adb devices | tail -n +2 | cut -f1)

if [ -z "$devices" ]; then
    echo "No devices found. Please connect a device and enable USB debugging."
    exit 1
fi

echo "Found devices:
$devices"

# 3. Loop through each device to install and start the app
for device in $devices; do
    echo "--- Deploying to device: $device ---"

    # Install the APK, replacing the existing installation and granting all permissions.
    echo "Installing APK..."
    adb -s "$device" install -r -g app/build/outputs/apk/debug/app-debug.apk
    if [ $? -ne 0 ]; then
        echo "Failed to install APK on $device. Skipping."
        continue
    fi

    # Start the main activity with the auto_start flag.
    echo "Starting app..."
    adb -s "$device" shell am start -n info.benjaminhill.localmesh/.MainActivity --ez auto_start true
    if [ $? -ne 0 ]; then
        echo "Failed to start app on $device."
    fi

    echo "--- Deployment to $device complete ---"
done

echo "All deployments finished."
