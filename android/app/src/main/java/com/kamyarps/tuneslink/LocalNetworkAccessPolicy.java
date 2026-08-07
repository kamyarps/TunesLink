package com.kamyarps.tuneslink;

final class LocalNetworkAccessPolicy {
    static final int ANDROID_17_API = 37;

    private LocalNetworkAccessPolicy() {
    }

    static boolean isRuntimePermissionRequired(int deviceSdk, int targetSdk) {
        return deviceSdk >= ANDROID_17_API && targetSdk >= ANDROID_17_API;
    }

    static boolean hasAccess(int deviceSdk, int targetSdk, boolean permissionGranted) {
        return !isRuntimePermissionRequired(deviceSdk, targetSdk) || permissionGranted;
    }
}
