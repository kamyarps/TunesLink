import android.content.res.Configuration;

/** Shell-only helper for API 23-25 emulators; never included in the app. */
public final class AndroidEmulatorSettings {
    public static void main(String[] args) throws Exception {
        float scale = Float.parseFloat(args[0]);
        if (!(scale >= 0.5f && scale <= 2.0f)) {
            throw new IllegalArgumentException("Invalid font scale");
        }
        // Older Android does not observe `settings put system font_scale`.
        // Use the same service call as its Settings UI, under the shell UID's
        // CHANGE_CONFIGURATION permission. This predates hidden-API restrictions.
        Class<?> serviceType = Class.forName("android.app.IActivityManager");
        Object service = Class.forName("android.app.ActivityManagerNative")
                .getMethod("getDefault").invoke(null);
        Configuration config = (Configuration) serviceType.getMethod("getConfiguration")
                .invoke(service);
        config.fontScale = scale;
        serviceType.getMethod("updatePersistentConfiguration", Configuration.class)
                .invoke(service, config);
        Configuration applied = (Configuration) serviceType.getMethod("getConfiguration")
                .invoke(service);
        if (Math.abs(applied.fontScale - scale) > 0.001f) {
            throw new IllegalStateException("Font scale was not applied");
        }
        System.out.println("Applied font scale: " + applied.fontScale);
    }
}
