package io.drsr.hotspotadb

import android.util.Log
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Executable

/**
 * Process-wide handle to the active Xposed module.
 *
 * The modern libxposed API exposes hooking and logging as instance methods on
 * [XposedInterface] (implemented by our [HotspotAdbModule]), whereas the legacy API
 * offered them as statics (XposedBridge). Singletons that need them - the hooks, the
 * TCP proxy, the reflection helpers - go through here. Attached once per process in
 * [HotspotAdbModule.onModuleLoaded], before any package/system-server callback fires.
 */
object Xp {
    private const val TAG = "HotspotAdb"

    @Volatile
    private var module: XposedInterface? = null

    fun attach(iface: XposedInterface) {
        module = iface
    }

    fun log(msg: String) {
        module?.log(Log.INFO, TAG, msg)
    }

    fun hook(executable: Executable): XposedInterface.HookBuilder =
        requireNotNull(module) { "Xposed module not attached yet" }.hook(executable)
}
