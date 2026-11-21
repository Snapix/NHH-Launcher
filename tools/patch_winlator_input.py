#!/usr/bin/env python3
from pathlib import Path
import sys

path = Path(sys.argv[1])
s = path.read_text()

needle = "            if (winHandler != null) winHandler.gamepadHandler.sendGamepadState(profile);"
replacement = """            if (winHandler != null) {
                winHandler.gamepadHandler.sendGamepadState(profile);
                com.snapix.nhhlauncher.NhhControllerRuntime.recordDispatchedEvent(
                        binding.name() + (isActionDown ? " DOWN" : " UP") +
                        (offset != 0 ? " " + offset : "")
                );
            }"""
if needle not in s:
    raise SystemExit("InputControlsView gamepad dispatch anchor not found")
s = s.replace(needle, replacement, 1)

old_move = """                    for (byte i = 0, count = (byte)event.getPointerCount(); i < count; i++) {
                        float x = event.getX(i);
                        float y = event.getY(i);
                        int movePointerId = event.getPointerId(i);

                        handled = false;
                        for (ControlElement element : profile.getElements()) {
                            if (element.handleTouchMove(movePointerId, x, y)) handled = true;
                        }
                        if (!handled) touchpadView.onTouchEvent(event);
                    }"""
new_move = """                    for (byte i = 0, count = (byte)event.getPointerCount(); i < count; i++) {
                        float x = event.getX(i);
                        float y = event.getY(i);
                        int movePointerId = event.getPointerId(i);

                        for (ControlElement element : profile.getElements()) {
                            if (element.handleTouchMove(movePointerId, x, y)) handled = true;
                        }
                    }
                    if (!handled) touchpadView.onTouchEvent(event);"""
if old_move not in s:
    raise SystemExit("InputControlsView multi-touch MOVE anchor not found")
s = s.replace(old_move, new_move, 1)

marker = "    public boolean onKeyEvent(KeyEvent event) {"
method = """    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (profile != null && w > 0 && h > 0) {
            profile.loadElements(this);
            invalidate();
            com.snapix.nhhlauncher.NhhControllerRuntime.onViewportChanged(null, this);
        }
    }

"""
if marker not in s:
    raise SystemExit("InputControlsView onKeyEvent anchor not found")
if "NhhControllerRuntime.onViewportChanged(null, this)" not in s:
    s = s.replace(marker, method + marker, 1)

path.write_text(s)
