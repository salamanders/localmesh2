# Setup Android development environment for jules
# IMPORTANT: If this is NOT being run inside jules.google.com, (e.g. being run from gemini-cli): this can be skipped and instead run `./gradlew assembleDebug` in the `android` folder.
# This should be run once from https://jules.google.com/repo/github/{YOUR USERNAME}/{YOUR REPO}/config

set -e

# Only download and set up if the SDK directory doesn't exist.
if [ ! -d "$HOME/Android/sdk" ]; then
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
else
    echo "Android SDK already exists. Skipping download and setup."
fi

# Create local.properties if it doesn't exist
if [ ! -f "local.properties" ]; then
    echo "sdk.dir=$HOME/Android/sdk" > local.properties
fi

# Add properties to gradle.properties if they don't already exist
if ! grep -q "android.useAndroidX" gradle.properties; then
    echo "android.useAndroidX=true" >> gradle.properties
fi

if ! grep -q "android.enableJetifier" gradle.properties; then
    echo "android.enableJetifier=true" >> gradle.properties
fi

if ! grep -q "org.gradle.jvmargs" gradle.properties; then
    echo "org.gradle.jvmargs=-Xmx2048m" >> gradle.properties
fi