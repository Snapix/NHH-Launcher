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

# Patch Winlator bootstrap so NHH owns the progress UI instead of showing the
# upstream centered installation dialog.
ROOTFS_INSTALLER="$WINLATOR_APP/src/main/java/com/winlator/xenvironment/RootFSInstaller.java"
python3 - "$ROOTFS_INSTALLER" <<'PY'
from pathlib import Path
import re, sys
p = Path(sys.argv[1])
s = p.read_text()

start = s.find("    public static void install(final MainActivity activity) {")
end = s.find("    public static void installIfNeeded", start)
if start < 0 or end < 0:
    raise SystemExit("RootFSInstaller.install block not found")

method = r'''    public static void install(final MainActivity activity) {
        AppUtils.keepScreenOn(activity);
        RootFS rootFS = RootFS.find(activity);
        final File rootDir = rootFS.getRootDir();

        SettingsFragment.resetPreferenceVersions(activity);
        NHH_INSTALL_PROGRESS = 0;
        NHH_INSTALL_RUNNING = true;

        Executors.newSingleThreadExecutor().execute(() -> {
            boolean success = false;
            try {
                clearRootDir(rootDir);
                final long contentLength = TarCompressorUtils.getContentLength(
                    TarCompressorUtils.Type.ZSTD, activity, FILENAME, rootDir
                );
                AtomicLong totalSizeRef = new AtomicLong();

                success = TarCompressorUtils.extract(
                    TarCompressorUtils.Type.ZSTD, activity, FILENAME, rootDir, (file, size) -> {
                        if (size > 0 && contentLength > 0) {
                            long totalSize = totalSizeRef.addAndGet(size);
                            NHH_INSTALL_PROGRESS = Math.max(
                                0, Math.min(100, (int)(((float) totalSize / contentLength) * 100))
                            );
                        }
                        return file;
                    }
                );

                if (success) {
                    activity.runOnUiThread(() -> {
                        rootFS.createRFSVersionFile(LATEST_VERSION);
                        resetContainerRFSVersions(activity);
                    });
                }
                else {
                    activity.runOnUiThread(() ->
                        AppUtils.showToast(activity, R.string.unable_to_install_system_files)
                    );
                }
            }
            catch (Throwable t) {
                android.util.Log.e("NHHRootFS", "System file installation failed", t);
                activity.runOnUiThread(() ->
                    AppUtils.showToast(activity, R.string.unable_to_install_system_files)
                );
            }
            finally {
                NHH_INSTALL_PROGRESS = success ? 100 : -1;
                NHH_INSTALL_RUNNING = false;
            }
        });
    }

'''
s = s[:start] + method + s[end:]
if "private static volatile int NHH_INSTALL_PROGRESS" not in s:
    s = s.replace(
        'public static final String FILENAME = "rootfs.tzst";',
        '''public static final String FILENAME = "rootfs.tzst";
    private static volatile int NHH_INSTALL_PROGRESS = 0;
    private static volatile boolean NHH_INSTALL_RUNNING = false;
    public static int getNhhInstallProgress() { return NHH_INSTALL_PROGRESS; }
    public static boolean isNhhInstallRunning() { return NHH_INSTALL_RUNNING; }'''
    )
p.write_text(s)
PY

# Download real No Heroes Here artwork into Android resources. No generated SVGs
# or placeholder art are used.
ASSET_DIR="$WINLATOR_APP/src/main/res/drawable-nodpi"
mkdir -p "$ASSET_DIR"
curl -fL --retry 3 --retry-delay 1 "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/696140/header.jpg" -o "$ASSET_DIR/nhh_banner.jpg"
curl -fL --retry 3 --retry-delay 1 "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/696140/library_600x900.jpg" -o "$ASSET_DIR/nhh_poster.jpg"
curl -fL --retry 3 --retry-delay 1 "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/696140/logo.png" -o "$ASSET_DIR/nhh_logo.png"

# Force the real Virtual Gamepad profile for NHH. Winlator's profile 3 is a
# controller profile with GAMEPAD_* bindings; we load its elements before the
# first touch so the overlay can immediately send input to Wine.
XSERVER_DISPLAY="$WINLATOR_APP/src/main/java/com/winlator/XServerDisplayActivity.java"
python3 - "$XSERVER_DISPLAY" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
s = p.read_text()

s = s.replace(
    'NavigationView navigationView = findViewById(R.id.NavigationView);',
    '''NavigationView navigationView = findViewById(R.id.NavigationView);
        if (getIntent().hasExtra("nhh_controls_profile_id")) {
            navigationView.setVisibility(View.GONE);
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        }''',
    1
)

s = s.replace(
    'preloaderDialog.show(R.string.updating_system_files);',
    '/* NHH owns the bootstrap surface. */'
)
s = s.replace(
    'preloaderDialog.show(R.string.starting_up);',
    '/* NHH owns the launch surface. */'
)

needle = '''        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                ControlsProfile profile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                if (profile != null) showInputControls(profile);
            }
        }

        if (MainActivity.DEBUG_MODE) rootView.addView(AppUtils.createDebugMsgTextView(this));'''
