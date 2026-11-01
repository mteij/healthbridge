#!/usr/bin/env bash
# Installs the release APK, starts it and fails with the logcat crash trace if the app dies.
adb install -r apk/*.apk || exit 1
adb logcat -c
adb shell am start -W -n nl.mteij.healthbridge/.MainActivity
sleep 20
adb logcat -d > logcat.txt
if adb shell pidof nl.mteij.healthbridge > /dev/null && ! grep -q "FATAL EXCEPTION" logcat.txt; then
  echo "app is running"
  exit 0
fi
echo "=== app crashed ==="
grep -n -A45 "FATAL EXCEPTION" logcat.txt | head -120
grep -n "nl.mteij.healthbridge" logcat.txt | tail -30
exit 1
