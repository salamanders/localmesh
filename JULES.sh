# Setup Android development environment for jules

wget -q https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -O /tmp/tools.zip
unzip /tmp/tools.zip -d /tmp/tools
mkdir -p ~/Android/sdk/cmdline-tools/latest
mv /tmp/tools/cmdline-tools/* ~/Android/sdk/cmdline-tools/latest
rm -rf /tmp/tools
rm /tmp/tools.zip

export ANDROID_SDK_ROOT="$HOME/Android/sdk"
export PATH="$PATH:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin"
export PATH="$PATH:$ANDROID_SDK_ROOT/platform-tools"

sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.1.0"
yes | sdkmanager --licenses
