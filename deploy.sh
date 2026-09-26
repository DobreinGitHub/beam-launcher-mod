#!/bin/sh
# Build the release APK and install it on the projector.
#   DEVICE  projector address, e.g. 192.168.1.50 (port 5555 is added if missing)
#   ADB     adb binary (default: adb from PATH)
# Both can go into local.env (KEY=value lines, git-ignored) instead of the environment;
# JAVA_HOME and ANDROID_HOME can too, or the SDK path goes into local.properties as usual.
set -e
cd "$(dirname "$0")"
if [ -f local.env ]; then set -a; . ./local.env; set +a; fi
[ -n "$DEVICE" ] || { echo "Set DEVICE (projector IP), e.g. DEVICE=192.168.1.50 ./deploy.sh or in local.env" >&2; exit 1; }
case "$DEVICE" in *:*) ;; *) DEVICE="$DEVICE:5555" ;; esac
ADB="${ADB:-adb}"
# --stubs: also install the remote-button stubs; --no-start: install quietly, don't bring Beam to the front.
STUBS=; START=1
for arg in "$@"; do case "$arg" in --stubs) STUBS=1 ;; --no-start) START= ;; esac; done
TASK=":app:assembleRelease"
# The Gradle daemon sometimes keeps classes.dex locked on Windows; restart it and retry once.
./gradlew "$TASK" --console=plain -q || { ./gradlew --stop -q; ./gradlew "$TASK" --console=plain -q; }
"$ADB" connect "$DEVICE" >/dev/null || true
"$ADB" -s "$DEVICE" install -r "app/build/outputs/apk/release/app-release.apk"
if [ -n "$STUBS" ]; then
    ./gradlew :stub:assembleRelease --console=plain -q
    for apk in stub/build/outputs/apk/*/release/*.apk; do
        "$ADB" -s "$DEVICE" install -r "$apk"
    done
fi
[ -n "$START" ] && "$ADB" -s "$DEVICE" shell am start -n com.home.tiles/.MainActivity
true
