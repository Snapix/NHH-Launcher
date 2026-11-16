#!/usr/bin/env bash
set -euo pipefail

WINLATOR_APP="winlator-app/app"

if [[ ! -d "$WINLATOR_APP" ]]; then
  echo "Winlator submodule is missing. Run: git submodule update --init --recursive"
  exit 1
fi

# Winlator upstream is an Android application. NHH Launcher consumes its complete
# runtime as an Android library so its Java/native code, assets and resources are
# packaged into the final NHH Launcher APK.
sed -i "s/apply plugin: 'com.android.application'/apply plugin: 'com.android.library'/" "$WINLATOR_APP/build.gradle"
sed -i "/^[[:space:]]*applicationId /d" "$WINLATOR_APP/build.gradle"
sed -i "s/classpath 'com.android.tools.build:gradle:8.4.2'/classpath 'com.android.tools.build:gradle:8.13.2'/" "winlator-app/build.gradle"
sed -i "s/compileSdk 35/compileSdk 36/" "$WINLATOR_APP/build.gradle"

echo "Winlator runtime prepared as Android library."
