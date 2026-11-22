#!/usr/bin/env bash
set -euo pipefail

WINLATOR_ROOT="winlator-app"
WINLATOR_APP="$WINLATOR_ROOT/app"
NHH_SOURCE="app/src/main/java/com/snapix/nhhlauncher"

[[ -d "$WINLATOR_APP" ]] || { echo "Winlator runtime is missing"; exit 1; }
[[ -f "$WINLATOR_APP/build.gradle" ]] || { echo "Winlator app/build.gradle is missing"; exit 1; }
[[ -f "$WINLATOR_APP/src/main/java/com/winlator/XServerDisplayActivity.java" ]] || { echo "Winlator XServerDisplayActivity.java is missing"; exit 1; }
grep -q 'versionName "11.2"' "$WINLATOR_APP/build.gradle" || { echo "Unsupported Winlator app source"; exit 1; }

echo "Preparing NHH launcher sources against Winlator 11.2..."

# 1. Enable Kotlin + Compose in the actual Winlator app module.
python3 - "$WINLATOR_APP/build.gradle" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
if "org.jetbrains.kotlin.android" not in s:
    s = s.replace(
        "apply plugin: 'com.android.application'",
        "apply plugin: 'com.android.application'\napply plugin: 'org.jetbrains.kotlin.android'",
        1
    )
if "buildFeatures { compose true }" not in s:
    s = s.replace(
        "android {",
        """android {
    buildFeatures { compose true }
    composeOptions { kotlinCompilerExtensionVersion '1.5.14' }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = '17' }
""",
        1
    )
deps = """    implementation 'androidx.core:core-ktx:1.13.1'
    implementation 'androidx.activity:activity-compose:1.9.3'
    implementation 'androidx.compose.animation:animation:1.7.8'
    implementation 'androidx.compose.ui:ui:1.7.8'
    implementation 'androidx.compose.ui:ui-tooling-preview:1.7.8'
    implementation 'androidx.compose.material3:material3:1.4.0'
    implementation 'androidx.compose.material:material-icons-extended:1.7.8'
"""
if "androidx.activity:activity-compose:1.9.3" not in s:
    s = s.replace("dependencies {", "dependencies {\n" + deps, 1)
p.write_text(s)
PY

