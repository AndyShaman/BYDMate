#!/bin/bash
# Compile WriteProbe.java -> probe.dex via javac + d8.
# Output: scripts/native-stack/write-probe/probe.dex
set -eu

ANDROID_JAR="${ANDROID_JAR:-$HOME/Library/Android/sdk/platforms/android-34/android.jar}"
D8="${D8:-$HOME/Library/Android/sdk/build-tools/34.0.0/d8}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

cd "$SCRIPT_DIR"
rm -rf build classes.dex probe.dex
mkdir -p build/com/bydmate/probe

javac -source 1.8 -target 1.8 \
      -bootclasspath "$ANDROID_JAR" \
      -d build \
      WriteProbe.java

"$D8" --min-api 29 \
      --output . \
      build/com/bydmate/probe/WriteProbe.class

mv classes.dex probe.dex
rm -rf build

ls -la probe.dex
echo "OK probe.dex built."
