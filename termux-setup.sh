#!/data/data/com.termux/files/usr/bin/bash
# One-time setup of the Android build tools inside Termux. Run:  bash termux-setup.sh
set -e
echo "==> Installing packages (JDK 17, aapt2, tools)..."
pkg update -y
pkg install -y openjdk-17 aapt2 wget unzip git

SDK="$HOME/android-sdk"
mkdir -p "$SDK/cmdline-tools"
if [ ! -d "$SDK/cmdline-tools/latest" ]; then
  echo "==> Downloading Android command-line tools..."
  wget -q --show-progress -O "$HOME/cmdtools.zip" https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  unzip -q "$HOME/cmdtools.zip" -d "$SDK/cmdline-tools"
  mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
  rm -f "$HOME/cmdtools.zip"
fi

export ANDROID_HOME="$SDK"
echo "==> Accepting licenses and installing platform 34 + build-tools 34.0.0 (large download)..."
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null || true
"$SDK/cmdline-tools/latest/bin/sdkmanager" "platforms;android-34" "build-tools;34.0.0"

echo "==> Telling Gradle to use Termux's own aapt2 (the downloaded one is for x86 PCs and cannot run on a phone)..."
mkdir -p "$HOME/.gradle"
GP="$HOME/.gradle/gradle.properties"
touch "$GP"
grep -v "aapt2FromMavenOverride" "$GP" > "$GP.tmp" || true
echo "android.aapt2FromMavenOverride=$PREFIX/bin/aapt2" >> "$GP.tmp"
mv "$GP.tmp" "$GP"

echo "sdk.dir=$SDK" > "$(dirname "$0")/local.properties"
grep -q ANDROID_HOME "$HOME/.bashrc" 2>/dev/null || echo "export ANDROID_HOME=$SDK" >> "$HOME/.bashrc"
echo
echo "Setup finished. Now run:  bash build-termux.sh"
