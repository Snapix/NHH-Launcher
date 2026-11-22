# No Heroes Here — Android

A dedicated Android port and launcher for **No Heroes Here**.

The game is not included in this repository or APK. Users import a ZIP of their own legally obtained game files.

## App identity

- Android application ID: `com.noheroshere.snap`
- Launcher implementation namespace: `com.snapix.nhhlauncher`

## Current experience

- First-run setup imports and extracts the user's game ZIP into private app storage.
- The launcher locates the game executable and prepares its isolated game environment.
- The game folder is mapped as drive `G:`.
- Two supplied touch layouts are installed as native controller profiles:
  - **NHH MAIN**
  - **NHH MAIN no key**
- The in-session NHH menu provides Resume, Edit Controls, Cast and Save and exit.
- Chromecast screen casting uses Android screen capture and a low-latency HLS stream.
- The main menu includes runtime settings, controller editing and desktop access.

## Runtime and attribution

This project integrates the open-source [Winlator](https://github.com/brunodev85/winlator) and [Winlator app](https://github.com/brunodev85/winlator-app) codebase for the Windows compatibility and graphics runtime, including Wine and Box86/Box64 components.

Winlator is licensed under LGPL-2.1. Third-party components retain their respective licenses. See upstream repositories and included license notices for details.

The Android launcher, NHH interface and NHH-specific integration are maintained in this repository.

## Build

GitHub Actions builds a debug APK on pushes to `main` and `dev`. The workflow verifies the application ID, launcher activity, artwork and both controller profile assets.
## Build without Git submodules

If you downloaded this project as a ZIP, run `./tools/fetch-winlator.sh` before opening/building the Gradle project. GitHub Actions performs this step automatically when the Winlator source directory is absent.
