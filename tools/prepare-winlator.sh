#!/usr/bin/env bash
set -euo pipefail

WINLATOR_ROOT="winlator-app"
WINLATOR_APP="$WINLATOR_ROOT/app"
NHH_SOURCE="app/src/main/java/com/snapix/nhhlauncher"

[[ -d "$WINLATOR_APP" ]] || { echo "Winlator submodule is missing"; exit 1; }

echo "Preparing NHH launcher sources..."

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
cp "$NHH_SOURCE/NhhControllerRuntime.kt" "$WINLATOR_APP/src/main/java/com/snapix/nhhlauncher/NhhControllerRuntime.kt"
cp "$NHH_SOURCE/ControllerTestScreen.kt" "$WINLATOR_APP/src/main/java/com/snapix/nhhlauncher/ControllerTestScreen.kt"
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
rm -rf /tmp/nhh-gif-frames
mkdir -p /tmp/nhh-gif-frames
for i in 0 1 2 3 4 5 6 7; do
    convert "$NHH_DRAWABLES/nhh_hero.jpg" -resize "720x" -gravity center -crop "$((92 + i * 4))%x$((92 + i * 4))%+0+0" +repage -resize 640x360 "/tmp/nhh-gif-frames/frame-$i.jpg"
done
convert -delay 9 -loop 0 /tmp/nhh-gif-frames/frame-*.jpg "$NHH_DRAWABLES/nhh_gameplay.gif"



# 3. Patch the rootfs installer so NHH can show setup progress without Winlator's launcher UI.
ROOTFS_INSTALLER="$WINLATOR_APP/src/main/java/com/winlator/xenvironment/RootFSInstaller.java"
python3 - "$ROOTFS_INSTALLER" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()
if "NHH_INSTALL_PROGRESS" not in s:
    s = s.replace(
        "public static final String FILENAME = \"rootfs.tzst\";",
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
        "NHH_INSTALL_PROGRESS = 0; NHH_INSTALL_RUNNING = true; dialog.show(R.string.installing_system_files);",
        1
    )
    s = s.replace(
        "final int progress = (int)(((float)totalSize / contentLength) * 100);",
        "final int progress = (int)(((float)totalSize / contentLength) * 100); NHH_INSTALL_PROGRESS = Math.max(0, Math.min(100, progress));",
        1
    )
    s = s.replace(
        "dialog.closeOnUiThread();",
        "NHH_INSTALL_PROGRESS = success ? 100 : -1; NHH_INSTALL_RUNNING = false; dialog.closeOnUiThread();",
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

# 5. Patch the Winlator display session for NHH game mode and keep native Winlator desktop mode intact.
XSERVER_DISPLAY="$WINLATOR_APP/src/main/java/com/winlator/XServerDisplayActivity.java"
python3 - "$XSERVER_DISPLAY" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()

if "isNhhGameMode" not in s:
    marker = "NavigationView navigationView = findViewById(R.id.NavigationView);"
    insert = '''NavigationView navigationView = findViewById(R.id.NavigationView);
        boolean isNhhGameMode = getIntent().hasExtra("nhh_controls_profile_id")
                && !getIntent().getBooleanExtra("nhh_desktop_mode", false);
        if (isNhhGameMode) {
            navigationView.setVisibility(View.GONE);
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        }'''
    if marker not in s:
        raise SystemExit("XServer navigation marker not found")
    s = s.replace(marker, insert, 1)

# NHH's Compose launcher owns bootstrap progress; desktop/game sessions still use the native XServer activity.
s = s.replace('preloaderDialog.show(R.string.starting_up);', '/* NHH launcher owns game bootstrap UI. */', 1)

if "NhhControllerRuntime.attachToSession(this, inputControlsView, xServer" not in s:
    marker = '''        if (MainActivity.DEBUG_MODE) rootView.addView(AppUtils.createDebugMsgTextView(this));'''
    replacement = '''        if (getIntent().hasExtra("nhh_controls_profile_id")
                && !getIntent().getBooleanExtra("nhh_desktop_mode", false)) {
            ControlsProfile profile = com.snapix.nhhlauncher.NhhControllerRuntime.ensureNhhProfile(this, false);
            showInputControls(profile);
            com.snapix.nhhlauncher.NhhControllerRuntime.attachToSession(this, inputControlsView, xServer, profile.id);
            if (getIntent().getBooleanExtra("nhh_force_fullscreen", true)) {
                xServerView.post(() -> {
                    if (!xServerView.getRenderer().isFullscreen()) xServerView.getRenderer().toggleFullscreen();
                });
            }
        }

        if (MainActivity.DEBUG_MODE) rootView.addView(AppUtils.createDebugMsgTextView(this));'''
    if marker not in s:
        raise SystemExit("XServer setupUI marker not found")
    s = s.replace(marker, replacement, 1)

if "NhhControllerRuntime.onSessionPaused();" not in s:
    s=s.replace("public void onPause() {", "public void onPause() {\n        com.snapix.nhhlauncher.NhhControllerRuntime.onSessionPaused();", 1)
if "NhhControllerRuntime.onSessionResumed(this);" not in s:
    s=s.replace("public void onResume() {", "public void onResume() {\n        com.snapix.nhhlauncher.NhhControllerRuntime.onSessionResumed(this);", 1)
if "NhhControllerRuntime.detachFromSession();" not in s:
    s=s.replace("protected void onDestroy() {", "protected void onDestroy() {\n        com.snapix.nhhlauncher.NhhControllerRuntime.detachFromSession();", 1)

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
            com.snapix.nhhlauncher.NhhControllerRuntime.onViewportChanged(this, inputControlsView);
        }
    }
'''
    s = s[:idx] + method + s[idx:]
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
if 'android:icon="@drawable/nhh_icon"' not in s:
    s=s.replace('<application ', '<application android:icon="@drawable/nhh_icon" ', 1)
p.write_text(s)
PY

echo "NHH preparation complete."

