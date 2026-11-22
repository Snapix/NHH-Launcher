#!/usr/bin/env bash
set -euo pipefail

WINLATOR_SHA="f8a211ce7fd776de9a2642b45d166016876eaa86"
WINLATOR_DIR="winlator-app"

if [ -f "$WINLATOR_DIR/app/build.gradle" ]; then
  echo "Winlator source already exists: $WINLATOR_DIR"
  exit 0
fi

rm -rf "$WINLATOR_DIR"
git clone --no-tags --depth 1 https://github.com/brunodev85/winlator-app.git "$WINLATOR_DIR"
cd "$WINLATOR_DIR"
git fetch --no-tags --depth 1 origin "$WINLATOR_SHA"
git checkout --detach "$WINLATOR_SHA"

test -f app/build.gradle
test -f app/src/main/java/com/winlator/XServerDisplayActivity.java
grep -q 'versionName "11.2"' app/build.gradle

echo "Winlator 11.2 source ready at $WINLATOR_DIR"
