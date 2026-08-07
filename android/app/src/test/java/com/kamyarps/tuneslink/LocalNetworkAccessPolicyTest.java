package com.kamyarps.tuneslink;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LocalNetworkAccessPolicyTest {
    @Test
    public void permissionIsRequiredOnlyForAndroid17AppsOnAndroid17() {
        assertFalse(LocalNetworkAccessPolicy.isRuntimePermissionRequired(36, 37));
        assertFalse(LocalNetworkAccessPolicy.isRuntimePermissionRequired(37, 36));
        assertTrue(LocalNetworkAccessPolicy.isRuntimePermissionRequired(37, 37));
        assertTrue(LocalNetworkAccessPolicy.isRuntimePermissionRequired(38, 37));
    }

    @Test
    public void legacyTargetsKeepImplicitAccess() {
        assertTrue(LocalNetworkAccessPolicy.hasAccess(37, 36, false));
    }

    @Test
    public void Android17TargetRequiresAGrant() {
        assertFalse(LocalNetworkAccessPolicy.hasAccess(37, 37, false));
        assertTrue(LocalNetworkAccessPolicy.hasAccess(37, 37, true));
    }
}
