package com.music.vibe.playback;

import android.content.pm.PackageManager;

/**
 * A delegating PackageManager that intercepts {@link PackageManager#FEATURE_AUTOMOTIVE}
 * queries from Media3 session initialization, preventing unnecessary ContentResolver
 * queries for androidx.car.app.connection on platforms without Android Auto.
 */
@SuppressWarnings("deprecation")
public class SafeAutomotivePackageManager extends PackageManager {
    private final PackageManager base;

    public SafeAutomotivePackageManager(PackageManager base) {
        this.base = base;
    }

    @Override
    public void addPackageToPreferred(java.lang.String p0) {
        this.base.addPackageToPreferred(p0);
    }

    @Override
    public boolean addPermission(android.content.pm.PermissionInfo p0) {
        return this.base.addPermission(p0);
    }

    @Override
    public boolean addPermissionAsync(android.content.pm.PermissionInfo p0) {
        return this.base.addPermissionAsync(p0);
    }

    @Override
    public void addPreferredActivity(android.content.IntentFilter p0, int p1, android.content.ComponentName[] p2, android.content.ComponentName p3) {
        this.base.addPreferredActivity(p0, p1, p2, p3);
    }

    @Override
    public boolean canRequestPackageInstalls() {
        return this.base.canRequestPackageInstalls();
    }

    @Override
    public java.lang.String[] canonicalToCurrentPackageNames(java.lang.String[] p0) {
        return this.base.canonicalToCurrentPackageNames(p0);
    }

    @Override
    public int checkPermission(java.lang.String p0, java.lang.String p1) {
        return this.base.checkPermission(p0, p1);
    }

    @Override
    public int checkSignatures(int p0, int p1) {
        return this.base.checkSignatures(p0, p1);
    }

    @Override
    public int checkSignatures(java.lang.String p0, java.lang.String p1) {
        return this.base.checkSignatures(p0, p1);
    }

    @Override
    public void clearInstantAppCookie() {
        this.base.clearInstantAppCookie();
    }

    @Override
    public void clearPackagePreferredActivities(java.lang.String p0) {
        this.base.clearPackagePreferredActivities(p0);
    }

    @Override
    public java.lang.String[] currentToCanonicalPackageNames(java.lang.String[] p0) {
        return this.base.currentToCanonicalPackageNames(p0);
    }