replacement = '''        if (shortcut != null) {
            String controlsProfile = shortcut.getExtra("controlsProfile");
            if (!controlsProfile.isEmpty()) {
                ControlsProfile profile = inputControlsManager.getProfile(Integer.parseInt(controlsProfile));
                if (profile != null) showInputControls(profile);
            }
        }

        if (getIntent().hasExtra("nhh_controls_profile_id")) {
            int profileId = getIntent().getIntExtra("nhh_controls_profile_id", 0);
            ControlsProfile profile = inputControlsManager.getProfile(profileId);
            if (profile != null) {
                inputControlsView.setProfile(profile);
                profile.loadElements(inputControlsView);
                inputControlsView.setShowTouchscreenControls(true);
                inputControlsView.setVisibility(View.VISIBLE);
            }
            setupNhhOverlay(rootView);
        }

        if (MainActivity.DEBUG_MODE) rootView.addView(AppUtils.createDebugMsgTextView(this));'''
if needle not in s:
    raise SystemExit("setupUI input-control block not found")
s = s.replace(needle, replacement, 1)

old_back = '''    @Override
    public void onBackPressed() {
        if (environment != null) {
            if (!drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.openDrawer(GravityCompat.START);
            }
            else drawerLayout.closeDrawers();
        }
    }'''
new_back = '''    @Override
    public void onBackPressed() {
        finish();
    }'''
if old_back in s:
    s = s.replace(old_back, new_back, 1)

if "private void setupNhhOverlay" not in s:
    insert_at = s.rfind("\n}")
    method = r'''
    private void setupNhhOverlay(FrameLayout rootView) {
        final float density = getResources().getDisplayMetrics().density;

        final android.widget.FrameLayout menu = new android.widget.FrameLayout(this);
        menu.setVisibility(View.GONE);
        menu.setBackgroundColor(0xCC180B0B);

        final android.widget.LinearLayout panel = new android.widget.LinearLayout(this);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int)(24 * density);
        panel.setPadding(pad, (int)(20 * density), pad, (int)(20 * density));

        android.graphics.drawable.GradientDrawable panelBg = new android.graphics.drawable.GradientDrawable();
        panelBg.setColor(0xF0221111);
        panelBg.setStroke((int)(2 * density), 0xFF7D342C);
        panelBg.setCornerRadius(28 * density);
        panel.setBackground(panelBg);

        final android.widget.TextView title = new android.widget.TextView(this);
        title.setText("NHH");
        title.setTextColor(0xFFFFE8E2);
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        panel.addView(title);

        final android.widget.TextView subtitle = new android.widget.TextView(this);
        subtitle.setText("GAME MENU");
        subtitle.setTextColor(0xFFFF8A76);
        subtitle.setTextSize(12);
        panel.addView(subtitle);

        android.widget.Button resume = new android.widget.Button(this);
        resume.setText("RESUME");
        android.widget.Button controls = new android.widget.Button(this);
        controls.setText("TOUCH CONTROLS");
        android.widget.Button fullscreen = new android.widget.Button(this);
        fullscreen.setText("FULLSCREEN");
        android.widget.Button exit = new android.widget.Button(this);
        exit.setText("EXIT GAME");

        android.widget.LinearLayout.LayoutParams buttonLp =
            new android.widget.LinearLayout.LayoutParams(-1, (int)(54 * density));
        buttonLp.topMargin = (int)(10 * density);
        panel.addView(resume, new android.widget.LinearLayout.LayoutParams(buttonLp));
        panel.addView(controls, new android.widget.LinearLayout.LayoutParams(buttonLp));
        panel.addView(fullscreen, new android.widget.LinearLayout.LayoutParams(buttonLp));
        panel.addView(exit, new android.widget.LinearLayout.LayoutParams(buttonLp));

        menu.addView(panel, new android.widget.FrameLayout.LayoutParams(
            (int)(300 * density), -2, android.view.Gravity.CENTER
        ));
        rootView.addView(menu, new android.widget.FrameLayout.LayoutParams(-1, -1));

        android.widget.Button trigger = new android.widget.Button(this);
        trigger.setText("NHH");
        trigger.setTextColor(0xFFFFE8E2);
        trigger.setTextSize(12);

        android.graphics.drawable.GradientDrawable triggerBg = new android.graphics.drawable.GradientDrawable();
        triggerBg.setColor(0xCC2A1111);
        triggerBg.setStroke((int)(2 * density), 0xFF7D342C);
        triggerBg.setCornerRadius(48 * density);
        trigger.setBackground(triggerBg);

        trigger.setOnClickListener(v ->
            menu.setVisibility(menu.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)
        );

        android.widget.FrameLayout.LayoutParams triggerLp =
            new android.widget.FrameLayout.LayoutParams(
                (int)(64 * density), (int)(44 * density),
                android.view.Gravity.TOP | android.view.Gravity.END
            );
        int margin = (int)(12 * density);
        triggerLp.setMargins(margin, margin, margin, margin);
        rootView.addView(trigger, triggerLp);

        resume.setOnClickListener(v -> menu.setVisibility(View.GONE));
        controls.setOnClickListener(v -> {
            inputControlsView.setVisibility(
                inputControlsView.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE
            );
            menu.setVisibility(View.GONE);
        });
        fullscreen.setOnClickListener(v -> {
            xServerView.getRenderer().toggleFullscreen();
            menu.setVisibility(View.GONE);
        });
        exit.setOnClickListener(v -> finish());
    }
'''
    s = s[:insert_at] + method + s[insert_at:]

p.write_text(s)
PY

echo "Prepared the full Winlator application with the NHH launcher front-end."
echo "Prepared the full Winlator application with the NHH launcher front-end."
