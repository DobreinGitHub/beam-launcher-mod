#!/bin/sh
# Build the release APK and install it on the projector.
set -e
export JAVA_HOME="C:/android/jdk" ANDROID_HOME="C:/android/sdk"
DEVICE="${DEVICE:-192.168.1.76:5555}"
# --stubs: also install the remote-button stubs; --no-start: install quietly, don't bring Beam to the front.
STUBS=; START=1
for arg in "$@"; do case "$arg" in --stubs) STUBS=1 ;; --no-start) START= ;; esac; done
TASK=":app:assembleRelease"
# The Gradle daemon sometimes keeps classes.dex locked on Windows; restart it and retry once.
./gradlew "$TASK" --console=plain -q || { ./gradlew --stop -q; ./gradlew "$TASK" --console=plain -q; }
/c/adb/adb.exe -s "$DEVICE" install -r "app/build/outputs/apk/release/app-release.apk"
if [ -n "$STUBS" ]; then
    ./gradlew :stub:assembleRelease --console=plain -q
    for apk in stub/build/outputs/apk/*/release/*.apk; do
        /c/adb/adb.exe -s "$DEVICE" install -r "$apk"
    done
fi
[ -n "$START" ] && /c/adb/adb.exe -s "$DEVICE" shell am start -n com.home.tiles/.MainActivity
true
