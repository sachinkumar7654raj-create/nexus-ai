#!/data/data/com.termux/files/usr/bin/bash
# Builds the debug APK and copies it to your Download folder. Run:  bash build-termux.sh
# If the Android SDK is not installed yet, it runs termux-setup.sh first.
set -e
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-$PREFIX/lib/jvm/java-17-openjdk}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
if [ ! -d "$ANDROID_HOME/platforms/android-34" ] || ! command -v java > /dev/null 2>&1; then
  echo "==> Build tools missing - running setup first..."
  bash ./termux-setup.sh
fi
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
chmod +x gradlew
echo "==> Building (first run downloads Gradle + libraries; can take 10-30 minutes). Close other apps to free RAM."
./gradlew assembleDebug --no-daemon --console=plain "$@"
APK="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
  echo "==> BUILD SUCCESSFUL: $APK"
  if [ -d "$HOME/storage/downloads" ]; then
    cp "$APK" "$HOME/storage/downloads/NexusAI-v2-debug.apk"
    echo "Copied to your phone's Download folder as NexusAI-v2-debug.apk. Open it with a file manager to install."
  else
    echo "Run 'termux-setup-storage' once (allow access), then re-run this script to copy the APK to Downloads."
  fi
else
  echo "APK not found - see errors above."; exit 1
fi