# 2. Copy the NHH launcher UI/runtime into the module that is actually built.
mkdir -p "$WINLATOR_APP/src/main/java/com/snapix/nhhlauncher"
cp "$NHH_SOURCE/MainActivity.kt" "$WINLATOR_APP/src/main/java/com/snapix/nhhlauncher/MainActivity.kt"
cp "$NHH_SOURCE/WinlatorRuntime.kt" "$WINLATOR_APP/src/main/java/com/snapix/nhhlauncher/WinlatorRuntime.kt"
cp "$NHH_SOURCE/LauncherSettingsScreen.kt" "$WINLATOR_APP/src/main/java/com/snapix/nhhlauncher/LauncherSettingsScreen.kt"
cp "$NHH_SOURCE/LauncherRuntimeSettings.kt" "$WINLATOR_APP/src/main/java/com/snapix/nhhlauncher/LauncherRuntimeSettings.kt"
cp "$NHH_SOURCE/NhhControllerRuntime.kt" "$WINLATOR_APP/src/main/java/com/snapix/nhhlauncher/NhhControllerRuntime.kt"
cp "$NHH_SOURCE/ControllerTestScreen.kt" "$WINLATOR_APP/src/main/java/com/snapix/nhhlauncher/ControllerTestScreen.kt"
cp "$NHH_SOURCE/OnboardingActivity.kt" "$WINLATOR_APP/src/main/java/com/snapix/nhhlauncher/OnboardingActivity.kt"
cp "$NHH_SOURCE/ControlsActivity.kt" "$WINLATOR_APP/src/main/java/com/snapix/nhhlauncher/ControlsActivity.kt"
cp "$NHH_SOURCE/ControllerTestActivity.kt" "$WINLATOR_APP/src/main/java/com/snapix/nhhlauncher/ControllerTestActivity.kt"
cp "$NHH_SOURCE/NhhSessionMenu.java" "$WINLATOR_APP/src/main/java/com/snapix/nhhlauncher/NhhSessionMenu.java"
cp "$NHH_SOURCE/NhhStartupOverlay.java" "$WINLATOR_APP/src/main/java/com/snapix/nhhlauncher/NhhStartupOverlay.java"
rm -rf "$WINLATOR_APP/src/main/java/com/snapix/nhhlauncher/cast"
cp -r "$NHH_SOURCE/cast" "$WINLATOR_APP/src/main/java/com/snapix/nhhlauncher/"
# 2b. Install only the two supplied NHH controller layouts.
NHH_PROFILE_ASSETS="$WINLATOR_APP/src/main/assets/inputcontrols/profiles"
mkdir -p "$NHH_PROFILE_ASSETS"
rm -f "$NHH_PROFILE_ASSETS"/*.icp
cp "app/src/main/assets/nhh_controls/controls-5.icp" "$NHH_PROFILE_ASSETS/controls-5.icp"
cp "app/src/main/assets/nhh_controls/controls-6.icp" "$NHH_PROFILE_ASSETS/controls-6.icp"

# 2c. Download real No Heroes Here artwork for the launcher; no SVG/mock artwork.
NHH_DRAWABLES="$WINLATOR_APP/src/main/res/drawable-nodpi"
mkdir -p "$NHH_DRAWABLES"
curl -L --fail --retry 3 "https://shared.akamai.steamstatic.com/store_item_assets/steam/apps/696140/header.jpg?t=1726857314" -o "$NHH_DRAWABLES/nhh_hero.jpg"
curl -L --fail --retry 3 "https://image.api.playstation.com/cdn/UP3562/CUSA12079_00/2LwCzU02fXbDK7wIcCYRHhuRf6zJSqib.png" -o "$NHH_DRAWABLES/nhh_poster.png"
cp "$NHH_DRAWABLES/nhh_poster.png" "$NHH_DRAWABLES/nhh_icon.png"
# Use the original game artwork without synthetic zoom-crop animation.
# 2d. Patch Winlator's native touch/gamepad dispatch path.
python3 tools/patch_winlator_input.py "$WINLATOR_APP/src/main/java/com/winlator/widget/InputControlsView.java"

# 3. Patch the rootfs installer so NHH can show setup progress without Winlator's launcher UI.
ROOTFS_INSTALLER="$WINLATOR_APP/src/main/java/com/winlator/xenvironment/RootFSInstaller.java"
python3 - "$ROOTFS_INSTALLER" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()

if "NHH_INSTALL_PROGRESS" not in s:
    s = s.replace(
        'public static final String FILENAME = "rootfs.tzst";',
        """public static final String FILENAME = "rootfs.tzst";
    private static volatile int NHH_INSTALL_PROGRESS = 0;
    private static volatile boolean NHH_INSTALL_RUNNING = false;
    public static int getNhhInstallProgress() { return NHH_INSTALL_PROGRESS; }
    public static boolean isNhhInstallRunning() { return NHH_INSTALL_RUNNING; }""",
        1
    )
    s = s.replace(
        "public static void install(final MainActivity activity) {",
        "public static void install(final androidx.appcompat.app.AppCompatActivity activity) {",
        1
    )
    s = s.replace(
        "public static void installIfNeeded(final MainActivity activity) {",
        "public static void installIfNeeded(final androidx.appcompat.app.AppCompatActivity activity) {",
        1
    )

s = s.replace(
    "dialog.show(R.string.installing_system_files);",
    """boolean nhhHost = activity.getClass().getName().startsWith("com.snapix.nhhlauncher.");
        NHH_INSTALL_PROGRESS = 0;
        NHH_INSTALL_RUNNING = true;
        if (!nhhHost) dialog.show(R.string.installing_system_files);""",
    1
)
s = s.replace(
    "final int progress = (int)(((float)totalSize / contentLength) * 100);",
    "final int progress = (int)(((float)totalSize / contentLength) * 100); NHH_INSTALL_PROGRESS = Math.max(0, Math.min(100, progress));",
    1
)
s = s.replace(
    "activity.runOnUiThread(() -> dialog.setProgress(progress));",
    "if (!nhhHost) activity.runOnUiThread(() -> dialog.setProgress(progress));",
    1
)
s = s.replace(
    "else AppUtils.showToast(activity, R.string.unable_to_install_system_files);",
    "else if (!nhhHost) AppUtils.showToast(activity, R.string.unable_to_install_system_files);",
    1
)
s = s.replace(
    "dialog.closeOnUiThread();",
    "NHH_INSTALL_PROGRESS = success ? 100 : -1; NHH_INSTALL_RUNNING = false; if (!nhhHost) dialog.closeOnUiThread();",
    1
)
p.write_text(s)
PY

# 4. Make the Winlator manifest explicitly launch NHH, not Winlator's own MainActivity.
MANIFEST="$WINLATOR_APP/src/main/AndroidManifest.xml"
python3 - "$MANIFEST" <<'PY'
from pathlib import Path
import re, sys
p = Path(sys.argv[1])
s = p.read_text()

pattern = re.compile(r'\s*<activity android:name="com\.winlator\.MainActivity"(?P<body>.*?)</activity>', re.S)
m = pattern.search(s)
if not m:
    raise SystemExit("Winlator MainActivity manifest entry not found")

replacement = '''\n        <activity android:name="com.winlator.MainActivity"
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
s = s[:m.start()] + replacement + s[m.end():]

manifest_match = re.search(r'<manifest\b[^>]*>', s)
if not manifest_match:
    raise SystemExit("Manifest opening tag not found")
manifest_tag = manifest_match.group(0)
permissions = [
    '        <uses-permission android:name="android.permission.INTERNET" />',
    '        <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />',
    '        <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />',
    '        <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE" />',
    '        <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation" />',
    '        <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />',
    '        <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />',
    '        <uses-permission android:name="android.permission.WAKE_LOCK" />',
]
for perm in permissions:
    if perm.strip() not in s:
        s = s.replace(manifest_tag, manifest_tag + "\n" + perm, 1)

if "com.snapix.nhhlauncher.cast.CastActivity" not in s:
    marker = '        <activity android:name="com.snapix.nhhlauncher.MainActivity"'
    idx = s.find(marker)
    block = '''        <activity android:name="com.snapix.nhhlauncher.cast.CastActivity"
            android:exported="false"
            android:screenOrientation="portrait" />
        <activity android:name="com.snapix.nhhlauncher.cast.MediaProjectionRequestActivity"
            android:theme="@android:style/Theme.Translucent.NoTitleBar"
            android:excludeFromRecents="true"
            android:exported="false" />
        <service android:name="com.snapix.nhhlauncher.cast.CastForegroundService"
            android:foregroundServiceType="mediaProjection"
            android:exported="false" />
'''
    s = s[:idx] + block + s[idx:]
p.write_text(s)
PY

# Give NHH a unique FileProvider authority matching its application ID.
FILE_UTILS="$WINLATOR_APP/src/main/java/com/winlator/core/FileUtils.java"
python3 - "$FILE_UTILS" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text().replace('"com.winlator.FileProvider"', '"com.noheroshere.snap.FileProvider"')
p.write_text(s)
PY

# Update upstream hard-coded sandbox paths to the NHH application ID.
python3 - "$WINLATOR_APP/src/main/java/com/winlator/core/AppUtils.java" \
    "$WINLATOR_APP/src/main/cpp/winlator/include/winlator.h" \
    "$WINLATOR_APP/src/main/cpp/vortekrenderer/include/vortek.h" \
    "$WINLATOR_APP/src/main/cpp/gladiorenderer/include/gladio.h" <<'PY'
from pathlib import Path
import sys

replacements = {
    "/data/data/com.winlator/storage": "/data/data/com.noheroshere.snap/storage",
    "/data/data/com.winlator/cache": "/data/data/com.noheroshere.snap/cache",
    "/data/data/com.winlator/files/rootfs/tmp/.vortek/V0": "/data/data/com.noheroshere.snap/files/rootfs/tmp/.vortek/V0",
    "/data/data/com.winlator/files/rootfs/tmp/.X11-unix/X0": "/data/data/com.noheroshere.snap/files/rootfs/tmp/.X11-unix/X0",
}
for name in sys.argv[1:]:
    path = Path(name)
    text = path.read_text()
    for old, new in replacements.items():
        text = text.replace(old, new)
    path.write_text(text)
PY
if grep -R -F "/data/data/com.winlator/" "$WINLATOR_APP/src/main/java" "$WINLATOR_APP/src/main/cpp"; then
    echo "A stale upstream sandbox path still points at the old application ID." >&2
    exit 1
fi

# Rename the native background-session notification and app strings for NHH.
FOREGROUND_SERVICE="$WINLATOR_APP/src/main/java/com/winlator/services/ForegroundService.java"
python3 - "$FOREGROUND_SERVICE" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
s = s.replace('"Winlator",\n                XServerDisplayActivity.class', '"No Heroes Here",\n                XServerDisplayActivity.class')
s = s.replace('return "Winlator is running in the background";', 'return "No Heroes Here is running in the background";')
s = s.replace(
    "Class<?> targetActivity = containerActive ? XServerDisplayActivity.class : MainActivity.class;",
    "Class<?> targetActivity = containerActive ? XServerDisplayActivity.class : com.snapix.nhhlauncher.MainActivity.class;"
)
p.write_text(s)
PY

STRINGS="$WINLATOR_APP/src/main/res/values/strings.xml"
python3 - "$STRINGS" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
s = s.replace('<string name="app_name">Winlator</string>', '<string name="app_name">No Heroes Here</string>')
s = s.replace("Keeps Winlator running in the background so a wine session is not interrupted by Android\\'s background process management.", "Keeps No Heroes Here running in the background so the game session is not interrupted by Android\\'s background process management.")
s = s.replace('<string name="fgs_notification_content_else">Winlator is running in the background</string>', '<string name="fgs_notification_content_else">No Heroes Here is running in the background</string>')
p.write_text(s)
PY

# 5. Patch the display session for NHH game mode and keep native Winlator desktop mode intact.
XSERVER_DISPLAY="$WINLATOR_APP/src/main/java/com/winlator/XServerDisplayActivity.java"
python3 - "$XSERVER_DISPLAY" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()

# NHH session flag and Chromecast result request.
if "private boolean nhhSession;" not in s:
    s = s.replace(
        "private String screenEffectProfile;",
        "private String screenEffectProfile;\n    private boolean nhhSession;\n    private boolean isNhhSession;\n    private static final int NHH_CAST_REQUEST_CODE = 0x4E48;",
        1
    )
else:
    s = s.replace(
        "private boolean nhhSession;\n    private static final int NHH_CAST_REQUEST_CODE",
        "private boolean nhhSession;\n    private boolean isNhhSession;\n    private static final int NHH_CAST_REQUEST_CODE",
        1
    )
if "nhhSession = getIntent().hasExtra" not in s:
    s = s.replace(
        "final PreloaderDialog preloaderDialog = new PreloaderDialog(this);",
        """final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
        nhhSession = getIntent().hasExtra("nhh_controls_profile_id")
                || getIntent().getBooleanExtra("nhh_desktop_mode", false);""",
        1
    )

# Never show native runtime dialogs in an NHH session.
s = s.replace(
    "preloaderDialog.show(R.string.updating_system_files);",
    """if (nhhSession) com.snapix.nhhlauncher.NhhStartupOverlay.show(
                        this, findViewById(R.id.FLXServerDisplay), "Updating game environment…"
                );
                else preloaderDialog.show(R.string.updating_system_files);""",
    1
)
s = s.replace(
    "preloaderDialog.show(R.string.starting_up);",
    "if (!nhhSession) preloaderDialog.show(R.string.starting_up);",
    1
)

# Hide the upstream navigation drawer for NHH and keep the display clean.
if "boolean isNhhSession = getIntent().hasExtra" not in s:
    marker = "NavigationView navigationView = findViewById(R.id.NavigationView);"
    insert = """NavigationView navigationView = findViewById(R.id.NavigationView);
        boolean isNhhSession = getIntent().hasExtra("nhh_controls_profile_id")
                || getIntent().getBooleanExtra("nhh_desktop_mode", false);
        if (isNhhSession) {
            navigationView.setVisibility(View.GONE);
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        }"""
    if marker not in s:
        raise SystemExit("XServer navigation marker not found")
    s = s.replace(marker, insert, 1)

# Attach the selected real NHH Winlator profile only when touch controls are enabled.
if "NhhControllerRuntime.attachToSession(this, inputControlsView, xServer" not in s:
    marker = "        if (MainActivity.DEBUG_MODE) rootView.addView(AppUtils.createDebugMsgTextView(this));"
    replacement = """        if (getIntent().hasExtra("nhh_controls_profile_id")
                && !getIntent().getBooleanExtra("nhh_desktop_mode", false)) {
            int nhhProfileId = getIntent().getIntExtra(
                    "nhh_controls_profile_id",
                    com.snapix.nhhlauncher.NhhControllerRuntime.DEFAULT_PROFILE_ID
            );
            if (nhhProfileId > 0) {
                ControlsProfile profile = com.snapix.nhhlauncher.NhhControllerRuntime.ensureNhhProfile(this, nhhProfileId);
                showInputControls(profile);
                com.snapix.nhhlauncher.NhhControllerRuntime.attachToSession(this, inputControlsView, xServer, profile.id);
            }
            if (getIntent().getBooleanExtra("nhh_force_fullscreen", true)) {
                xServerView.post(() -> {
                    if (!xServerView.getRenderer().isFullscreen()) xServerView.getRenderer().toggleFullscreen();
                });
            }
        }

        if (isNhhSession) {
            com.snapix.nhhlauncher.NhhSessionMenu.attach(
                    this,
                    rootView,
                    this::openNhhControlsEditor,
                    this::openNhhCast,
                    this::exit
            );
            touchpadView.setFourFingersTapCallback(() ->
                    com.snapix.nhhlauncher.NhhSessionMenu.open(rootView)
            );
        }

        if (MainActivity.DEBUG_MODE) rootView.addView(AppUtils.createDebugMsgTextView(this));"""
    if marker not in s:
        raise SystemExit("XServer setupUI marker not found")
    s = s.replace(marker, replacement, 1)

# Android 13+ predictive Back does not reliably route through onBackPressed().
# Register a direct dispatcher callback for NHH sessions so Back always opens the menu.
anchor='        setupUI();'
insert='''        if (nhhSession && android.os.Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                    () -> com.snapix.nhhlauncher.NhhSessionMenu.open(
                            findViewById(R.id.FLXServerDisplay)
                    )
            );
        }

'''
if anchor in s and insert not in s:
    s=s.replace(anchor,insert+anchor,1)

# Make the existing upstream Back handler open the NHH menu during game sessions.
needle="""    @Override
    public void onBackPressed() {
"""
start=s.find(needle)
if start >= 0:
    body_end=s.find("\n    }", start)
    if body_end >= 0:
        method_end=body_end+len("\n    }")
        method=s[start:method_end]
        if "nhhSession" in method:
            replacement="""    @Override
    public void onBackPressed() {
        if (nhhSession) {
            com.snapix.nhhlauncher.NhhSessionMenu.open(findViewById(R.id.FLXServerDisplay));
            return;
        }
        super.onBackPressed();
    }"""
            s=s[:start]+replacement+s[method_end:]
p.write_text(s)
PY

# Launch the Controls Editor and return to the same session with its saved layout.
python3 - "$XSERVER_DISPLAY" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()

if "private void openNhhControlsEditor()" not in s:
    marker = "    private boolean isGenerateWineprefix() {"
    methods = """    private void openNhhControlsEditor() {
        int profileId = getIntent().getIntExtra(
                "nhh_controls_profile_id",
                com.snapix.nhhlauncher.NhhControllerRuntime.DEFAULT_PROFILE_ID
        );
        if (profileId <= 0) profileId = com.snapix.nhhlauncher.NhhControllerRuntime.DEFAULT_PROFILE_ID;
        final int selectedProfileId = profileId;

        com.snapix.nhhlauncher.NhhControllerRuntime.ensureNhhProfile(this, selectedProfileId);
        editInputControlsCallback = () -> {
            ControlsProfile updated = com.snapix.nhhlauncher.NhhControllerRuntime.ensureNhhProfile(this, selectedProfileId);
            showInputControls(updated);
            com.snapix.nhhlauncher.NhhControllerRuntime.attachToSession(this, inputControlsView, xServer, selectedProfileId);
        };

        Intent intent = new Intent(this, ControlsEditorActivity.class);
        intent.putExtra("profile_id", selectedProfileId);
        intent.putExtra("nhh_editor_safe_area", true);
        startActivityForResult(intent, MainActivity.EDIT_INPUT_CONTROLS_REQUEST_CODE);
    }

    private void openNhhCast() {
        startActivityForResult(
                new Intent(this, com.snapix.nhhlauncher.cast.CastActivity.class),
                NHH_CAST_REQUEST_CODE
        );
    }

"""
    if marker not in s:
        raise SystemExit("XServer utility marker not found")
    s = s.replace(marker, methods + marker, 1)

# Hand the chosen Chromecast to the screen-capture permission trampoline.
if "requestCode == NHH_CAST_REQUEST_CODE" not in s:
    marker = "        super.onActivityResult(requestCode, resultCode, data);"
    insert = """        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == NHH_CAST_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Intent projectionIntent = new Intent(
                        this,
                        com.snapix.nhhlauncher.cast.MediaProjectionRequestActivity.class
                );
                projectionIntent.putExtras(data);
                startActivity(projectionIntent);
            }
            return;
        }"""
    if marker not in s:
        raise SystemExit("XServer onActivityResult marker not found")
    s = s.replace(marker, insert, 1)

if "NhhControllerRuntime.onSessionPaused();" not in s:
    s = s.replace(
        "public void onPause() {",
        "public void onPause() {\n        com.snapix.nhhlauncher.NhhControllerRuntime.onSessionPaused();",
        1
    )
if "NhhControllerRuntime.onSessionResumed(this);" not in s:
    s = s.replace(
        "public void onResume() {",
        "public void onResume() {\n        com.snapix.nhhlauncher.NhhControllerRuntime.onSessionResumed(this);",
        1
    )
if "NhhControllerRuntime.detachFromSession();" not in s:
    s = s.replace(
        "protected void onDestroy() {",
        "protected void onDestroy() {\n        com.snapix.nhhlauncher.NhhControllerRuntime.detachFromSession();",
        1
    )

p.write_text(s)
PY

# NHH is a Unity game. Force Unity's direct graphics path for this session.
python3 - "$XSERVER_DISPLAY" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1])
s=p.read_text()
needle='        if (cmdArgs.isEmpty()) cmdArgs = "/dir C:\\\\windows \\\"wfm.exe\\\"";'
if needle not in s:
    # Match the actual source spelling without relying on shell escaping.
    needle='        if (cmdArgs.isEmpty()) cmdArgs = "/dir C:\\\\windows \\\"wfm.exe\\\"";'
if needle in s and 'if (nhhSession) cmdArgs += " -force-gfx-direct";' not in s:
    s=s.replace(needle, needle+'\n\n        if (nhhSession) cmdArgs += " -force-gfx-direct";',1)
p.write_text(s)
PY

# Report NHH guest-process termination instead of silently leaving a black XServer.
python3 - "$XSERVER_DISPLAY" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
old = "        guestProgramLauncherComponent.setTerminationCallback((status) -> exit());"
new = """        guestProgramLauncherComponent.setTerminationCallback((status) -> {
            if (nhhSession) {
                runOnUiThread(() -> {
                    try {
                        java.io.File log = new java.io.File(getFilesDir(), "nhh-launch.log");
                        java.io.FileWriter writer = new java.io.FileWriter(log, true);
                        writer.write(System.currentTimeMillis() + " Guest program terminated status=" + status + "\\n");
                        writer.close();
                    } catch (Throwable ignored) {}
                    com.snapix.nhhlauncher.NhhStartupOverlay.showError(
                            this,
                            findViewById(R.id.FLXServerDisplay),
                            "The game process stopped before opening a window. Exit status: " + status,
                            this::exit
                    );
                });
            }
            else exit();
        });"""
if old in s:
    s = s.replace(old, new, 1)
p.write_text(s)
PY

# 5b. Use an NHH-owned launch overlay instead of the native startup dialog.
python3 - "$XSERVER_DISPLAY" <<'PY'
from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text()

old_map = """                    preloaderDialog.closeOnUiThread();
                    flags[0] = true;""";
new_map = """                    if (nhhSession) runOnUiThread(() ->
                            com.snapix.nhhlauncher.NhhStartupOverlay.dismiss(
                                    findViewById(R.id.FLXServerDisplay)
                            )
                    );
                    preloaderDialog.closeOnUiThread();
                    flags[0] = true;""";
if "NhhStartupOverlay.dismiss(" not in s:
    if old_map not in s:
        raise SystemExit("XServer first-window callback marker not found")
    s = s.replace(old_map, new_map, 1)
elif "updateCursorVisibility();" in s and "if (nhhSession) runOnUiThread(()" in s:
    s = s.replace(
        """updateCursorVisibility();
                    if (nhhSession) runOnUiThread(() ->
                            com.snapix.nhhlauncher.NhhStartupOverlay.dismiss(
                                    findViewById(R.id.FLXServerDisplay)
                            )
                    );
                    preloaderDialog.closeOnUiThread();""",
        """xServerView.getRenderer().setCursorVisible(true);
                    if (nhhSession) runOnUiThread(() ->
                            com.snapix.nhhlauncher.NhhStartupOverlay.dismiss(
                                    findViewById(R.id.FLXServerDisplay)
                            )
                    );
                    preloaderDialog.closeOnUiThread();""",
        1
    )

if 'NhhStartupOverlay.show(this, rootView, "Launching No Heroes Here…")' not in s:
    marker = "        if (MainActivity.DEBUG_MODE) rootView.addView(AppUtils.createDebugMsgTextView(this));"
    replacement = """        if (isNhhSession) {
            com.snapix.nhhlauncher.NhhStartupOverlay.show(this, rootView, "Launching No Heroes Here…");
            rootView.postDelayed(() -> {
                if (com.snapix.nhhlauncher.NhhStartupOverlay.isLoading(rootView)) {
                    com.snapix.nhhlauncher.NhhStartupOverlay.showError(
                            this,
                            rootView,
                            "The game did not open a window. Check that the selected executable and game files are complete.",
                            this::exit
                    );
                }
            }, 180000);
        }

        if (MainActivity.DEBUG_MODE) rootView.addView(AppUtils.createDebugMsgTextView(this));"""
    if marker not in s:
        raise SystemExit("XServer setupUI completion marker not found")
    s = s.replace(marker, replacement, 1)

worker_old = """        Executors.newSingleThreadExecutor().execute(() -> {
            if (!isGenerateWineprefix()) {
                setupWineSystemFiles();
                extractGraphicsDriverFiles();
                changeWineAudioDriver();
            }
            setupXEnvironment();
        });"""
worker_new = """        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                if (!isGenerateWineprefix()) {
                    setupWineSystemFiles();
                    extractGraphicsDriverFiles();
                    changeWineAudioDriver();
                }
                setupXEnvironment();
            }
            catch (Throwable error) {
                if (nhhSession) {
                    runOnUiThread(() -> com.snapix.nhhlauncher.NhhStartupOverlay.showError(
                            this,
                            findViewById(R.id.FLXServerDisplay),
                            error.getMessage() != null ? error.getMessage() : "The game environment could not start.",
                            this::exit
                    ));
                }
                else throw new RuntimeException(error);
            }
        });"""
if "The game environment could not start." not in s:
    if worker_old not in s:
        raise SystemExit("XServer startup worker marker not found")
    s = s.replace(worker_old, worker_new, 1)

# Final compatibility guard: this upstream revision has no updateCursorVisibility().
s = s.replace("updateCursorVisibility();", "xServerView.getRenderer().setCursorVisible(true);")
p.write_text(s)
PY

# 6. Patch editor/runtime input dispatch and safe-area parity.
CONTROLS_EDITOR="$WINLATOR_APP/src/main/java/com/winlator/ControlsEditorActivity.java"
if [[ -f "$CONTROLS_EDITOR" ]]; then
python3 - "$CONTROLS_EDITOR" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); s=p.read_text()
needle="inputControlsView.setProfile(profile);"
if "nhh_editor_safe_area" not in s and needle in s:
    s=s.replace(needle, needle+'\n        if (getIntent().getBooleanExtra("nhh_editor_safe_area", false)) com.snapix.nhhlauncher.NhhControllerRuntime.applySafeAreaInsets(this, inputControlsView);',1)
p.write_text(s)
PY
fi

INPUT_CONTROLS_VIEW="$WINLATOR_APP/src/main/java/com/winlator/widget/InputControlsView.java"
if [[ -f "$INPUT_CONTROLS_VIEW" ]]; then
python3 - "$INPUT_CONTROLS_VIEW" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1]); s=p.read_text()
if "NhhControllerRuntime.recordDispatchedEvent" not in s and "winHandler.sendGamepadState();" in s:
    s=s.replace("winHandler.sendGamepadState();", 'winHandler.sendGamepadState();\n                com.snapix.nhhlauncher.NhhControllerRuntime.recordDispatchedEvent("GamepadState -> WinHandler");')
p.write_text(s)
PY
fi


# 7. Make the native Winlator touch overlay the authoritative input path.
INPUT_CONTROLS_VIEW="$WINLATOR_APP/src/main/java/com/winlator/widget/InputControlsView.java"
python3 - "$INPUT_CONTROLS_VIEW" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()

dispatch_marker = '    public void handleInputEvent(Binding binding, boolean isActionDown, float offset) {\n'
if "NhhControllerRuntime.recordDispatchedEvent" not in s:
    if dispatch_marker not in s:
        raise SystemExit("InputControlsView handleInputEvent marker not found")
    s = s.replace(
        dispatch_marker,
        dispatch_marker + '        com.snapix.nhhlauncher.NhhControllerRuntime.recordDispatchedEvent("Binding " + binding + " " + (isActionDown ? "DOWN" : "UP"));\n',
        1
    )

old_move = '''                case MotionEvent.ACTION_MOVE: {
                    for (byte i = 0, count = (byte)event.getPointerCount(); i < count; i++) {
                        float x = event.getX(i);
                        float y = event.getY(i);
                        int movePointerId = event.getPointerId(i);

                        handled = false;
                        for (ControlElement element : profile.getElements()) {
                            if (element.handleTouchMove(movePointerId, x, y)) handled = true;
                        }
                        if (!handled) touchpadView.onTouchEvent(event);
                    }
                    break;
                }'''
new_move = '''                case MotionEvent.ACTION_MOVE: {
                    boolean touchpadHandled = false;
                    for (int i = 0; i < event.getPointerCount(); i++) {
                        float x = event.getX(i);
                        float y = event.getY(i);
                        int movePointerId = event.getPointerId(i);

                        boolean pointerHandled = false;
                        for (ControlElement element : profile.getElements()) {
                            if (element.handleTouchMove(movePointerId, x, y)) pointerHandled = true;
                        }
                        if (!pointerHandled) touchpadHandled = true;
                    }
                    if (touchpadHandled) touchpadView.onTouchEvent(event);
                    break;
                }'''
if old_move in s:
    s = s.replace(old_move, new_move, 1)

size_marker = '    @Override\n    public boolean onGenericMotionEvent(MotionEvent event) {'
if "protected void onSizeChanged(int w, int h, int oldw, int oldh)" not in s:
    if size_marker not in s:
        raise SystemExit("InputControlsView onGenericMotionEvent marker not found")
    size_method = '''    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (profile != null && w > 0 && h > 0) {
            profile.loadElements(this);
            invalidate();
        }
    }

'''
    s = s.replace(size_marker, size_method + size_marker, 1)

p.write_text(s)
PY


# Keep the NHH overlay aligned after rotation, split-screen, and window-size changes.
python3 - "$XSERVER_DISPLAY" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
if "NhhControllerRuntime.onViewportChanged(this, inputControlsView)" not in s:
    idx = s.rfind("\n}")
    method = '''
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (inputControlsView != null && getIntent().hasExtra("nhh_controls_profile_id")) {
            com.snapix.nhhlauncher.NhhControllerRuntime.onViewportChanged(inputControlsView);
        }
    }
'''
    s = s[:idx] + method + s[idx:]
p.write_text(s)
PY


# 2e. Give the NHH app its own Android application ID.
python3 - "$WINLATOR_APP/build.gradle" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1])
s=p.read_text()
s=s.replace("applicationId 'com.winlator'", "applicationId 'com.noheroshere.snap'")
s=s.replace("versionCode 33", "versionCode 38")
s=s.replace('versionName "11.2"', 'versionName "1.0.4"')
p.write_text(s)
PY

# 2d. Register NHH first-run and controller activities in the final Winlator manifest.
python3 - "$MANIFEST" <<'PY'
from pathlib import Path
import sys
p=Path(sys.argv[1])
s=p.read_text()
anchor='        <activity android:name="com.snapix.nhhlauncher.MainActivity"'
block='''        <activity android:name="com.snapix.nhhlauncher.OnboardingActivity"
            android:theme="@style/AppThemeDark"
            android:exported="false"
            android:screenOrientation="sensor" />
        <activity android:name="com.snapix.nhhlauncher.ControlsActivity"
            android:theme="@style/AppThemeDark"
            android:exported="false"
            android:screenOrientation="sensor" />
        <activity android:name="com.snapix.nhhlauncher.ControllerTestActivity"
            android:theme="@style/AppThemeDark"
            android:exported="false"
            android:screenOrientation="sensor" />
'''
if "com.snapix.nhhlauncher.OnboardingActivity" not in s:
    s=s.replace(anchor,block+anchor,1)
s=s.replace('android:icon="@mipmap/ic_launcher"', 'android:icon="@drawable/nhh_icon"')
s=s.replace('android:label="@string/app_name"', 'android:label="No Heroes Here"')
s=s.replace('android:authorities="com.winlator.FileProvider"', 'android:authorities="com.noheroshere.snap.FileProvider"')
p.write_text(s)
PY

echo "NHH preparation complete."

