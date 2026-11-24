#!/bin/sh
# Host tests of the touch HUD (HudModel.java: layout format, presets, geometry, stick maths; HudTouch.java: multi-touch
# routing). They use no Android classes, so a JDK is all they need.
set -e
here=$(cd "$(dirname "$0")" && pwd)
src="$here/../../android/app/src/main/java/com/dsrnative/app"
tmp=$(mktemp -d)
javac -d "$tmp" "$src/HudModel.java" "$src/HudTouch.java" "$here/HudModelTest.java" "$here/HudTouchTest.java"
java -cp "$tmp" com.dsrnative.app.HudModelTest
java -cp "$tmp" com.dsrnative.app.HudTouchTest
rm -rf "$tmp"
