package dev.hotspot.wirelessdebug

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HotspotWirelessDebugModule : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            "com.android.settings" -> {
                XposedBridge.log("HotspotWirelessDebug: hooking Settings")
                SettingsHook.init(lpparam)
            }
            "android" -> {
                XposedBridge.log("HotspotWirelessDebug: hooking framework")
                FrameworkHook.init(lpparam)
            }
        }
    }
}
