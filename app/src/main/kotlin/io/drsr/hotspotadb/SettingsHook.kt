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
import io.drsr.hotspotadb.compat.SettingsAppRefs

object SettingsHook {
    private const val TAG_WTS_OBSERVER = "hotspot_adb_observer"
    private const val TAG_WTS_RECEIVER = "hotspot_adb_receiver"
    private const val TAG_WD_OBSERVER = "hotspot_adb_fixed_visibility"

    fun init(classLoader: ClassLoader) {
        hookIsWifiConnected(classLoader)
        hookGetIpv4Address(classLoader)
        hookGetAdbWirelessPort(classLoader)
        hookWifiTetherSettings(classLoader)
        hookWirelessDebuggingFragment(classLoader)
        hookFragmentCleanup(classLoader)
    }

    private fun hookFragmentCleanup(classLoader: ClassLoader) {
        // Unregister observers/receivers we attached in injection hooks. Both target
        // fragments extend DashboardFragment so one hook covers them. Android 16 QPR
        // dropped onDestroyView from DashboardFragment; fall back to onStop, which
        // pairs symmetrically with the onStart-time registration.
        val dashboard =
            try {
                classLoader.loadClass("com.android.settings.dashboard.DashboardFragment")
            } catch (e: Throwable) {
                Xp.log("HotspotAdb: DashboardFragment not found: $e")
                return
            }
        for (method in listOf("onDestroyView", "onStop")) {
            try {
                val target = Reflect.findMethod(dashboard, method)
                Xp.hook(target).intercept { chain ->
                    val result = chain.proceed()
                    chain.thisObject?.let { cleanupFragment(it) }
                    result
                }
                return
            } catch (e: Throwable) {
                Xp.log("HotspotAdb: DashboardFragment.$method unavailable: $e")
            }
        }
    }

