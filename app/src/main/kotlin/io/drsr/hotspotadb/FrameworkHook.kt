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
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.drsr.hotspotadb.compat.AdbFrameworkRefs
import io.drsr.hotspotadb.compat.ClassRefs
import io.drsr.hotspotadb.compat.HotspotApi

object FrameworkHook {
    @Volatile
    private var observersRegistered = false

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        SubnetAlias.setClassLoader(lpparam.classLoader)
        hookGetCurrentWifiApInfo(lpparam)
        hookVerifyWifiNetwork(lpparam)
        hookBroadcastReceiver(lpparam)
    }

    /**
     * Android 16 QPR gates the initial enable on verifyWifiNetwork(bssid, ssid), which
     * consults the user-trusted-networks store and disables ADB_WIFI when the network
     * isn't trusted. Our synthetic hotspot BSSID is never trusted, so treat the hotspot
     * as trusted while it is active. Absent on older versions (hook install no-ops).
     *
     * Must run in beforeHookedMethod: the original body calls startConfirmationForNetwork()
     * for untrusted networks, which launches SystemUI's WifiDebuggingActivity and then
     * writes ADB_WIFI_ENABLED=0 (deny), flapping the toggle. Returning early skips it.
     */
    private fun hookVerifyWifiNetwork(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val handlerClass = AdbFrameworkRefs.findHandlerClass(lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                handlerClass,
                "verifyWifiNetwork",
                String::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val context = getContext(param.thisObject) ?: return
                        if (!HotspotHelper.isHotspotActive(context)) return
                        param.result = true
                        XposedBridge.log("HotspotAdb: verifyWifiNetwork -> true (hotspot active)")
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("HotspotAdb: failed to hook verifyWifiNetwork: $e")
        }
    }

    private fun hookGetCurrentWifiApInfo(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val handlerClass = AdbFrameworkRefs.findHandlerClass(lpparam.classLoader)
            XposedHelpers.findAndHookMethod(
                handlerClass,
                "getCurrentWifiApInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result != null) return

                        val context = getContext(param.thisObject) ?: return
                        if (!HotspotHelper.isHotspotActive(context)) return

                        try {
                            val ssid = HotspotApi.getHotspotSsid(context)
                            val info = AdbFrameworkRefs.newConnectionInfo(lpparam.classLoader, "02:00:00:00:00:00", ssid)
                            param.result = info
                            XposedBridge.log("HotspotAdb: getCurrentWifiApInfo -> synthetic (hotspot active)")

                            ensureObservers(context)
                            evaluateProxy(context)
                        } catch (e: Exception) {
                            XposedBridge.log("HotspotAdb: failed to create AdbConnectionInfo: $e")
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("HotspotAdb: failed to hook getCurrentWifiApInfo: $e")
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
                XposedBridge.log("HotspotAdb: framework observers registered")
            } catch (e: Exception) {
                XposedBridge.log("HotspotAdb: failed to register observers: $e")
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
            XposedBridge.log("HotspotAdb: evaluateProxy failed: $e")
        }
    }

    private fun hookBroadcastReceiver(lpparam: XC_LoadPackage.LoadPackageParam) {
        val cls = AdbFrameworkRefs.resolveBroadcastReceiverClass(lpparam.classLoader)
        if (cls == null) {
            XposedBridge.log("HotspotAdb: BroadcastReceiver not found, falling back to ContentResolver hook")
            hookSettingsGlobalDisable(lpparam)
            return
        }
        try {
            hookBroadcastReceiverClass(cls)
            XposedBridge.log("HotspotAdb: hooked BroadcastReceiver ${cls.name}")
        } catch (e: Throwable) {
            XposedBridge.log("HotspotAdb: failed to hook ${cls.name}: $e")
        }
    }

    private fun hookBroadcastReceiverClass(cls: Class<*>) {
        XposedBridge.hookAllMethods(
            cls,
            "onReceive",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val context = param.args[0] as Context
                    val intent = param.args[1] as Intent
                    val action = intent.action ?: return

                    if (action == WifiManager.WIFI_STATE_CHANGED_ACTION ||
                        action == WifiManager.NETWORK_STATE_CHANGED_ACTION
                    ) {
                        if (HotspotHelper.isHotspotActive(context)) {
                            param.result = null
                            XposedBridge.log("HotspotAdb: suppressed $action (hotspot active)")
                        }
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val context = param.args[0] as? Context ?: return
                    ensureObservers(context)
                    evaluateProxy(context)
                }
            },
        )
    }

    private fun hookSettingsGlobalDisable(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Fallback: intercept Settings.Global.putInt to prevent ADB_WIFI_ENABLED = 0 when hotspot is active
        try {
            XposedHelpers.findAndHookMethod(
                "android.provider.Settings\$Global",
                lpparam.classLoader,
                "putInt",
                ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args[1] as? String ?: return
                        val value = param.args[2] as Int
                        if (key != "adb_wifi_enabled" || value != 0) return
                        val resolver = param.args[0] as ContentResolver
                        val context = ClassRefs.contextFromResolver(resolver)
                        if (context != null && HotspotHelper.isHotspotActive(context)) {
                            param.result = false
                            XposedBridge.log("HotspotAdb: blocked ADB_WIFI_ENABLED=0 (hotspot active)")
                        }
                    }
                },
            )
        } catch (e: Throwable) {
            XposedBridge.log("HotspotAdb: failed to hook Settings.Global.putInt: $e")
        }
    }

    private fun getContext(handler: Any): Context? {
        return try {
            val manager = XposedHelpers.getObjectField(handler, "this\$0")
            XposedHelpers.getObjectField(manager, "mContext") as Context
        } catch (e: Exception) {
            XposedBridge.log("HotspotAdb: failed to get context: $e")
            null
        }
    }
}
