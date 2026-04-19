package io.drsr.hotspotadb.compat

import android.content.BroadcastReceiver
import de.robv.android.xposed.XposedHelpers

/**
 * Class resolution for ADB-related framework internals. Callers hook by class object,
 * not by fully-qualified name, so this is the single place that knows where AOSP
 * moved things between Android versions.
 *
 * Android 11–15: inner classes under `AdbDebuggingManager`.
 * Android 16+: `AdbConnectionInfo` and `AdbBroadcastReceiver` promoted to top-level.
 */
object AdbFrameworkRefs {
    private const val HANDLER = "com.android.server.adb.AdbDebuggingManager\$AdbDebuggingHandler"
    private const val CONNECTION_INFO_TOPLEVEL = "com.android.server.adb.AdbConnectionInfo"
    private const val CONNECTION_INFO_INNER = "com.android.server.adb.AdbDebuggingManager\$AdbConnectionInfo"
    private const val BROADCAST_RECEIVER_TOPLEVEL = "com.android.server.adb.AdbBroadcastReceiver"
    private const val BROADCAST_RECEIVER_INNER_SCAN_LIMIT = 10

    fun findHandlerClass(loader: ClassLoader): Class<*> {
        return XposedHelpers.findClass(HANDLER, loader)
    }

    fun findConnectionInfoClass(loader: ClassLoader): Class<*> {
        return ClassRefs.tryFindClass(loader, CONNECTION_INFO_TOPLEVEL, CONNECTION_INFO_INNER)
            ?: throw ClassNotFoundException("AdbConnectionInfo not found in either location")
    }

    fun newConnectionInfo(
        loader: ClassLoader,
        bssid: String,
        ssid: String,
    ): Any {
        return XposedHelpers.newInstance(findConnectionInfoClass(loader), bssid, ssid)
    }

    /**
     * Returns the BroadcastReceiver subclass AdbDebuggingManager uses to observe
     * Wi-Fi state, or null if it cannot be located. Android 16 promoted it to a
     * top-level class; earlier versions used an anonymous inner class of
     * AdbDebuggingHandler (usually $1..$4, scanning covers ROM variance).
     */
    fun resolveBroadcastReceiverClass(loader: ClassLoader): Class<*>? {
        ClassRefs.tryFindClass(loader, BROADCAST_RECEIVER_TOPLEVEL)?.let { return it }
        for (i in 1..BROADCAST_RECEIVER_INNER_SCAN_LIMIT) {
            val cls = ClassRefs.tryFindClass(loader, "$HANDLER\$$i") ?: continue
            if (BroadcastReceiver::class.java.isAssignableFrom(cls)) return cls
        }
        return null
    }
}
