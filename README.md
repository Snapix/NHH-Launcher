# NHH Launcher

Dedicated Android launcher/runtime for NHH.

## Current flow

- First launch asks for the user's NHH ZIP.
- ZIP is extracted into private app storage.
- The launcher recursively searches for NHH.exe.
- The discovered executable path is persisted.
- Later launches go directly to the NHH main menu.
- Main menu: Run Game and Settings.

The game itself is not included in this repository or APK.

## Runtime

The intended runtime is based on the open-source Winlator stack: Wine, Box86/Box64 and the associated graphics/runtime components. Winlator is LGPL-2.1; third-party components have their own licenses.

Upstream:
https://github.com/brunodev85/winlator
https://github.com/brunodev85/winlator-app

## Status

The Android launcher shell and secure ZIP installation flow are implemented first. Winlator runtime integration is the next milestone.

## Build

GitHub Actions builds a debug APK on pushes to main and dev.
