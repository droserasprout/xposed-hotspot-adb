package io.drsr.hotspotadb

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.drsr.hotspotadb.compat.AdbFrameworkRefs
import io.drsr.hotspotadb.compat.ClassRefs
import io.drsr.hotspotadb.compat.HotspotApi

object FrameworkHook {
    @Volatile
    private var observersRegistered = false

    fun init(classLoader: ClassLoader) {
        SubnetAlias.setClassLoader(classLoader)
        hookGetCurrentWifiApInfo(classLoader)
        hookVerifyWifiNetwork(classLoader)
        hookBroadcastReceiver(classLoader)
    }

    /**
     * Android 16 QPR gates the initial enable on verifyWifiNetwork(bssid, ssid), which
     * consults the user-trusted-networks store and disables ADB_WIFI when the network
     * isn't trusted. Our synthetic hotspot BSSID is never trusted, so treat the hotspot
     * as trusted while it is active. Absent on older versions (hook install no-ops).
     *
     * We skip the original body (never call chain.proceed): for untrusted networks it
     * calls startConfirmationForNetwork(), which launches SystemUI's WifiDebuggingActivity
     * and then writes ADB_WIFI_ENABLED=0 (deny), flapping the toggle.
     */
    private fun hookVerifyWifiNetwork(classLoader: ClassLoader) {
        try {
            val handlerClass = AdbFrameworkRefs.findHandlerClass(classLoader)
            val method =
                Reflect.findMethod(
                    handlerClass,
                    "verifyWifiNetwork",
                    String::class.java,
                    String::class.java,
                )
            Xp.hook(method).intercept { chain ->
                val context = getContext(chain.thisObject)
                if (context != null && HotspotHelper.isHotspotActive(context)) {
                    Xp.log("HotspotAdb: verifyWifiNetwork -> true (hotspot active)")
                    true
                } else {
                    chain.proceed()
                }
            }
        } catch (e: Throwable) {
            Xp.log("HotspotAdb: failed to hook verifyWifiNetwork: $e")
        }
    }

    private fun hookGetCurrentWifiApInfo(classLoader: ClassLoader) {
        try {
            val handlerClass = AdbFrameworkRefs.findHandlerClass(classLoader)
            val method = Reflect.findMethod(handlerClass, "getCurrentWifiApInfo")
            Xp.hook(method).intercept { chain ->
                val result = chain.proceed()
                if (result != null) return@intercept result

                val context = getContext(chain.thisObject) ?: return@intercept result
                if (!HotspotHelper.isHotspotActive(context)) return@intercept result

                try {
                    val ssid = HotspotApi.getHotspotSsid(context)
                    val info = AdbFrameworkRefs.newConnectionInfo(classLoader, "02:00:00:00:00:00", ssid)
                    Xp.log("HotspotAdb: getCurrentWifiApInfo -> synthetic (hotspot active)")

                    ensureObservers(context)
                    evaluateProxy(context)
                    info
                } catch (e: Exception) {
                    Xp.log("HotspotAdb: failed to create AdbConnectionInfo: $e")
                    result
                }
            }
        } catch (e: Throwable) {
            Xp.log("HotspotAdb: failed to hook getCurrentWifiApInfo: $e")
        }
    }

    private fun ensureObservers(context: Context) {
        if (observersRegistered) return
        synchronized(this) {
            if (observersRegistered) return
            try {
                val resolver = context.contentResolver
                val observer =
                    object : ContentObserver(Handler(Looper.getMainLooper())) {
                        override fun onChange(
                            selfChange: Boolean,
                            uri: Uri?,
                        ) {
                            evaluateProxy(context)
                        }
                    }
                resolver.registerContentObserver(
                    Settings.Global.getUriFor(HotspotHelper.FIXED_ENDPOINT_KEY),
                    false,
                    observer,
                )
                resolver.registerContentObserver(
                    Settings.Global.getUriFor(HotspotHelper.ADB_WIFI_ENABLED),
                    false,
                    observer,
                )
                observersRegistered = true
                Xp.log("HotspotAdb: framework observers registered")
            } catch (e: Exception) {
                Xp.log("HotspotAdb: failed to register observers: $e")
            }
        }
    }

