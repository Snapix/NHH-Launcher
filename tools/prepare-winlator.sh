#!/usr/bin/env bash
set -euo pipefail

# Winlator runtime source is pinned as a Git submodule.

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

MAIN_ACTIVITY="$WINLATOR_APP/src/main/java/com/winlator/MainActivity.java"
python3 - "$MAIN_ACTIVITY" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
needle = "        super.onCreate(savedInstanceState);\n"
guard = "        if (getIntent().getBooleanExtra(\"nhh_launcher\", false)) return;\n"
if guard not in s:
    if needle not in s:
        raise SystemExit("Could not locate Winlator MainActivity onCreate")
    s = s.replace(needle, needle + guard, 1)
    p.write_text(s)
PY

echo "Winlator runtime prepared as Android library."
