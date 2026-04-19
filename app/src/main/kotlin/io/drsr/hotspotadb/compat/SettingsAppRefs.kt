package io.drsr.hotspotadb.compat

/**
 * AOSP Settings app class names that diverge between Android versions.
 *
 * Android 11–15: `WirelessDebuggingFragment` / `WirelessDebuggingPreferenceController`.
 * Android 16+: renamed with `Adb` prefix.
 */
object SettingsAppRefs {
    const val IP_PREF_KEY = "adb_ip_addr_pref"

    private const val CONTROLLER_TOPLEVEL = "com.android.settings.development.AdbWirelessDebuggingPreferenceController"
    private const val CONTROLLER_LEGACY = "com.android.settings.development.WirelessDebuggingPreferenceController"
    private const val FRAGMENT_TOPLEVEL = "com.android.settings.development.AdbWirelessDebuggingFragment"
    private const val FRAGMENT_LEGACY = "com.android.settings.development.WirelessDebuggingFragment"

    fun resolveControllerClassName(loader: ClassLoader): String =
        ClassRefs.tryFindClass(loader, CONTROLLER_TOPLEVEL, CONTROLLER_LEGACY)?.name
            ?: CONTROLLER_LEGACY

    fun resolveFragmentClassName(loader: ClassLoader): String =
        ClassRefs.tryFindClass(loader, FRAGMENT_TOPLEVEL, FRAGMENT_LEGACY)?.name
            ?: FRAGMENT_LEGACY
}
