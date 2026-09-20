# Sourced by Android emulator CI scripts to keep Gboard first-run UI off the app.
android_emulator_suppress_gboard_first_run() {
  local adb_command="${1:-adb}"
  local ime_id
  local ime_list
  # Prefer AOSP LatinIME when the image still ships it, then disable Gboard so
  # its Theme first-run activity cannot cover the app on API 31 google_apis.
  ime_list="$("$adb_command" shell ime list -s 2>/dev/null | tr -d '\r' || true)"
  while IFS= read -r ime_id; do
    [[ -n "$ime_id" ]] || continue
    case "$ime_id" in
      com.android.inputmethod.latin/*)
        "$adb_command" shell ime enable "$ime_id" >/dev/null 2>&1 || true
        "$adb_command" shell ime set "$ime_id" >/dev/null 2>&1 || true
        ;;
    esac
  done <<< "$ime_list"
  while IFS= read -r ime_id; do
    [[ -n "$ime_id" ]] || continue
    case "$ime_id" in
      com.google.android.inputmethod.latin/*)
        "$adb_command" shell ime disable "$ime_id" >/dev/null 2>&1 || true
        ;;
    esac
  done <<< "$ime_list"
  "$adb_command" shell am force-stop com.google.android.inputmethod.latin >/dev/null 2>&1 || true
}
