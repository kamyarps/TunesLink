# Sourced by Android emulator CI scripts to keep Gboard first-run UI off the app.
android_emulator_suppress_gboard_first_run() {
  local adb_command="${1:-adb}"
  local ime_id
  local ime_list
  local aosp_ime=""
  local gboard_ime=""
  # Prefer AOSP LatinIME when the image still ships it, then disable Gboard so
  # its Theme first-run activity cannot cover the app on API 31 google_apis.
  # Include disabled keyboards so repeated smoke/integration runs can recover
  # from an earlier selection instead of leaving a voice-only IME active.
  ime_list="$("$adb_command" shell ime list -a -s 2>/dev/null | tr -d '\r' || true)"
  while IFS= read -r ime_id; do
    [[ -n "$ime_id" ]] || continue
    case "$ime_id" in
      com.android.inputmethod.latin/*)
        aosp_ime="$ime_id"
        ;;
      com.google.android.inputmethod.latin/*)
        gboard_ime="$ime_id"
        ;;
    esac
  done <<< "$ime_list"
  if [[ -z "$aosp_ime" ]]; then
    # Some API 31 images have only Gboard plus voice input. Disabling Gboard
    # selects the voice IME, which can swallow Back even while it is hidden.
    # Keep a real keyboard; dump_ui handles any Gboard first-run overlay.
    if [[ -n "$gboard_ime" ]]; then
      "$adb_command" shell ime enable "$gboard_ime" >/dev/null
      "$adb_command" shell ime set "$gboard_ime" >/dev/null
    fi
    return
  fi
  "$adb_command" shell ime enable "$aosp_ime" >/dev/null
  "$adb_command" shell ime set "$aosp_ime" >/dev/null
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
