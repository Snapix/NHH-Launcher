#!/usr/bin/env bash
set -euo pipefail

# Winlator runtime source is pinned as a Git submodule.

WINLATOR_ROOT="winlator-app"
WINLATOR_APP="$WINLATOR_ROOT/app"
NHH_SOURCE="app/src/main/java/com/snapix/nhhlauncher"

if [[ ! -d "$WINLATOR_APP" ]]; then
  echo "Winlator submodule is missing. Run: git submodule update --init --recursive"
  exit 1
fi

# Build the upstream Winlator app itself. This is intentional: Winlator is an
# application-sized runtime with native libraries, assets and resources. Treating
# it as a library breaks its resource IDs and native packaging.
python3 - "$WINLATOR_APP/build.gradle" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()

if "apply plugin: 'org.jetbrains.kotlin.android'" not in s:
    s = s.replace(
        "apply plugin: 'com.android.application'",
        "apply plugin: 'com.android.application'\napply plugin: 'org.jetbrains.kotlin.android'",
        1
    )

s = s.replace("compileSdk 35", "compileSdk 35")
s = s.replace(
    "android {\n",
    """android {
    buildFeatures {
        compose true
    }
    composeOptions {
        kotlinCompilerExtensionVersion '1.5.14'
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = '17'
    }
""",
    1
)

needle = "dependencies {"
if "androidx.activity:activity-compose:1.9.3" not in s:
    s = s.replace(
        needle,
        """dependencies {
    implementation 'androidx.activity:activity-compose:1.9.3'
    implementation 'androidx.compose.ui:ui:1.7.8'
    implementation 'androidx.compose.ui:ui-tooling-preview:1.7.8'
    implementation 'androidx.compose.material3:material3:1.3.2'
    implementation 'androidx.compose.material:material-icons-extended:1.7.8'
""",
        1
    )

p.write_text(s)
PY

# Copy the launcher/runtime sources into the real Winlator application module.
mkdir -p "$WINLATOR_APP/src/main/java/com/snapix/nhhlauncher"
cp "$NHH_SOURCE/MainActivity.kt" "$WINLATOR_APP/src/main/java/com/snapix/nhhlauncher/MainActivity.kt"
cp "$NHH_SOURCE/WinlatorRuntime.kt" "$WINLATOR_APP/src/main/java/com/snapix/nhhlauncher/WinlatorRuntime.kt"

# NHH's launcher subclasses Winlator MainActivity only to reuse its real
# bootstrap APIs. The guard prevents the upstream launcher UI from running first.
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

# Make NHH Launcher the only launcher activity while keeping Winlator's native
# MainActivity available for its full settings screens.
MANIFEST="$WINLATOR_APP/src/main/AndroidManifest.xml"
python3 - "$MANIFEST" <<'PY'
from pathlib import Path
import re
import sys
p = Path(sys.argv[1])
s = p.read_text()

pattern = re.compile(
    r'\s*<activity android:name="com\.winlator\.MainActivity"(?P<body>.*?)</activity>',
    re.S
)
m = pattern.search(s)
if not m:
    raise SystemExit("Could not locate Winlator MainActivity in manifest")

body = m.group('body')
body = re.sub(r'\s*<intent-filter>\s*<action android:name="android\.intent\.action\.MAIN"/>\s*<category android:name="android\.intent\.category\.LAUNCHER"/>\s*</intent-filter>', '', body)
body = body.replace('android:exported="true"', 'android:exported="false"', 1)

replacement = '''        <activity android:name="com.winlator.MainActivity"
            android:theme="@style/AppThemeDark"
            android:exported="false"
            android:screenOrientation="sensor"
            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation" />

        <activity android:name="com.snapix.nhhlauncher.MainActivity"
            android:theme="@style/AppThemeDark"
            android:exported="true"
            android:screenOrientation="sensor"
            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>'''
s = s[:m.start()] + "\n" + replacement + s[m.end():]
p.write_text(s)
PY

echo "Prepared the full Winlator application with the NHH launcher front-end."