    private fun cleanupFragment(fragment: Any) {
        val context = Reflect.call(fragment, "getContext") as? Context ?: return
        val resolver = context.contentResolver
        for (tag in listOf(TAG_WTS_OBSERVER, TAG_WD_OBSERVER)) {
            val observer =
                Reflect.getInstanceField(fragment, tag) as? ContentObserver ?: continue
            try {
                resolver.unregisterContentObserver(observer)
            } catch (e: Throwable) {
                Xp.log("HotspotAdb: unregister $tag failed: $e")
            }
            Reflect.removeInstanceField(fragment, tag)
        }
        val receiver =
            Reflect.getInstanceField(fragment, TAG_WTS_RECEIVER) as? BroadcastReceiver
        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Throwable) {
                Xp.log("HotspotAdb: unregister $TAG_WTS_RECEIVER failed: $e")
            }
            Reflect.removeInstanceField(fragment, TAG_WTS_RECEIVER)
        }
    }

    private fun hookIsWifiConnected(classLoader: ClassLoader) {
        val controllerClassName = SettingsAppRefs.resolveControllerClassName(classLoader)
        try {
            val controller = classLoader.loadClass(controllerClassName)
            val method = Reflect.findMethod(controller, "isWifiConnected", Context::class.java)
            Xp.hook(method).intercept { chain ->
                val result = chain.proceed()
                if (result == false) {
                    val context = chain.getArg(0) as Context
                    if (HotspotHelper.isHotspotActive(context)) {
                        Xp.log("HotspotAdb: isWifiConnected -> true (hotspot active)")
                        return@intercept true
                    }
                }
                result
            }
        } catch (e: Throwable) {
            Xp.log("HotspotAdb: failed to hook isWifiConnected: $e")
        }
    }

    private fun hookGetIpv4Address(classLoader: ClassLoader) {
        try {
            val controller =
                classLoader.loadClass("com.android.settings.development.AdbIpAddressPreferenceController")
            val method = Reflect.findMethod(controller, "getIpv4Address")
            Xp.hook(method).intercept { chain ->
                val result = chain.proceed()
                val context =
                    Reflect.getField(chain.thisObject!!, "mContext") as? Context
                        ?: return@intercept result
                if (!HotspotHelper.isHotspotActive(context)) return@intercept result
                if (HotspotHelper.isFixedEndpointEnabled(context)) {
                    return@intercept HotspotHelper.FIXED_IP
                }
                HotspotHelper.getHotspotIpAddress(context) ?: result
            }
        } catch (e: Throwable) {
            Xp.log("HotspotAdb: failed to hook getIpv4Address: $e")
        }
    }

    private fun hookGetAdbWirelessPort(classLoader: ClassLoader) {
        // Override the port value returned by IAdbManager binder calls in the Settings process only.
        // adbd on the server side keeps binding its real port; the TCP proxy in system_server forwards 5555 to it.
        try {
            val proxyStub = classLoader.loadClass("android.debug.IAdbManager\$Stub\$Proxy")
            val method = Reflect.findMethod(proxyStub, "getAdbWirelessPort")
            Xp.hook(method).intercept { chain ->
                val result = chain.proceed()
                try {
                    val app = currentApplication() ?: return@intercept result
                    if (!HotspotHelper.isFixedEndpointEnabled(app)) return@intercept result
                    if (!HotspotHelper.isAdbWifiEnabled(app)) return@intercept result
                    HotspotHelper.FIXED_PORT
                } catch (e: Throwable) {
                    Xp.log("HotspotAdb: port override failed: $e")
                    result
                }
            }
        } catch (e: Throwable) {
            Xp.log("HotspotAdb: failed to hook getAdbWirelessPort: $e")
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

    private fun hookWifiTetherSettings(classLoader: ClassLoader) {
        try {
            val tetherSettings =
                classLoader.loadClass("com.android.settings.wifi.tether.WifiTetherSettings")
            val method = Reflect.findMethod(tetherSettings, "onStart")
            Xp.hook(method).intercept { chain ->
                val result = chain.proceed()
                try {
                    injectWirelessDebuggingPref(chain.thisObject!!, classLoader)
                } catch (e: Throwable) {
                    Xp.log("HotspotAdb: failed to inject preference: $e")
                }
                result
            }
        } catch (e: Throwable) {
            Xp.log("HotspotAdb: failed to hook WifiTetherSettings: $e")
        }
    }

    private fun injectWirelessDebuggingPref(
        fragment: Any,
        classLoader: ClassLoader,
    ) {
        val screen =
            Reflect.call(fragment, "getPreferenceScreen") ?: run {
                Xp.log("HotspotAdb: preferenceScreen is null")
                return
            }
        if (Reflect.call(screen, "findPreference", "hotspot_adb_wireless_debugging") != null) return
        val context = Reflect.call(screen, "getContext") as Context

        // PrimarySwitchPreference — split toggle+button, same as Developer Options
        val primarySwitchClass =
            classLoader.loadClass("com.android.settingslib.PrimarySwitchPreference")
        val pref = primarySwitchClass.getConstructor(Context::class.java).newInstance(context)

        Reflect.call(pref, "setKey", "hotspot_adb_wireless_debugging")
        Reflect.call(pref, "setTitle", "Wireless debugging")
        updatePrefState(context, pref)

        // Switch toggle listener
        val changeListenerClass =
            classLoader.loadClass("androidx.preference.Preference\$OnPreferenceChangeListener")
        val changeProxy =
            java.lang.reflect.Proxy.newProxyInstance(
                classLoader,
                arrayOf(changeListenerClass),
            ) { _, _, args ->
                val newValue = args!![1] as Boolean
                Settings.Global.putInt(context.contentResolver, HotspotHelper.ADB_WIFI_ENABLED, if (newValue) 1 else 0)
                updatePrefState(context, pref)
                true
            }
        Reflect.call(pref, "setOnPreferenceChangeListener", changeProxy)

        // Click on the left side opens Wireless Debugging screen
        val clickListenerClass =
            classLoader.loadClass("androidx.preference.Preference\$OnPreferenceClickListener")
        val clickProxy =
            java.lang.reflect.Proxy.newProxyInstance(
                classLoader,
                arrayOf(clickListenerClass),
            ) { _, _, _ ->
                try {
                    val subSettingsClass = context.classLoader.loadClass("com.android.settings.SubSettings")
                    val fragmentClass = SettingsAppRefs.resolveFragmentClassName(classLoader)
                    val intent = android.content.Intent(context, subSettingsClass)
                    intent.putExtra(":settings:show_fragment", fragmentClass)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Xp.log("HotspotAdb: failed to open wireless debugging: $e")
                }
                true
            }
        Reflect.call(pref, "setOnPreferenceClickListener", clickProxy)

        Reflect.call(screen, "addPreference", pref)

        // Sync state from Developer Options; observer stored on the fragment for later cleanup
        if (Reflect.getInstanceField(fragment, TAG_WTS_OBSERVER) == null) {
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
            Reflect.setInstanceField(fragment, TAG_WTS_OBSERVER, observer)
        }

        // Also watch hotspot state changes (on/off) to update the label
        if (Reflect.getInstanceField(fragment, TAG_WTS_RECEIVER) == null) {
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
            Reflect.setInstanceField(fragment, TAG_WTS_RECEIVER, receiver)
        }

        Xp.log("HotspotAdb: added wireless debugging toggle to hotspot settings")
    }

    private fun hookWirelessDebuggingFragment(classLoader: ClassLoader) {
        // The target fragment doesn't override onStart() directly, so hook DashboardFragment.onStart() and filter.
        val fragmentClassName = SettingsAppRefs.resolveFragmentClassName(classLoader)
        try {
            val dashboard = classLoader.loadClass("com.android.settings.dashboard.DashboardFragment")
            val method = Reflect.findMethod(dashboard, "onStart")
            Xp.hook(method).intercept { chain ->
                val result = chain.proceed()
                val self = chain.thisObject
                if (self != null && self.javaClass.name == fragmentClassName) {
                    try {
                        injectFixedEndpointPref(self, classLoader)
                    } catch (e: Throwable) {
                        Xp.log("HotspotAdb: failed to inject fixed endpoint pref: $e")
                    }
                }
                result
            }
        } catch (e: Throwable) {
            Xp.log("HotspotAdb: failed to hook DashboardFragment.onStart for $fragmentClassName: $e")
        }
    }

    private fun injectFixedEndpointPref(
        fragment: Any,
        classLoader: ClassLoader,
    ) {
        val screen =
            Reflect.call(fragment, "getPreferenceScreen") ?: run {
                Xp.log("HotspotAdb: WD preferenceScreen is null")
                return
            }
        if (Reflect.call(screen, "findPreference", HotspotHelper.FIXED_ENDPOINT_KEY) != null) return
        val context = Reflect.call(screen, "getContext") as Context

        val switchClass = classLoader.loadClass("androidx.preference.SwitchPreferenceCompat")
        val pref = switchClass.getConstructor(Context::class.java).newInstance(context)
        Reflect.call(pref, "setKey", HotspotHelper.FIXED_ENDPOINT_KEY)
        Reflect.call(pref, "setTitle", "Fixed IP/port")
        Reflect.call(
            pref,
            "setSummary",
            "Use ${HotspotHelper.FIXED_IP}:${HotspotHelper.FIXED_PORT}",
        )
        Reflect.call(pref, "setChecked", HotspotHelper.isFixedEndpointEnabled(context))
        Reflect.call(pref, "setVisible", HotspotHelper.isAdbWifiEnabled(context))

        val changeListenerClass =
            classLoader.loadClass("androidx.preference.Preference\$OnPreferenceChangeListener")
        val changeProxy =
            java.lang.reflect.Proxy.newProxyInstance(
                classLoader,
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
                    Reflect.call(fragment, "updatePreferenceStates")
                } catch (e: Throwable) {
                    Xp.log("HotspotAdb: updatePreferenceStates failed: $e")
                }
                true
            }
        Reflect.call(pref, "setOnPreferenceChangeListener", changeProxy)

        // Place the toggle right after the IP/Port row. Resolve by the "adb_ip_addr_pref"
        // key (present A11–A14, A16) when possible, else default to index 0 (A15, which
        // reorganized the fragment and left the IP row keyless).
        val count = Reflect.call(screen, "getPreferenceCount") as Int
        var targetIndex = 0
        for (i in 0 until count) {
            val p = Reflect.call(screen, "getPreference", i)!!
            if (Reflect.call(p, "getKey") as? String == SettingsAppRefs.IP_PREF_KEY) {
                targetIndex = i
                break
            }
        }
        for (i in 0 until count) {
            val p = Reflect.call(screen, "getPreference", i)!!
            val newOrder = if (i <= targetIndex) i else i + 1
            Reflect.call(p, "setOrder", newOrder)
        }
        Reflect.call(pref, "setOrder", targetIndex + 1)
        Reflect.call(screen, "addPreference", pref)

        // Toggle visibility with the main Wireless Debugging switch on this screen.
        if (Reflect.getInstanceField(fragment, TAG_WD_OBSERVER) == null) {
            val observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(
                        selfChange: Boolean,
                        uri: Uri?,
                    ) {
                        Reflect.call(pref, "setVisible", HotspotHelper.isAdbWifiEnabled(context))
                    }
                }
            context.contentResolver.registerContentObserver(
                Settings.Global.getUriFor(HotspotHelper.ADB_WIFI_ENABLED),
                false,
                observer,
            )
            Reflect.setInstanceField(fragment, TAG_WD_OBSERVER, observer)
        }
        Xp.log("HotspotAdb: added Fixed IP/port toggle to Wireless Debugging")
    }

    private fun updatePrefState(
        context: Context,
        pref: Any,
    ) {
        val on = HotspotHelper.isAdbWifiEnabled(context) && HotspotHelper.isHotspotActive(context)
        Reflect.call(pref, "setChecked", on)
        Reflect.call(pref, "setSummary", getWirelessDebuggingSummary(context, on))
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
