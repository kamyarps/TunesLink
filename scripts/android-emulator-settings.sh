# Sourced by the smoke/integration scripts for real font scaling on older Android.
android_emulator_prepare_settings() {
  local adb_command="$1"
  local workspace="$2"
  local sdk_int
  android_emulator_legacy_font_scale=0
  sdk_int="$("$adb_command" shell getprop ro.build.version.sdk | tr -d '\r')"
  if (( sdk_int >= 26 )); then return; fi

  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  local properties="$workspace/android/gradle.properties"
  local build_tools platform d8
  local output="$workspace/android/app/build/intermediates/emulator-settings"
  build_tools="$(sed -n 's/^TunesLink.buildToolsVersion=//p' "$properties" | tr -d '\r')"
  platform="$(sed -n 's/^TunesLink.androidPlatformPackage=//p' "$properties" | tr -d '\r')"
  d8="$sdk/build-tools/$build_tools/d8"
  if [[ -f "$d8.bat" ]]; then d8="$d8.bat"; fi
  mkdir -p "$output"
  javac -source 8 -target 8 -Xlint:-options \
    -cp "$sdk/platforms/$platform/android.jar" -d "$output" \
    "$workspace/test-support/AndroidEmulatorSettings.java" || return
  "$d8" --min-api 23 --lib "$sdk/platforms/$platform/android.jar" \
    --output "$output/device-settings.zip" "$output/AndroidEmulatorSettings.class" || return
  "$adb_command" push "$output/device-settings.zip" /data/local/tmp/tuneslink-device-settings.zip >/dev/null || return
  android_emulator_legacy_font_scale=1
}

android_emulator_set_font_scale() {
  local adb_command="$1"
  local scale="$2"
  if [[ ! "$scale" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
    printf 'Invalid font scale: %s\n' "$scale" >&2
    return 1
  fi
  if [[ "${android_emulator_legacy_font_scale:-0}" == "1" ]]; then
    "$adb_command" shell env CLASSPATH=/data/local/tmp/tuneslink-device-settings.zip \
      app_process /system/bin AndroidEmulatorSettings "$scale"
  else
    "$adb_command" shell settings put system font_scale "$scale"
  fi
}
