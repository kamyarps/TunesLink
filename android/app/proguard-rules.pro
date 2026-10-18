# R8's default source-file attribute (r8-map-id-<hash>) lets Logcat retrace release stacks
# automatically; line numbers are kept by default since AGP 8.6. Keep the mapping file from
# each release build (CI uploads it as a private artifact).

# Navigation debug traces stay out of release builds. Timing (Log.i) and sanitized warnings remain:
# the README's troubleshooting steps rely on the TunesLinkTiming tag.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
