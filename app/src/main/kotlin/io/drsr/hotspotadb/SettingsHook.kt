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
import io.drsr.hotspotadb.compat.SettingsAppRefs

object SettingsHook {
    private const val TAG_WTS_OBSERVER = "hotspot_adb_observer"
    private const val TAG_WTS_RECEIVER = "hotspot_adb_receiver"
    private const val TAG_WD_OBSERVER = "hotspot_adb_fixed_visibility"

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookIsWifiConnected(lpparam)
        hookGetIpv4Address(lpparam)
        hookGetAdbWirelessPort(lpparam)
        hookWifiTetherSettings(lpparam)
        hookWirelessDebuggingFragment(lpparam)
        hookFragmentCleanup(lpparam)
    }

    private fun hookFragmentCleanup(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Unregister observers/receivers we attached in injection hooks. Both target
        // fragments extend DashboardFragment so one hook covers them. Android 16 QPR
        // dropped onDestroyView from DashboardFragment; fall back to onStop, which
        // pairs symmetrically with the onStart-time registration.
        val cleanup =
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    cleanupFragment(param.thisObject)
                }
            }
        for (method in listOf("onDestroyView", "onStop")) {
            try {
                XposedHelpers.findAndHookMethod(
                    "com.android.settings.dashboard.DashboardFragment",
                    lpparam.classLoader,
                    method,
                    cleanup,
                )
                return
            } catch (e: Throwable) {
                XposedBridge.log("HotspotAdb: DashboardFragment.$method unavailable: $e")
            }
        }
    }

    private fun cleanupFragment(fragment: Any) {
        val context =
            XposedHelpers.callMethod(fragment, "getContext") as? Context ?: return
        val resolver = context.contentResolver
        for (tag in listOf(TAG_WTS_OBSERVER, TAG_WD_OBSERVER)) {
            val observer =
                XposedHelpers.getAdditionalInstanceField(fragment, tag) as? ContentObserver
                    ?: continue
            try {
                resolver.unregisterContentObserver(observer)
            } catch (e: Throwable) {
                XposedBridge.log("HotspotAdb: unregister $tag failed: $e")
            }
            XposedHelpers.removeAdditionalInstanceField(fragment, tag)
        }
        val receiver =
            XposedHelpers.getAdditionalInstanceField(fragment, TAG_WTS_RECEIVER)
                as? BroadcastReceiver
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Throwable) {
                XposedBridge.log("HotspotAdb: unregister $TAG_WTS_RECEIVER failed: $e")
            }
            XposedHelpers.removeAdditionalInstanceField(fragment, TAG_WTS_RECEIVER)
        }
    }

    private fun hookIsWifiConnected(lpparam: XC_LoadPackage.LoadPackageParam) {
        val controllerClass = SettingsAppRefs.resolveControllerClassName(lpparam.classLoader)
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
        } catch (e: Throwable) {
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
                        if (HotspotHelper.isFixedEndpointEnabled(context)) {
                            param.result = HotspotHelper.FIXED_IP
                            return
                        }
                        val ip = HotspotHelper.getHotspotIpAddress(context) ?: return
                        param.result = ip
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("HotspotAdb: failed to hook getIpv4Address: $e")
        }
    }

    private fun hookGetAdbWirelessPort(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Override the port value returned by IAdbManager binder calls in the Settings process only.
        // adbd on the server side keeps binding its real port; the TCP proxy in system_server forwards 5555 to it.
        try {
            XposedHelpers.findAndHookMethod(
                "android.debug.IAdbManager\$Stub\$Proxy",
                lpparam.classLoader,
                "getAdbWirelessPort",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val app = currentApplication() ?: return
                            if (!HotspotHelper.isFixedEndpointEnabled(app)) return
                            if (!HotspotHelper.isAdbWifiEnabled(app)) return
                            param.result = HotspotHelper.FIXED_PORT
                        } catch (e: Throwable) {
                            XposedBridge.log("HotspotAdb: port override failed: $e")
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("HotspotAdb: failed to hook getAdbWirelessPort: $e")
        }
    }

    private fun currentApplication(): Context? {
        return try {
            val activityThread = Class.forName("android.app.ActivityThread")
            activityThread.getMethod("currentApplication").invoke(null) as? Context
        } catch (_: Throwable) {
            null
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
        } catch (e: Throwable) {
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
                Settings.Global.putInt(context.contentResolver, HotspotHelper.ADB_WIFI_ENABLED, if (newValue) 1 else 0)
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
                    val fragmentClass = SettingsAppRefs.resolveFragmentClassName(lpparam.classLoader)
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

        // Sync state from Developer Options; observer stored on the fragment for later cleanup
        if (XposedHelpers.getAdditionalInstanceField(fragment, TAG_WTS_OBSERVER) == null) {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(
                        selfChange: Boolean,
                        uri: Uri?,
                    ) {
                        updatePrefState(context, pref)
                    }
                }
            val resolver = context.contentResolver
            resolver.registerContentObserver(Settings.Global.getUriFor(HotspotHelper.ADB_WIFI_ENABLED), false, observer)
            resolver.registerContentObserver(
                Settings.Global.getUriFor(HotspotHelper.FIXED_ENDPOINT_KEY),
                false,
                observer,
            )
            XposedHelpers.setAdditionalInstanceField(fragment, TAG_WTS_OBSERVER, observer)
        }

        // Also watch hotspot state changes (on/off) to update the label
        if (XposedHelpers.getAdditionalInstanceField(fragment, TAG_WTS_RECEIVER) == null) {
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
            XposedHelpers.setAdditionalInstanceField(fragment, TAG_WTS_RECEIVER, receiver)
        }

        XposedBridge.log("HotspotAdb: added wireless debugging toggle to hotspot settings")
    }

    private fun hookWirelessDebuggingFragment(lpparam: XC_LoadPackage.LoadPackageParam) {
        // The target fragment doesn't override onStart() directly, so hook DashboardFragment.onStart() and filter.
        val fragmentClassName = SettingsAppRefs.resolveFragmentClassName(lpparam.classLoader)
        try {
            XposedHelpers.findAndHookMethod(
                "com.android.settings.dashboard.DashboardFragment",
                lpparam.classLoader,
                "onStart",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.thisObject.javaClass.name != fragmentClassName) return
                        try {
                            injectFixedEndpointPref(param.thisObject, lpparam)
                        } catch (e: Throwable) {
                            XposedBridge.log("HotspotAdb: failed to inject fixed endpoint pref: $e")
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("HotspotAdb: failed to hook DashboardFragment.onStart for $fragmentClassName: $e")
        }
    }

    private fun injectFixedEndpointPref(
        fragment: Any,
        lpparam: XC_LoadPackage.LoadPackageParam,
    ) {
        val screen =
            XposedHelpers.callMethod(fragment, "getPreferenceScreen") ?: run {
                XposedBridge.log("HotspotAdb: WD preferenceScreen is null")
                return
            }
        if (XposedHelpers.callMethod(screen, "findPreference", HotspotHelper.FIXED_ENDPOINT_KEY) != null) return
        val context = XposedHelpers.callMethod(screen, "getContext") as Context

        val switchClass =
            XposedHelpers.findClass(
                "androidx.preference.SwitchPreferenceCompat",
                lpparam.classLoader,
            )
        val pref = switchClass.getConstructor(Context::class.java).newInstance(context)
        XposedHelpers.callMethod(pref, "setKey", HotspotHelper.FIXED_ENDPOINT_KEY)
        XposedHelpers.callMethod(pref, "setTitle", "Fixed IP/port")
        XposedHelpers.callMethod(
            pref,
            "setSummary",
            "Use ${HotspotHelper.FIXED_IP}:${HotspotHelper.FIXED_PORT}",
        )
        XposedHelpers.callMethod(pref, "setChecked", HotspotHelper.isFixedEndpointEnabled(context))
        XposedHelpers.callMethod(pref, "setVisible", HotspotHelper.isAdbWifiEnabled(context))

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
                Settings.Global.putInt(
                    context.contentResolver,
                    HotspotHelper.FIXED_ENDPOINT_KEY,
                    if (newValue) 1 else 0,
                )
                // Refresh the IP/Port row above so it re-reads our getIpv4Address hook.
                try {
                    XposedHelpers.callMethod(fragment, "updatePreferenceStates")
                } catch (e: Throwable) {
                    XposedBridge.log("HotspotAdb: updatePreferenceStates failed: $e")
                }
                true
            }
        XposedHelpers.callMethod(pref, "setOnPreferenceChangeListener", changeProxy)

        // Place the toggle right after the IP/Port row. Resolve by the "adb_ip_addr_pref"
        // key (present A11–A14, A16) when possible, else default to index 0 (A15, which
        // reorganized the fragment and left the IP row keyless).
        val count = XposedHelpers.callMethod(screen, "getPreferenceCount") as Int
        var targetIndex = 0
        for (i in 0 until count) {
            val p = XposedHelpers.callMethod(screen, "getPreference", i)
            if (XposedHelpers.callMethod(p, "getKey") as? String == SettingsAppRefs.IP_PREF_KEY) {
                targetIndex = i
                break
            }
        }
        for (i in 0 until count) {
            val p = XposedHelpers.callMethod(screen, "getPreference", i)
            val newOrder = if (i <= targetIndex) i else i + 1
            XposedHelpers.callMethod(p, "setOrder", newOrder)
        }
        XposedHelpers.callMethod(pref, "setOrder", targetIndex + 1)
        XposedHelpers.callMethod(screen, "addPreference", pref)

        // Toggle visibility with the main Wireless Debugging switch on this screen.
        if (XposedHelpers.getAdditionalInstanceField(fragment, TAG_WD_OBSERVER) == null) {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(
                        selfChange: Boolean,
                        uri: Uri?,
                    ) {
                        XposedHelpers.callMethod(pref, "setVisible", HotspotHelper.isAdbWifiEnabled(context))
                    }
                }
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(HotspotHelper.ADB_WIFI_ENABLED),
                false,
                observer,
            )
            XposedHelpers.setAdditionalInstanceField(fragment, TAG_WD_OBSERVER, observer)
        }
        XposedBridge.log("HotspotAdb: added Fixed IP/port toggle to Wireless Debugging")
    }

    private fun updatePrefState(
        context: Context,
        pref: Any,
    ) {
        val on = HotspotHelper.isAdbWifiEnabled(context) && HotspotHelper.isHotspotActive(context)
        XposedHelpers.callMethod(pref, "setChecked", on)
        XposedHelpers.callMethod(pref, "setSummary", getWirelessDebuggingSummary(context, on))
    }

    private fun getWirelessDebuggingSummary(
        context: Context,
        enabled: Boolean,
    ): String {
        if (!enabled) return ""
        if (HotspotHelper.isFixedEndpointEnabled(context)) {
            return "${HotspotHelper.FIXED_IP}:${HotspotHelper.FIXED_PORT}"
        }
        val ip =
            HotspotHelper.getHotspotIpAddress(context)
                ?: HotspotHelper.getAnyWlanIp()
                ?: return ""
        val port = HotspotHelper.getAdbWirelessPort()
        return if (port > 0) "$ip:$port" else ip
    }
}
