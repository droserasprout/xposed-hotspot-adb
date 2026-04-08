package dev.hotspot.wirelessdebug

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

object SettingsHook {

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        hookIsWifiConnected(lpparam)
        hookGetIpv4Address(lpparam)
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
                                XposedBridge.log("HotspotWirelessDebug: isWifiConnected -> true (hotspot active)")
                            }
                        }
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("HotspotWirelessDebug: failed to hook isWifiConnected: $e")
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
                                XposedBridge.log("HotspotWirelessDebug: getIpv4Address -> $ip (hotspot)")
                            }
                        }
                    }
                }
            )
        } catch (e: Exception) {
            XposedBridge.log("HotspotWirelessDebug: failed to hook getIpv4Address: $e")
        }
    }
}
