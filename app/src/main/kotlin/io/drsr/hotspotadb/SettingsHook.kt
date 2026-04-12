package io.drsr.hotspotadb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.net.wifi.WifiManager
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
        // Android 16+: AdbWirelessDebuggingPreferenceController; Android 15: WirelessDebuggingPreferenceController
        val controllerClass =
            try {
                XposedHelpers.findClass(
                    "com.android.settings.development.AdbWirelessDebuggingPreferenceController",
                    lpparam.classLoader,
                ).name
            } catch (_: XposedHelpers.ClassNotFoundError) {
                "com.android.settings.development.WirelessDebuggingPreferenceController"
            }
        try {
            XposedHelpers.findAndHookMethod(
                controllerClass,
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
                },
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
                        val context =
                            XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context
                                ?: return
                        if (!HotspotHelper.isHotspotActive(context)) return
                        val ip = HotspotHelper.getHotspotIpAddress(context) ?: return
                        param.result = ip
                    }
                },
            )
        } catch (e: Exception) {
            XposedBridge.log("HotspotAdb: failed to hook getIpv4Address: $e")
        }
    }

    private fun hookWifiTetherSettings(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val tetherSettingsClass =
                XposedHelpers.findClass(
                    "com.android.settings.wifi.tether.WifiTetherSettings",
                    lpparam.classLoader,
                )

            XposedHelpers.findAndHookMethod(
                tetherSettingsClass,
                "onStart",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            injectWirelessDebuggingPref(param.thisObject, lpparam)
                        } catch (e: Throwable) {
                            XposedBridge.log("HotspotAdb: failed to inject preference: $e")
                        }
                    }
                },
            )
        } catch (e: Exception) {
            XposedBridge.log("HotspotAdb: failed to hook WifiTetherSettings: $e")
        }
    }

    private fun injectWirelessDebuggingPref(
        fragment: Any,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ) {
        val screen =
            XposedHelpers.callMethod(fragment, "getPreferenceScreen") ?: run {
                XposedBridge.log("HotspotAdb: preferenceScreen is null")
                return
            }
        if (XposedHelpers.callMethod(screen, "findPreference", "hotspot_adb_wireless_debugging") != null) return
        val context = XposedHelpers.callMethod(screen, "getContext") as Context

        // PrimarySwitchPreference — split toggle+button, same as Developer Options
        val primarySwitchClass =
            XposedHelpers.findClass(
                "com.android.settingslib.PrimarySwitchPreference",
                lpparam.classLoader,
            )
        val pref = primarySwitchClass.getConstructor(Context::class.java).newInstance(context)

        XposedHelpers.callMethod(pref, "setKey", "hotspot_adb_wireless_debugging")
        XposedHelpers.callMethod(pref, "setTitle", "Wireless debugging")
        updatePrefState(context, pref)

        // Switch toggle listener
        val changeListenerClass =
            XposedHelpers.findClass(
                "androidx.preference.Preference\$OnPreferenceChangeListener",
                lpparam.classLoader,
            )
        val changeProxy =
            java.lang.reflect.Proxy.newProxyInstance(
                lpparam.classLoader,
                arrayOf(changeListenerClass),
            ) { _, _, args ->
                val newValue = args!![1] as Boolean
                Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, if (newValue) 1 else 0)
                updatePrefState(context, pref)
                true
            }
        XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", changeProxy)

        // Click on the left side opens Wireless Debugging screen
        val clickListenerClass =
            XposedHelpers.findClass(
                "androidx.preference.Preference\$OnPreferenceClickListener",
                lpparam.classLoader,
            )
        val clickProxy =
            java.lang.reflect.Proxy.newProxyInstance(
                lpparam.classLoader,
                arrayOf(clickListenerClass),
            ) { _, _, _ ->
                try {
                    val subSettingsClass = XposedHelpers.findClass("com.android.settings.SubSettings", context.classLoader)
                    // Android 16+: AdbWirelessDebuggingFragment; Android 15: WirelessDebuggingFragment
                    val fragmentClass =
                        try {
                            lpparam.classLoader.loadClass(
                                "com.android.settings.development.AdbWirelessDebuggingFragment",
                            ).name
                        } catch (_: ClassNotFoundException) {
                            "com.android.settings.development.WirelessDebuggingFragment"
                        }
                    val intent = android.content.Intent(context, subSettingsClass)
                    intent.putExtra(":settings:show_fragment", fragmentClass)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    XposedBridge.log("HotspotAdb: failed to open wireless debugging: $e")
                }
                true
            }
        XposedHelpers.callMethod(pref, "setOnPreferenceClickListener", clickProxy)

        XposedHelpers.callMethod(screen, "addPreference", pref)

        // Sync state from Developer Options; observer stored on the fragment to avoid leaks
        val observerTag = "hotspot_adb_observer"
        if (XposedHelpers.getAdditionalInstanceField(fragment, observerTag) == null) {
            val uri = Settings.Global.getUriFor(ADB_WIFI_ENABLED)
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(
                        selfChange: Boolean,
                        uri: Uri?,
                    ) {
                        updatePrefState(context, pref)
                    }
                }
            context.contentResolver.registerContentObserver(uri, false, observer)
            XposedHelpers.setAdditionalInstanceField(fragment, observerTag, observer)
        }

        // Also watch hotspot state changes (on/off) to update the label
        val receiverTag = "hotspot_adb_receiver"
        if (XposedHelpers.getAdditionalInstanceField(fragment, receiverTag) == null) {
            val handler = Handler(Looper.getMainLooper())
            val updatePref = Runnable { updatePrefState(context, pref) }
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        ctx: Context,
                        intent: Intent,
                    ) {
                        // Run immediately and again after a delay — the hotspot interface
                        // IP may not be available yet when the AP state changes.
                        updatePref.run()
                        handler.postDelayed(updatePref, 1000)
                    }
                }
            context.registerReceiver(
                receiver,
                IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION).apply {
                    addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
                },
            )
            XposedHelpers.setAdditionalInstanceField(fragment, receiverTag, receiver)
        }

        XposedBridge.log("HotspotAdb: added wireless debugging toggle to hotspot settings")
    }

    private fun updatePrefState(
        context: Context,
        pref: Any,
    ) {
        val on = isAdbWifiEnabled(context)
        XposedHelpers.callMethod(pref, "setChecked", on)
        XposedHelpers.callMethod(pref, "setSummary", getWirelessDebuggingSummary(context, on))
    }

    private fun isAdbWifiEnabled(context: Context): Boolean {
        return Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1
    }

    private fun getWirelessDebuggingSummary(
        context: Context,
        enabled: Boolean,
    ): String {
        if (!enabled) return ""
        val ip =
            HotspotHelper.getHotspotIpAddress(context)
                ?: HotspotHelper.getAnyWlanIp()
                ?: return ""
        val port = getAdbWirelessPort()
        return if (port > 0) "$ip:$port" else ip
    }

    private fun getAdbWirelessPort(): Int {
        return try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val binder =
                serviceManagerClass.getMethod("getService", String::class.java)
                    .invoke(null, "adb")
            val iAdbManagerStub = Class.forName("android.debug.IAdbManager\$Stub")
            val adbService =
                iAdbManagerStub.getMethod("asInterface", android.os.IBinder::class.java)
                    .invoke(null, binder)
            adbService.javaClass.getMethod("getAdbWirelessPort").invoke(adbService) as Int
        } catch (_: Throwable) {
            -1
        }
    }
}
