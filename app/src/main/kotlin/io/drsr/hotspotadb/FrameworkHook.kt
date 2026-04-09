package io.drsr.hotspotadb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object FrameworkHook {
    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookGetCurrentWifiApInfo(lpparam)
        hookBroadcastReceiver(lpparam)
    }

    private fun hookGetCurrentWifiApInfo(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val handlerClass =
                XposedHelpers.findClass(
                    "com.android.server.adb.AdbDebuggingManager\$AdbDebuggingHandler",
                    lpparam.classLoader,
                )

            XposedHelpers.findAndHookMethod(
                handlerClass,
                "getCurrentWifiApInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.result != null) return

                        val context = getContext(param.thisObject) ?: return
                        if (!HotspotHelper.isHotspotActive(context)) return

                        try {
                            val ssid =
                                try {
                                    val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                                    val config = wm.javaClass.getMethod("getSoftApConfiguration").invoke(wm)
                                    val wifiSsid = config.javaClass.getMethod("getWifiSsid").invoke(config)
                                    wifiSsid?.toString() ?: "HotspotAP"
                                } catch (_: Throwable) {
                                    "HotspotAP"
                                }

                            val connectionInfoClass =
                                XposedHelpers.findClass(
                                    "com.android.server.adb.AdbDebuggingManager\$AdbConnectionInfo",
                                    lpparam.classLoader,
                                )
                            val info =
                                XposedHelpers.newInstance(
                                    connectionInfoClass,
                                    "02:00:00:00:00:00",
                                    ssid,
                                )
                            param.result = info
                            XposedBridge.log("HotspotAdb: getCurrentWifiApInfo -> synthetic (hotspot active)")
                        } catch (e: Exception) {
                            XposedBridge.log("HotspotAdb: failed to create AdbConnectionInfo: $e")
                        }
                    }
                },
            )
        } catch (e: Exception) {
            XposedBridge.log("HotspotAdb: failed to hook getCurrentWifiApInfo: $e")
        }
    }

    private fun hookBroadcastReceiver(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Scan anonymous inner classes of AdbDebuggingHandler to find the BroadcastReceiver
        val baseName = "com.android.server.adb.AdbDebuggingManager\$AdbDebuggingHandler"
        var found = false

        for (i in 1..10) {
            try {
                val cls = Class.forName("$baseName\$$i", false, lpparam.classLoader)
                if (!BroadcastReceiver::class.java.isAssignableFrom(cls)) continue

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
                    },
                )
                found = true
                XposedBridge.log("HotspotAdb: hooked BroadcastReceiver ${cls.name}")
                break
            } catch (_: ClassNotFoundException) {
                continue
            } catch (e: Exception) {
                XposedBridge.log("HotspotAdb: error scanning inner class $i: $e")
            }
        }

        if (!found) {
            XposedBridge.log("HotspotAdb: BroadcastReceiver inner class not found, falling back to ContentResolver hook")
            hookSettingsGlobalDisable(lpparam)
        }
    }

    private fun hookSettingsGlobalDisable(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Fallback: intercept Settings.Global.putInt to prevent ADB_WIFI_ENABLED = 0 when hotspot is active
        try {
            XposedHelpers.findAndHookMethod(
                "android.provider.Settings\$Global",
                lpparam.classLoader,
                "putInt",
                android.content.ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args[1] as? String ?: return
                        val value = param.args[2] as Int
                        if (key == "adb_wifi_enabled" && value == 0) {
                            try {
                                val resolver = param.args[0] as android.content.ContentResolver
                                val context =
                                    resolver.javaClass.getMethod("getContext")
                                        .invoke(resolver) as? Context
                                if (context != null && HotspotHelper.isHotspotActive(context)) {
                                    param.result = false
                                    XposedBridge.log("HotspotAdb: blocked ADB_WIFI_ENABLED=0 (hotspot active)")
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                },
            )
        } catch (e: Exception) {
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
