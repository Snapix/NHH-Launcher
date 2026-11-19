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
    implementation 'androidx.compose.material3:material3:1.4.0'
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
cp "$NHH_SOURCE/LauncherSettingsScreen.kt" "$WINLATOR_APP/src/main/java/com/snapix/nhhlauncher/LauncherSettingsScreen.kt"

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

# Fix an upstream threading bug exposed by embedding Winlator. RootFSInstaller
# extracts on an executor, then its success path constructs ContainerManager,
# whose Handler must be created on an Android Looper.
ROOTFS_INSTALLER="$WINLATOR_APP/src/main/java/com/winlator/xenvironment/RootFSInstaller.java"
python3 - "$ROOTFS_INSTALLER" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
old = """            if (success) {
                rootFS.createRFSVersionFile(LATEST_VERSION);
                resetContainerRFSVersions(activity);
            }
            else AppUtils.showToast(activity, R.string.unable_to_install_system_files);

            dialog.closeOnUiThread();"""
new = """            if (success) {
                // RootFS extraction runs on a worker. ContainerManager creates an
                // Android Handler, so post-install bookkeeping must run on the
                // Activity's Looper.
                activity.runOnUiThread(() -> {
                    rootFS.createRFSVersionFile(LATEST_VERSION);
                    resetContainerRFSVersions(activity);
                    dialog.closeOnUiThread();
                });
            }
            else {
                activity.runOnUiThread(() -> {
                    AppUtils.showToast(activity, R.string.unable_to_install_system_files);
                    dialog.closeOnUiThread();
                });
            }"""
if old not in s:
    raise SystemExit("RootFSInstaller success block not found")
p.write_text(s.replace(old, new, 1))
PY

# Winlator's async container callbacks are created from executor threads during
# bootstrap/container setup. Android's Handler() constructor requires a Looper on
# the current thread, so bind these callbacks explicitly to the main Looper.
CONTAINER_MANAGER="$WINLATOR_APP/src/main/java/com/winlator/container/ContainerManager.java"
python3 - "$CONTAINER_MANAGER" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
if "import android.os.Looper;" not in s:
    s = s.replace("import android.os.Handler;\n", "import android.os.Handler;\nimport android.os.Looper;\n", 1)
s = s.replace("new Handler()", "new Handler(Looper.getMainLooper())")
p.write_text(s)
PY

# Allow the direct exec_path launch route to attach the chosen Winlator
# input-control profile without depending on a .desktop shortcut parser.
XSERVER_DISPLAY="$WINLATOR_APP/src/main/java/com/winlator/XServerDisplayActivity.java"
python3 - "$XSERVER_DISPLAY" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
needle = "        if (MainActivity.DEBUG_MODE) rootView.addView(AppUtils.createDebugMsgTextView(this));"
insert = """        if (shortcut == null) {
            int nhhControlsProfileId = getIntent().getIntExtra("nhh_controls_profile_id", 0);
            if (nhhControlsProfileId > 0) {
                ControlsProfile nhhProfile = inputControlsManager.getProfile(nhhControlsProfileId);
                if (nhhProfile != null) showInputControls(nhhProfile);
            }
        }

"""
if "nhh_controls_profile_id" not in s:
    if needle not in s:
        raise SystemExit("Could not locate Winlator input controls initialization")
    s = s.replace(needle, insert + needle, 1)
p.write_text(s)
PY

echo "Prepared the full Winlator application with the NHH launcher front-end."
