#!/usr/bin/env bash
# Build, install and open tAI-Chi on the test tablet (macOS / Linux).
# Windows: use tablet.cmd instead. Both run the same Gradle tasks (app/tablet.gradle.kts).
#
#   ./tablet.sh            build, install and open the app (same as: ./tablet.sh run)
#   ./tablet.sh wifi       tablet plugged in by USB: switch to Wi-Fi, then unplug
#   ./tablet.sh devices    show connected tablets
#   ./tablet.sh pull       copy recorded sessions from the tablet to tAI-Chi-repo/recordings/
#   ./tablet.sh uninstall  remove the app from the tablet (add "force" if it holds recordings)
#   ./tablet.sh test       run the pose-engine unit tests
set -euo pipefail
cd "$(dirname "$0")"
extra=""

case "${1:-run}" in
  run)       task=":app:tabletRun" ;;
  wifi)      task=":app:tabletWifi" ;;
  devices)   task=":app:tabletDevices" ;;
  pull)      task=":app:tabletPull" ;;
  uninstall) task=":app:tabletUninstall"
             if [ "${2:-}" = "force" ]; then extra="-PtabletForce=true"; fi ;;
  test)      task=":pose-engine:testDebugUnitTest" ;;
  -h|--help|help) sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "Unknown command '$1'. Use: run, wifi, devices, pull, uninstall, test" >&2; exit 1 ;;
esac

# --- Java: use a full JDK (Android Studio's bundled one). Some JREs, such as the one
# inside VS Code's Java extension, lack jlink and break the Android build.
jdk=""
for d in "${JAVA_HOME:-}" \
         "/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
         "$HOME/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
         "/opt/android-studio/jbr" "$HOME/android-studio/jbr" "/snap/android-studio/current/jbr"; do
  if [ -n "$d" ] && [ -x "$d/bin/jlink" ]; then jdk="$d"; break; fi
done
if [ -z "$jdk" ]; then
  echo "Could not find a full Java JDK. Install Android Studio (developer.android.com/studio)," >&2
  echo "or set JAVA_HOME to a JDK that contains bin/jlink." >&2
  exit 1
fi

# --- Android SDK: local.properties is per computer. Create or fix it if needed.
sdk=""
if [ -f local.properties ]; then
  sdk="$(sed -n 's/^sdk\.dir=//p' local.properties | sed 's/\\:/:/g; s/\\\\/\\/g' | tail -1)"
fi
if [ -z "$sdk" ] || [ ! -d "$sdk/platform-tools" ]; then
  sdk=""
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [ -n "$d" ] && [ -d "$d/platform-tools" ]; then sdk="$d"; break; fi
  done
  if [ -z "$sdk" ]; then
    echo "Could not find the Android SDK. Open Android Studio once so it downloads the SDK," >&2
    echo "or set ANDROID_HOME to your SDK folder." >&2
    exit 1
  fi
  printf 'sdk.dir=%s\n' "$sdk" > local.properties
  echo "Wrote local.properties (sdk.dir=$sdk)"
fi

export JAVA_HOME="$jdk"
exec bash ./gradlew "$task" $extra \
  -Porg.gradle.java.installations.auto-detect=false \
  "-Porg.gradle.java.installations.paths=$jdk"
