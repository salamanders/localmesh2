#!/bin/bash
set -e
echo "Building the application..."
./gradlew assembleDebug

echo "Getting connected devices..."
DEVICES=$(adb devices | tail -n +2 | cut -f1)

if [ -z "$DEVICES" ]; then
    echo "No devices found."
    exit 1
fi

for DEVICE in $DEVICES; do
    (
        echo "Clearing logcat on device: $DEVICE"
        adb -s $DEVICE logcat -c
        echo "Deploying to device: $DEVICE"
        adb -s $DEVICE install -r -g app/build/outputs/apk/debug/app-debug.apk
        adb -s $DEVICE shell am start -n info.benjaminhill.localmesh2/.MainActivity
    ) &
done

wait
echo "Deployment to all devices complete."