    private fun evaluateProxy(context: Context) {
        try {
            val hotspot = HotspotHelper.isHotspotActive(context)
            val fixed = HotspotHelper.isFixedEndpointEnabled(context)
            val adb = HotspotHelper.isAdbWifiEnabled(context)

            if (hotspot && fixed) SubnetAlias.apply(context) else SubnetAlias.remove()

            if (hotspot && fixed && adb) {
                val realPort = HotspotHelper.getAdbWirelessPort()
                if (realPort > 0) AdbPortProxy.start(realPort) else AdbPortProxy.stop()
            } else {
                AdbPortProxy.stop()
            }
        } catch (e: Exception) {
            Xp.log("HotspotAdb: evaluateProxy failed: $e")
        }
    }

    private fun hookBroadcastReceiver(classLoader: ClassLoader) {
        val cls = AdbFrameworkRefs.resolveBroadcastReceiverClass(classLoader)
        if (cls == null) {
            Xp.log("HotspotAdb: BroadcastReceiver not found, falling back to ContentResolver hook")
            hookSettingsGlobalDisable(classLoader)
            return
        }
        try {
            hookBroadcastReceiverClass(cls)
            Xp.log("HotspotAdb: hooked BroadcastReceiver ${cls.name}")
        } catch (e: Throwable) {
            Xp.log("HotspotAdb: failed to hook ${cls.name}: $e")
        }
    }

    private fun hookBroadcastReceiverClass(cls: Class<*>) {
        for (method in Reflect.methodsNamed(cls, "onReceive")) {
            Xp.hook(method).intercept { chain ->
                val context = chain.getArg(0) as Context
                val intent = chain.getArg(1) as Intent
                val action = intent.action

                val suppress =
                    action != null &&
                        (
                            action == WifiManager.WIFI_STATE_CHANGED_ACTION ||
                                action == WifiManager.NETWORK_STATE_CHANGED_ACTION
                        ) &&
                        HotspotHelper.isHotspotActive(context)

                if (suppress) {
                    Xp.log("HotspotAdb: suppressed $action (hotspot active)")
                } else {
                    chain.proceed()
                }

                ensureObservers(context)
                evaluateProxy(context)
                null
            }
        }
    }

    private fun hookSettingsGlobalDisable(classLoader: ClassLoader) {
        // Fallback: intercept Settings.Global.putInt to prevent ADB_WIFI_ENABLED = 0 when hotspot is active
        try {
            val settingsGlobal = classLoader.loadClass("android.provider.Settings\$Global")
            val method =
                Reflect.findMethod(
                    settingsGlobal,
                    "putInt",
                    ContentResolver::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType!!,
                )
            Xp.hook(method).intercept { chain ->
                val key = chain.getArg(1) as? String
                val value = chain.getArg(2) as Int
                if (key == "adb_wifi_enabled" && value == 0) {
                    val resolver = chain.getArg(0) as ContentResolver
                    val context = ClassRefs.contextFromResolver(resolver)
                    if (context != null && HotspotHelper.isHotspotActive(context)) {
                        Xp.log("HotspotAdb: blocked ADB_WIFI_ENABLED=0 (hotspot active)")
                        return@intercept false
                    }
                }
                chain.proceed()
            }
        } catch (e: Throwable) {
            Xp.log("HotspotAdb: failed to hook Settings.Global.putInt: $e")
        }
    }

    private fun getContext(handler: Any?): Context? {
        if (handler == null) return null
        return try {
            val manager = Reflect.getField(handler, "this\$0")!!
            Reflect.getField(manager, "mContext") as Context
        } catch (e: Exception) {
            Xp.log("HotspotAdb: failed to get context: $e")
            null
        }
    }
}
