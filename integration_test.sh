#!/bin/bash

# Exit immediately if a command exits with a non-zero status.
set -e

# Define paths and variables.
# Use ANDROID_HOME if it's set, otherwise default to a common location.
SDK_ROOT="${ANDROID_HOME:-/home/jules/Android/sdk}"
ADB_PATH="$SDK_ROOT/platform-tools/adb"
EMULATOR_PATH="$SDK_ROOT/emulator/emulator"
AVD_NAME="arm_avd"
animations=("disco" "eye" "motion" "equalizer" "snakes" "zap")

# --- Helper Functions ---

# Function to print a formatted success message.
print_success() {
    echo "✅ SUCCESS: $1"
}

# Function to print a formatted error message and exit.
print_error() {
    echo "❌ ERROR: $1" >&2
    # The trap will handle emulator shutdown.
    exit 1
}

# --- Emulator and Cleanup Logic ---

# Function to shut down the emulator.
shutdown_emulator() {
    echo "Shutting down emulator..."
    $ADB_PATH emu kill || echo "Emulator was not running."
}

# Set a trap to ensure the emulator is shut down on script exit (normal or error).
trap shutdown_emulator EXIT

# --- Main Test Logic ---

echo "Starting integration test for web animations..."

# 1. Launch the emulator in the background.
echo "Launching emulator '$AVD_NAME'..."
# Use -no-window for headless execution.
$EMULATOR_PATH -avd $AVD_NAME -no-window > /dev/null 2>&1 &

# 2. Wait for the emulator to boot completely.
echo "Waiting for emulator to boot..."
$ADB_PATH wait-for-device
echo "Emulator is online. Waiting for boot to complete..."
until [[ "$($ADB_PATH shell getprop sys.boot_completed 2>/dev/null)" == "1" ]]; do
  echo "Still waiting for boot..."
  sleep 2
done
echo "Emulator has fully booted."

# 3. Build and install the app.
echo "Building and installing the application..."
./gradlew -p app assembleDebug
$ADB_PATH install -r -g app/build/outputs/apk/debug/app-debug.apk

# 4. Start the app automatically.
echo "Starting the application with auto_start hook..."
$ADB_PATH shell am start -n info.benjaminhill.localmesh/.MainActivity --ez auto_start true
sleep 10 # Wait for the service and web server to start.

# 5. Forward the device port.
echo "Forwarding device port 8099..."
$ADB_PATH forward tcp:8099 tcp:8099

# 6. Loop through each animation and test it.
for anim in "${animations[@]}"; do
    echo "----------------------------------------"
    echo "Testing animation: $anim"

    # Clear logcat.
    echo "Clearing logcat..."
    $ADB_PATH logcat -c

    # Send the 'display' command.
    echo "Sending 'display' command for '$anim'..."
    curl -s -X GET "http://localhost:8099/display?path=$anim&sourceNodeId=test-node"

    # Wait for the WebView to load.
    echo "Waiting for animation to initialize..."
    sleep 10

    # Check for the success message.
    echo "Checking for success log..."
    if $ADB_PATH logcat -d | grep -q "LOCALMESH_SCRIPT_SUCCESS:$anim"; then
        print_success "Found success log for '$anim'."
    else
        echo "----------------- LOGCAT OUTPUT -----------------"
        $ADB_PATH logcat -d -t 100
        echo "-------------------------------------------------"
        print_error "Success log for '$anim' not found."
    fi
done

# --- Final Cleanup ---
$ADB_PATH forward --remove tcp:8099

print_success "All animations tested successfully!"