    @Override
    public void extendVerificationTimeout(int p0, int p1, long p2) {
        this.base.extendVerificationTimeout(p0, p1, p2);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityBanner(android.content.ComponentName p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getActivityBanner(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityBanner(android.content.Intent p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getActivityBanner(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityIcon(android.content.ComponentName p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getActivityIcon(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityIcon(android.content.Intent p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getActivityIcon(p0);
    }

    @Override
    public android.content.pm.ActivityInfo getActivityInfo(android.content.ComponentName p0, android.content.pm.PackageManager.ComponentInfoFlags p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getActivityInfo(p0, p1);
    }

    @Override
    public android.content.pm.ActivityInfo getActivityInfo(android.content.ComponentName p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getActivityInfo(p0, p1);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityLogo(android.content.ComponentName p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getActivityLogo(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getActivityLogo(android.content.Intent p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getActivityLogo(p0);
    }

    @Override
    public java.util.List<android.content.pm.PermissionGroupInfo> getAllPermissionGroups(int p0) {
        return this.base.getAllPermissionGroups(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationBanner(android.content.pm.ApplicationInfo p0) {
        return this.base.getApplicationBanner(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationBanner(java.lang.String p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getApplicationBanner(p0);
    }

    @Override
    public int getApplicationEnabledSetting(java.lang.String p0) {
        return this.base.getApplicationEnabledSetting(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationIcon(android.content.pm.ApplicationInfo p0) {
        return this.base.getApplicationIcon(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationIcon(java.lang.String p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getApplicationIcon(p0);
    }

    @Override
    public android.content.pm.ApplicationInfo getApplicationInfo(java.lang.String p0, android.content.pm.PackageManager.ApplicationInfoFlags p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getApplicationInfo(p0, p1);
    }

    @Override
    public android.content.pm.ApplicationInfo getApplicationInfo(java.lang.String p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getApplicationInfo(p0, p1);
    }

    @Override
    public java.lang.CharSequence getApplicationLabel(android.content.pm.ApplicationInfo p0) {
        return this.base.getApplicationLabel(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationLogo(android.content.pm.ApplicationInfo p0) {
        return this.base.getApplicationLogo(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getApplicationLogo(java.lang.String p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getApplicationLogo(p0);
    }

    @Override
    public android.content.pm.ChangedPackages getChangedPackages(int p0) {
        return this.base.getChangedPackages(p0);
    }

    @Override
    public int getComponentEnabledSetting(android.content.ComponentName p0) {
        return this.base.getComponentEnabledSetting(p0);
    }

    @Override
    public android.graphics.drawable.Drawable getDefaultActivityIcon() {
        return this.base.getDefaultActivityIcon();
    }

    @Override
    public android.graphics.drawable.Drawable getDrawable(java.lang.String p0, int p1, android.content.pm.ApplicationInfo p2) {
        return this.base.getDrawable(p0, p1, p2);
    }

    @Override
    public java.util.List<android.content.pm.ApplicationInfo> getInstalledApplications(android.content.pm.PackageManager.ApplicationInfoFlags p0) {
        return this.base.getInstalledApplications(p0);
    }

    @Override
    public java.util.List<android.content.pm.ApplicationInfo> getInstalledApplications(int p0) {
        return this.base.getInstalledApplications(p0);
    }

    @Override
    public java.util.List<android.content.pm.PackageInfo> getInstalledPackages(android.content.pm.PackageManager.PackageInfoFlags p0) {
        return this.base.getInstalledPackages(p0);
    }

    @Override
    public java.util.List<android.content.pm.PackageInfo> getInstalledPackages(int p0) {
        return this.base.getInstalledPackages(p0);
    }

    @Override
    public java.lang.String getInstallerPackageName(java.lang.String p0) {
        return this.base.getInstallerPackageName(p0);
    }

    @Override
    public byte[] getInstantAppCookie() {
        return this.base.getInstantAppCookie();
    }

    @Override
    public int getInstantAppCookieMaxBytes() {
        return this.base.getInstantAppCookieMaxBytes();
    }

    @Override
    public android.content.pm.InstrumentationInfo getInstrumentationInfo(android.content.ComponentName p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getInstrumentationInfo(p0, p1);
    }

    @Override
    public android.content.Intent getLaunchIntentForPackage(java.lang.String p0) {
        return this.base.getLaunchIntentForPackage(p0);
    }

    @Override
    public android.content.Intent getLeanbackLaunchIntentForPackage(java.lang.String p0) {
        return this.base.getLeanbackLaunchIntentForPackage(p0);
    }

    @Override
    public java.lang.String getNameForUid(int p0) {
        return this.base.getNameForUid(p0);
    }

    @Override
    public android.content.pm.PackageInfo getPackageArchiveInfo(java.lang.String p0, android.content.pm.PackageManager.PackageInfoFlags p1) {
        return this.base.getPackageArchiveInfo(p0, p1);
    }

    @Override
    public int[] getPackageGids(java.lang.String p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getPackageGids(p0);
    }

    @Override
    public int[] getPackageGids(java.lang.String p0, android.content.pm.PackageManager.PackageInfoFlags p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getPackageGids(p0, p1);
    }

    @Override
    public int[] getPackageGids(java.lang.String p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getPackageGids(p0, p1);
    }

    @Override
    public android.content.pm.PackageInfo getPackageInfo(android.content.pm.VersionedPackage p0, android.content.pm.PackageManager.PackageInfoFlags p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getPackageInfo(p0, p1);
    }

    @Override
    public android.content.pm.PackageInfo getPackageInfo(android.content.pm.VersionedPackage p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getPackageInfo(p0, p1);
    }

    @Override
    public android.content.pm.PackageInfo getPackageInfo(java.lang.String p0, android.content.pm.PackageManager.PackageInfoFlags p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getPackageInfo(p0, p1);
    }

    @Override
    public android.content.pm.PackageInfo getPackageInfo(java.lang.String p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getPackageInfo(p0, p1);
    }

    @Override
    public android.content.pm.PackageInstaller getPackageInstaller() {
        return this.base.getPackageInstaller();
    }

    @Override
    public int getPackageUid(java.lang.String p0, android.content.pm.PackageManager.PackageInfoFlags p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getPackageUid(p0, p1);
    }

    @Override
    public int getPackageUid(java.lang.String p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getPackageUid(p0, p1);
    }

    @Override
    public java.lang.String[] getPackagesForUid(int p0) {
        return this.base.getPackagesForUid(p0);
    }

    @Override
    public java.util.List<android.content.pm.PackageInfo> getPackagesHoldingPermissions(java.lang.String[] p0, android.content.pm.PackageManager.PackageInfoFlags p1) {
        return this.base.getPackagesHoldingPermissions(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.PackageInfo> getPackagesHoldingPermissions(java.lang.String[] p0, int p1) {
        return this.base.getPackagesHoldingPermissions(p0, p1);
    }

    @Override
    public android.content.pm.PermissionGroupInfo getPermissionGroupInfo(java.lang.String p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getPermissionGroupInfo(p0, p1);
    }

    @Override
    public android.content.pm.PermissionInfo getPermissionInfo(java.lang.String p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getPermissionInfo(p0, p1);
    }

    @Override
    public int getPreferredActivities(java.util.List<android.content.IntentFilter> p0, java.util.List<android.content.ComponentName> p1, java.lang.String p2) {
        return this.base.getPreferredActivities(p0, p1, p2);
    }

    @Override
    public java.util.List<android.content.pm.PackageInfo> getPreferredPackages(int p0) {
        return this.base.getPreferredPackages(p0);
    }

    @Override
    public android.content.pm.ProviderInfo getProviderInfo(android.content.ComponentName p0, android.content.pm.PackageManager.ComponentInfoFlags p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getProviderInfo(p0, p1);
    }

    @Override
    public android.content.pm.ProviderInfo getProviderInfo(android.content.ComponentName p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getProviderInfo(p0, p1);
    }

    @Override
    public android.content.pm.ActivityInfo getReceiverInfo(android.content.ComponentName p0, android.content.pm.PackageManager.ComponentInfoFlags p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getReceiverInfo(p0, p1);
    }

    @Override
    public android.content.pm.ActivityInfo getReceiverInfo(android.content.ComponentName p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getReceiverInfo(p0, p1);
    }

    @Override
    public android.content.res.Resources getResourcesForActivity(android.content.ComponentName p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getResourcesForActivity(p0);
    }

    @Override
    public android.content.res.Resources getResourcesForApplication(android.content.pm.ApplicationInfo p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getResourcesForApplication(p0);
    }

    @Override
    public android.content.res.Resources getResourcesForApplication(java.lang.String p0) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getResourcesForApplication(p0);
    }

    @Override
    public android.content.pm.ServiceInfo getServiceInfo(android.content.ComponentName p0, android.content.pm.PackageManager.ComponentInfoFlags p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getServiceInfo(p0, p1);
    }

    @Override
    public android.content.pm.ServiceInfo getServiceInfo(android.content.ComponentName p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.getServiceInfo(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.SharedLibraryInfo> getSharedLibraries(android.content.pm.PackageManager.PackageInfoFlags p0) {
        return this.base.getSharedLibraries(p0);
    }

    @Override
    public java.util.List<android.content.pm.SharedLibraryInfo> getSharedLibraries(int p0) {
        return this.base.getSharedLibraries(p0);
    }

    @Override
    public android.content.pm.FeatureInfo[] getSystemAvailableFeatures() {
        return this.base.getSystemAvailableFeatures();
    }

    @Override
    public java.lang.String[] getSystemSharedLibraryNames() {
        return this.base.getSystemSharedLibraryNames();
    }

    @Override
    public java.lang.CharSequence getText(java.lang.String p0, int p1, android.content.pm.ApplicationInfo p2) {
        return this.base.getText(p0, p1, p2);
    }

    @Override
    public android.graphics.drawable.Drawable getUserBadgedDrawableForDensity(android.graphics.drawable.Drawable p0, android.os.UserHandle p1, android.graphics.Rect p2, int p3) {
        return this.base.getUserBadgedDrawableForDensity(p0, p1, p2, p3);
    }

    @Override
    public android.graphics.drawable.Drawable getUserBadgedIcon(android.graphics.drawable.Drawable p0, android.os.UserHandle p1) {
        return this.base.getUserBadgedIcon(p0, p1);
    }

    @Override
    public java.lang.CharSequence getUserBadgedLabel(java.lang.CharSequence p0, android.os.UserHandle p1) {
        return this.base.getUserBadgedLabel(p0, p1);
    }

    @Override
    public android.content.res.XmlResourceParser getXml(java.lang.String p0, int p1, android.content.pm.ApplicationInfo p2) {
        return this.base.getXml(p0, p1, p2);
    }

    @Override
    public boolean hasSystemFeature(String name) {
        if (PackageManager.FEATURE_AUTOMOTIVE.equals(name)) {
            return true;
        }
        return this.base.hasSystemFeature(name);
    }

    @Override
    public boolean hasSystemFeature(String name, int version) {
        if (PackageManager.FEATURE_AUTOMOTIVE.equals(name)) {
            return true;
        }
        return this.base.hasSystemFeature(name, version);
    }

    @Override
    public boolean isInstantApp() {
        return this.base.isInstantApp();
    }

    @Override
    public boolean isInstantApp(java.lang.String p0) {
        return this.base.isInstantApp(p0);
    }

    @Override
    public boolean isPermissionRevokedByPolicy(java.lang.String p0, java.lang.String p1) {
        return this.base.isPermissionRevokedByPolicy(p0, p1);
    }

    @Override
    public boolean isSafeMode() {
        return this.base.isSafeMode();
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryBroadcastReceivers(android.content.Intent p0, android.content.pm.PackageManager.ResolveInfoFlags p1) {
        return this.base.queryBroadcastReceivers(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryBroadcastReceivers(android.content.Intent p0, int p1) {
        return this.base.queryBroadcastReceivers(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.ProviderInfo> queryContentProviders(java.lang.String p0, int p1, android.content.pm.PackageManager.ComponentInfoFlags p2) {
        return this.base.queryContentProviders(p0, p1, p2);
    }

    @Override
    public java.util.List<android.content.pm.ProviderInfo> queryContentProviders(java.lang.String p0, int p1, int p2) {
        return this.base.queryContentProviders(p0, p1, p2);
    }

    @Override
    public java.util.List<android.content.pm.InstrumentationInfo> queryInstrumentation(java.lang.String p0, int p1) {
        return this.base.queryInstrumentation(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentActivities(android.content.Intent p0, android.content.pm.PackageManager.ResolveInfoFlags p1) {
        return this.base.queryIntentActivities(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentActivities(android.content.Intent p0, int p1) {
        return this.base.queryIntentActivities(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentActivityOptions(android.content.ComponentName p0, android.content.Intent[] p1, android.content.Intent p2, int p3) {
        return this.base.queryIntentActivityOptions(p0, p1, p2, p3);
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentActivityOptions(android.content.ComponentName p0, java.util.List<android.content.Intent> p1, android.content.Intent p2, android.content.pm.PackageManager.ResolveInfoFlags p3) {
        return this.base.queryIntentActivityOptions(p0, p1, p2, p3);
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentContentProviders(android.content.Intent p0, android.content.pm.PackageManager.ResolveInfoFlags p1) {
        return this.base.queryIntentContentProviders(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentContentProviders(android.content.Intent p0, int p1) {
        return this.base.queryIntentContentProviders(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentServices(android.content.Intent p0, android.content.pm.PackageManager.ResolveInfoFlags p1) {
        return this.base.queryIntentServices(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.ResolveInfo> queryIntentServices(android.content.Intent p0, int p1) {
        return this.base.queryIntentServices(p0, p1);
    }

    @Override
    public java.util.List<android.content.pm.PermissionInfo> queryPermissionsByGroup(java.lang.String p0, int p1) throws android.content.pm.PackageManager.NameNotFoundException {
        return this.base.queryPermissionsByGroup(p0, p1);
    }

    @Override
    public void removePackageFromPreferred(java.lang.String p0) {
        this.base.removePackageFromPreferred(p0);
    }

    @Override
    public void removePermission(java.lang.String p0) {
        this.base.removePermission(p0);
    }

    @Override
    public android.content.pm.ResolveInfo resolveActivity(android.content.Intent p0, android.content.pm.PackageManager.ResolveInfoFlags p1) {
        return this.base.resolveActivity(p0, p1);
    }

    @Override
    public android.content.pm.ResolveInfo resolveActivity(android.content.Intent p0, int p1) {
        return this.base.resolveActivity(p0, p1);
    }

    @Override
    public android.content.pm.ProviderInfo resolveContentProvider(java.lang.String p0, android.content.pm.PackageManager.ComponentInfoFlags p1) {
        return this.base.resolveContentProvider(p0, p1);
    }

    @Override
    public android.content.pm.ProviderInfo resolveContentProvider(java.lang.String p0, int p1) {
        return this.base.resolveContentProvider(p0, p1);
    }

    @Override
    public android.content.pm.ResolveInfo resolveService(android.content.Intent p0, android.content.pm.PackageManager.ResolveInfoFlags p1) {
        return this.base.resolveService(p0, p1);
    }

    @Override
    public android.content.pm.ResolveInfo resolveService(android.content.Intent p0, int p1) {
        return this.base.resolveService(p0, p1);
    }

    @Override
    public void setApplicationCategoryHint(java.lang.String p0, int p1) {
        this.base.setApplicationCategoryHint(p0, p1);
    }

    @Override
    public void setApplicationEnabledSetting(java.lang.String p0, int p1, int p2) {
        this.base.setApplicationEnabledSetting(p0, p1, p2);
    }

    @Override
    public void setComponentEnabledSetting(android.content.ComponentName p0, int p1, int p2) {
        this.base.setComponentEnabledSetting(p0, p1, p2);
    }

    @Override
    public void setInstallerPackageName(java.lang.String p0, java.lang.String p1) {
        this.base.setInstallerPackageName(p0, p1);
    }

    @Override
    public void updateInstantAppCookie(byte[] p0) {
        this.base.updateInstantAppCookie(p0);
    }

    @Override
    public void verifyPendingInstall(int p0, int p1) {
        this.base.verifyPendingInstall(p0, p1);
    }

}
