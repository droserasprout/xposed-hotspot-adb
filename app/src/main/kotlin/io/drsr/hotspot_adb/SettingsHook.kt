package io.drsr.hotspot_adb

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object SettingsHook {

    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookIsWifiConnected(lpparam)
        hookGetIpv4Address(lpparam)
        hookWifiTetherSettings(lpparam)
    }

    private fun hookIsWifiConnected(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.settings.development.WirelessDebuggingPreferenceController",
                lpparam.classLoader,
                "isWifiConnected",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result == false) {
                            val context = param.args[0] as Context
                            if (HotspotHelper.isHotspotActive(context)) {
                                param.result = true
                                XposedBridge.log("HotspotAdb: isWifiConnected -> true (hotspot active)")
                            }
                        }
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("HotspotAdb: failed to hook isWifiConnected: $e")
        }
    }

    private fun hookGetIpv4Address(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.settings.development.AdbIpAddressPreferenceController",
                lpparam.classLoader,
                "getIpv4Address",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val result = param.result as? String
                        if (result.isNullOrEmpty()) {
                            val ip = HotspotHelper.getHotspotIpAddress()
                            if (ip != null) {
                                param.result = ip
                                XposedBridge.log("HotspotAdb: getIpv4Address -> $ip (hotspot)")
                            }
                        }
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("HotspotAdb: failed to hook getIpv4Address: $e")
        }
    }

    private fun hookWifiTetherSettings(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val tetherSettingsClass = XposedHelpers.findClass(
                "com.android.settings.wifi.tether.WifiTetherSettings",
                lpparam.classLoader
            )

            XposedHelpers.findAndHookMethod(
                tetherSettingsClass,
                "onStart",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            injectWirelessDebuggingPref(param.thisObject, lpparam)
                        } catch (e: Exception) {
                            XposedBridge.log("HotspotAdb: failed to inject preference: $e")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("HotspotAdb: failed to hook WifiTetherSettings: $e")
        }
    }

    private fun injectWirelessDebuggingPref(fragment: Any, lpparam: XC_LoadPackage.LoadPackageParam) {
        val screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen") ?: run {
            XposedBridge.log("HotspotAdb: preferenceScreen is null")
            return
        }
        if (XposedHelpers.callMethod(screen, "findPreference", "hotspot_adb_wireless_debugging") != null) return
        val context = XposedHelpers.callMethod(screen, "getContext") as Context

        // Use the runtime SwitchPreferenceCompat class from the Settings app classloader
        val switchPrefClass = XposedHelpers.findClass(
            "androidx.preference.SwitchPreferenceCompat",
            lpparam.classLoader
        )
        val pref = switchPrefClass.getConstructor(Context::class.java).newInstance(context)

        XposedHelpers.callMethod(pref, "setKey", "hotspot_adb_wireless_debugging")
        XposedHelpers.callMethod(pref, "setTitle", "Wireless debugging" as CharSequence)
        val enabled = isAdbWifiEnabled(context)
        XposedHelpers.callMethod(pref, "setChecked", enabled)
        XposedHelpers.callMethod(pref, "setSummary", getWirelessDebuggingSummary(context, enabled) as CharSequence)

        // Toggle listener
        val changeListenerClass = XposedHelpers.findClass(
            "androidx.preference.Preference\$OnPreferenceChangeListener",
            lpparam.classLoader
        )
        val changeProxy = java.lang.reflect.Proxy.newProxyInstance(
            lpparam.classLoader,
            arrayOf(changeListenerClass)
        ) { _, _, args ->
            val newValue = args!![1] as Boolean
            Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, if (newValue) 1 else 0)
            XposedHelpers.callMethod(pref, "setSummary", getWirelessDebuggingSummary(context, newValue) as CharSequence)
            true
        }
        XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", changeProxy)

        XposedHelpers.callMethod(screen, "addPreference", pref)

        // ContentObserver to sync state from Developer Options
        val uri = Settings.Global.getUriFor(ADB_WIFI_ENABLED)
        context.contentResolver.registerContentObserver(
            uri,
            false,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    val on = isAdbWifiEnabled(context)
                    XposedHelpers.callMethod(pref, "setChecked", on)
                    XposedHelpers.callMethod(pref, "setSummary", getWirelessDebuggingSummary(context, on) as CharSequence)
                }
            }
        )

        XposedBridge.log("HotspotAdb: added wireless debugging toggle to hotspot settings")
    }

    private fun isAdbWifiEnabled(context: Context): Boolean {
        return Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1
    }

    private fun getWirelessDebuggingSummary(context: Context, enabled: Boolean): String {
        if (!enabled) return "Enable to debug over this hotspot"
        val ip = HotspotHelper.getHotspotIpAddress() ?: return "Enabled"
        val port = Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled_port", -1)
        return if (port > 0) "$ip:$port" else "Enabled — $ip"
    }
}
