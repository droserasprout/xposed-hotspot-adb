package io.drsr.hotspotadb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Constructor

/**
 * Hooks in system_server (android scope) that keep Wireless Debugging alive while a Wi-Fi
 * hotspot is active.
 *
 * Hook point 1 — getCurrentWifiApInfo()
 *   AdbDebuggingHandler.getCurrentWifiApInfo() returns null when no station Wi-Fi is connected,
 *   which causes the framework to refuse to start wireless debugging.  When hotspot is active we
 *   return a synthetic AdbConnectionInfo so the framework accepts the AP network.
 *
 * Hook point 2 — network monitor / receiver suppression
 *
 *   Android 16 (allowAdbWifiReconnect enabled, the default):
 *     AdbWifiNetworkMonitor is a ConnectivityManager.NetworkCallback.  Its onLost() and
 *     onCapabilitiesChanged() tear down wireless debugging when the device loses station Wi-Fi.
 *     We suppress those callbacks while hotspot is active.
 *     Confirmed in Android 16 QPR2 AOSP: com.android.server.adb.AdbWifiNetworkMonitor.
 *
 *   Android 16 (allowAdbWifiReconnect disabled):
 *     AdbBroadcastReceiver handles WIFI_STATE_CHANGED / NETWORK_STATE_CHANGED broadcasts.
 *     Confirmed in Android 16 QPR2 AOSP: com.android.server.adb.AdbBroadcastReceiver.
 *
 *   Android 15:
 *     Anonymous inner BroadcastReceiver classes inside AdbDebuggingHandler handle the same
 *     broadcasts.  Scanned by index and hooked identically.
 *
 *   Both AdbWifiNetworkMonitor and AdbBroadcastReceiver hooks are always installed when the
 *   classes are present, because AdbDebuggingManager chooses between them at runtime via
 *   allowAdbWifiReconnect() — we cannot predict which path will be active.
 *
 * User-initiated disables (Developer Options toggle, hotspot settings toggle) write
 * Settings.Global.ADB_WIFI_ENABLED directly and are NOT routed through AdbWifiNetworkMonitor
 * or AdbBroadcastReceiver.  Suppressing those classes does not interfere with user intent.
 *
 * AdbConnectionInfo resolution (Android 16 QPR2 AOSP confirmed):
 *   Primary: com.android.server.adb.AdbConnectionInfo (top-level, package-private ctor)
 *   Fallback: com.android.server.adb.AdbDebuggingManager$AdbConnectionInfo (Android 15 nested)
 */
object FrameworkHook {
    // Stable synthetic BSSID.  ADB uses BSSID as part of the trusted-network fingerprint.
    // Fixed value prevents trust from resetting every time hotspot is re-enabled
    // (Android randomises the real hotspot MAC on each enable cycle).
    private const val SYNTHETIC_BSSID = "02:00:00:00:00:00"

    // Resolved once at install time; null if no suitable constructor was found.
    private var connectionInfoCtor: Constructor<*>? = null

    fun install(
        classLoader: ClassLoader,
        module: XposedModule,
    ) {
        hookGetCurrentWifiApInfo(classLoader, module)
        hookNetworkMonitors(classLoader, module)
    }

    // ---- getCurrentWifiApInfo ----

    private fun hookGetCurrentWifiApInfo(
        classLoader: ClassLoader,
        module: XposedModule,
    ) {
        val handlerClass =
            tryFindClass(
                "com.android.server.adb.AdbDebuggingManager\$AdbDebuggingHandler",
                classLoader,
            ) ?: run {
                module.log(Log.WARN, TAG, "AdbDebuggingHandler not found; getCurrentWifiApInfo hook skipped")
                return
            }

        val method =
            try {
                handlerClass.getDeclaredMethod("getCurrentWifiApInfo").also { it.isAccessible = true }
            } catch (e: NoSuchMethodException) {
                module.log(Log.WARN, TAG, "getCurrentWifiApInfo not found in AdbDebuggingHandler: $e")
                return
            }

        connectionInfoCtor = resolveConnectionInfoCtor(classLoader, module)

        // Deoptimise: prevent the JIT from inlining callers (e.g. handleMessage) and
        // bypassing the hook.
        module.deoptimize(method)

        module.hook(method).intercept { chain ->
            val result = chain.proceed()
            if (result != null) return@intercept result

            val context = getContext(chain.getThisObject()) ?: return@intercept null
            if (!HotspotHelper.isHotspotActive(context)) return@intercept null

            val ctor =
                connectionInfoCtor ?: run {
                    module.log(Log.WARN, TAG, "AdbConnectionInfo ctor not resolved; cannot synthesise AP info")
                    return@intercept null
                }

            val ssid = getHotspotSsid(context)
            try {
                val info = ctor.newInstance(SYNTHETIC_BSSID, ssid)
                module.log(Log.INFO, TAG, "getCurrentWifiApInfo → synthetic (bssid=$SYNTHETIC_BSSID ssid=$ssid)")
                info
            } catch (e: Exception) {
                module.log(Log.ERROR, TAG, "failed to create AdbConnectionInfo: $e")
                null
            }
        }
        module.log(Log.INFO, TAG, "hooked AdbDebuggingHandler.getCurrentWifiApInfo")
    }

    /**
     * Resolve AdbConnectionInfo(String bssid, String ssid) constructor.
     *
     * Android 16 QPR2 AOSP: com.android.server.adb.AdbConnectionInfo — top-level class with
     *   package-private (String, String) constructor.
     * Android 15: com.android.server.adb.AdbDebuggingManager$AdbConnectionInfo — nested class.
     */
    private fun resolveConnectionInfoCtor(
        classLoader: ClassLoader,
        module: XposedModule,
    ): Constructor<*>? {
        val candidates =
            listOf(
                "com.android.server.adb.AdbConnectionInfo",
                "com.android.server.adb.AdbDebuggingManager\$AdbConnectionInfo",
            )
        for (name in candidates) {
            val clazz = tryFindClass(name, classLoader) ?: continue
            return try {
                clazz
                    .getDeclaredConstructor(String::class.java, String::class.java)
                    .also {
                        it.isAccessible = true
                        module.log(Log.INFO, TAG, "resolved AdbConnectionInfo ctor: $name")
                    }
            } catch (e: NoSuchMethodException) {
                module.log(Log.DEBUG, TAG, "no (String,String) ctor in $name: $e")
                continue
            }
        }
        module.log(Log.WARN, TAG, "AdbConnectionInfo constructor not found in any candidate class")
        return null
    }

    // ---- Network monitor / BroadcastReceiver hooks ----

    /**
     * Install all applicable network monitor hooks.
     *
     * Hooks are installed on every class that is present in the classloader.  We do not stop
     * after the first success because AdbDebuggingManager selects between AdbWifiNetworkMonitor
     * and AdbBroadcastReceiver at runtime via allowAdbWifiReconnect(); both may be compiled in.
     *
     * Hook selection order:
     *   1. AdbWifiNetworkMonitor (Android 16, NetworkCallback — NOT a BroadcastReceiver)
     *   2. AdbBroadcastReceiver  (Android 16, BroadcastReceiver)
     *   3. Anonymous inner BroadcastReceiver scan (Android 15 fallback)
     *
     * No Settings.Global.putInt fallback: that intercept is too broad and would block
     * user-initiated disables (Developer Options toggle, hotspot settings toggle).
     */
    private fun hookNetworkMonitors(
        classLoader: ClassLoader,
        module: XposedModule,
    ) {
        var anyHookInstalled = false

        // Path A: AdbWifiNetworkMonitor — ConnectivityManager.NetworkCallback (Android 16 default).
        // Not a BroadcastReceiver; hooks onLost() and onCapabilitiesChanged().
        if (hookAdbWifiNetworkMonitor(classLoader, module)) {
            anyHookInstalled = true
        }

        // Path B: AdbBroadcastReceiver — BroadcastReceiver (Android 16 when allowAdbWifiReconnect disabled).
        for (name in listOf(
            "com.android.server.adb.AdbBroadcastReceiver",
            "com.android.server.adb.AdbDebuggingManager\$AdbBroadcastReceiver",
        )) {
            val clazz = tryFindClass(name, classLoader) ?: continue
            if (!BroadcastReceiver::class.java.isAssignableFrom(clazz)) {
                module.log(Log.DEBUG, TAG, "$name is not a BroadcastReceiver; skipping onReceive hook")
                continue
            }
            if (hookOnReceive(clazz, module, "AdbBroadcastReceiver path ($name)")) {
                anyHookInstalled = true
            }
        }

        // Path C: anonymous inner BroadcastReceiver — Android 15 fallback.
        // Only attempt if no named Android 16 class was found.
        if (!anyHookInstalled) {
            module.log(
                Log.INFO,
                TAG,
                "no named Android 16 monitor classes found; scanning for Android 15 anonymous BroadcastReceiver",
            )
            val baseName = "com.android.server.adb.AdbDebuggingManager\$AdbDebuggingHandler"
            for (i in 1..15) {
                val clazz = tryFindClass("$baseName\$$i", classLoader) ?: continue
                if (!BroadcastReceiver::class.java.isAssignableFrom(clazz)) continue
                if (hookOnReceive(clazz, module, "anonymous inner class $i (Android 15)")) {
                    anyHookInstalled = true
                    break
                }
            }
        }

        if (!anyHookInstalled) {
            // No fallback to Settings.Global.putInt: that hook is too broad and blocks
            // user-driven disables.  Log the miss so it is visible in LSPosed logs.
            module.log(
                Log.WARN,
                TAG,
                "WARNING: no ADB network monitor or BroadcastReceiver hook installed; " +
                    "framework-driven wireless debugging teardown will NOT be suppressed",
            )
        }
    }

    /**
     * Hook AdbWifiNetworkMonitor (ConnectivityManager.NetworkCallback) on Android 16.
     *
     * AdbDebuggingManager uses AdbWifiNetworkMonitor when allowAdbWifiReconnect() is enabled
     * (the default on Android 16).  When the station Wi-Fi network is lost or changes to a
     * state without connectivity, the monitor calls setAdbWifiState(false, reason), which
     * disables wireless debugging.
     *
     * We suppress onLost() and onCapabilitiesChanged() while hotspot is active.
     * User-initiated disables go through Settings.Global directly and are not affected.
     *
     * Android 16 QPR2 AOSP: com.android.server.adb.AdbWifiNetworkMonitor confirmed.
     */
    private fun hookAdbWifiNetworkMonitor(
        classLoader: ClassLoader,
        module: XposedModule,
    ): Boolean {
        val clazz =
            tryFindClass("com.android.server.adb.AdbWifiNetworkMonitor", classLoader) ?: run {
                module.log(Log.DEBUG, TAG, "AdbWifiNetworkMonitor not found (Android < 16?)")
                return false
            }

        val networkClass =
            tryFindClass("android.net.Network", classLoader) ?: run {
                module.log(Log.WARN, TAG, "android.net.Network not found; cannot hook AdbWifiNetworkMonitor")
                return false
            }
        val networkCapabilitiesClass = tryFindClass("android.net.NetworkCapabilities", classLoader)

        module.log(Log.INFO, TAG, "found AdbWifiNetworkMonitor; installing Android 16 NetworkCallback hooks")
        var installed = false

        // onLost(Network): fired when the station Wi-Fi network is lost entirely.
        try {
            val onLost = clazz.getDeclaredMethod("onLost", networkClass).also { it.isAccessible = true }
            module.deoptimize(onLost)
            module.hook(onLost).intercept { chain ->
                val ctx = getContextFromMonitor(chain.getThisObject())
                if (ctx != null && HotspotHelper.isHotspotActive(ctx)) {
                    module.log(
                        Log.INFO,
                        TAG,
                        "blocked AdbWifiNetworkMonitor.onLost (hotspot active; framework-driven disable suppressed)",
                    )
                    null
                } else {
                    chain.proceed()
                }
            }
            module.log(Log.INFO, TAG, "hooked AdbWifiNetworkMonitor.onLost")
            installed = true
        } catch (e: Exception) {
            module.log(Log.WARN, TAG, "failed to hook AdbWifiNetworkMonitor.onLost: $e")
        }

        // onCapabilitiesChanged(Network, NetworkCapabilities): fired when network capabilities
        // change, e.g. when the Wi-Fi network loses connectivity or internet access.
        if (networkCapabilitiesClass != null) {
            try {
                val onCaps =
                    clazz
                        .getDeclaredMethod(
                            "onCapabilitiesChanged",
                            networkClass,
                            networkCapabilitiesClass,
                        ).also { it.isAccessible = true }
                module.deoptimize(onCaps)
                module.hook(onCaps).intercept { chain ->
                    val ctx = getContextFromMonitor(chain.getThisObject())
                    if (ctx != null && HotspotHelper.isHotspotActive(ctx)) {
                        module.log(Log.INFO, TAG, "blocked AdbWifiNetworkMonitor.onCapabilitiesChanged (hotspot active)")
                        null
                    } else {
                        chain.proceed()
                    }
                }
                module.log(Log.INFO, TAG, "hooked AdbWifiNetworkMonitor.onCapabilitiesChanged")
                installed = true
            } catch (e: Exception) {
                module.log(Log.WARN, TAG, "failed to hook AdbWifiNetworkMonitor.onCapabilitiesChanged: $e")
            }
        } else {
            module.log(Log.WARN, TAG, "android.net.NetworkCapabilities not found; onCapabilitiesChanged hook skipped")
        }

        return installed
    }

    private fun hookOnReceive(
        clazz: Class<*>,
        module: XposedModule,
        label: String,
    ): Boolean {
        return try {
            val onReceive =
                clazz
                    .getDeclaredMethod("onReceive", Context::class.java, Intent::class.java)
                    .also { it.isAccessible = true }

            module.hook(onReceive).intercept { chain ->
                val context = chain.getArg(0) as? Context ?: return@intercept chain.proceed()
                val intent = chain.getArg(1) as? Intent ?: return@intercept chain.proceed()
                val action = intent.action ?: return@intercept chain.proceed()

                if ((
                        action == WifiManager.WIFI_STATE_CHANGED_ACTION ||
                            action == WifiManager.NETWORK_STATE_CHANGED_ACTION
                    ) &&
                    HotspotHelper.isHotspotActive(context)
                ) {
                    module.log(Log.INFO, TAG, "suppressed broadcast $action via $label (hotspot active)")
                    null
                } else {
                    chain.proceed()
                }
            }
            module.log(Log.INFO, TAG, "hooked BroadcastReceiver.onReceive via $label")
            true
        } catch (e: Exception) {
            module.log(Log.DEBUG, TAG, "failed to hook onReceive in ${clazz.name}: $e")
            false
        }
    }

    // ---- Context extraction helpers ----

    /**
     * Extract Context from an AdbDebuggingHandler instance.
     *
     * Android 15: handler is an inner class of AdbDebuggingManager.
     *   this$0 → AdbDebuggingManager → mContext
     * Android 16: handler may be top-level with a direct mContext field, or hold a reference
     *   to AdbDebuggingManager.
     */
    private fun getContext(handler: Any?): Context? {
        handler ?: return null
        return try {
            val outer =
                getFieldValue(handler, "this\$0")
                    ?: findFieldByTypeName(handler, "com.android.server.adb.AdbDebuggingManager")
                    ?: handler
            (getFieldValue(outer, "mContext") as? Context)
                ?: (getFieldValue(handler, "mContext") as? Context)
        } catch (e: Exception) {
            Log.w(TAG, "$TAG: failed to get context from handler: $e")
            null
        }
    }

    /**
     * Extract Context from an AdbWifiNetworkMonitor instance.
     *
     * AdbWifiNetworkMonitor holds a reference to AdbDebuggingManager (which has mContext),
     * or potentially has its own mContext field.
     */
    private fun getContextFromMonitor(monitor: Any?): Context? {
        monitor ?: return null
        return try {
            (getFieldValue(monitor, "mContext") as? Context)
                ?: run {
                    val manager =
                        getFieldValue(monitor, "mAdbDebuggingManager")
                            ?: findFieldByTypeName(monitor, "com.android.server.adb.AdbDebuggingManager")
                            ?: getFieldValue(monitor, "this\$0")
                    manager?.let { getFieldValue(it, "mContext") as? Context }
                }
        } catch (e: Exception) {
            Log.w(TAG, "$TAG: failed to get context from AdbWifiNetworkMonitor: $e")
            null
        }
    }

    private fun getHotspotSsid(context: Context): String =
        try {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val config = wm.javaClass.getMethod("getSoftApConfiguration").invoke(wm)
            val wifiSsid = config.javaClass.getMethod("getWifiSsid").invoke(config)
            wifiSsid?.toString() ?: "HotspotAP"
        } catch (_: Throwable) {
            "HotspotAP"
        }

    // ---- Reflection utilities ----

    private fun tryFindClass(
        name: String,
        classLoader: ClassLoader,
    ): Class<*>? =
        try {
            Class.forName(name, false, classLoader)
        } catch (_: ClassNotFoundException) {
            null
        }

    private fun getFieldValue(
        obj: Any,
        name: String,
    ): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            try {
                val field = cls.getDeclaredField(name)
                field.isAccessible = true
                return field.get(obj)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        return null
    }

    private fun findFieldByTypeName(
        obj: Any,
        typeName: String,
    ): Any? {
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            for (field in cls.declaredFields) {
                if (field.type.name == typeName) {
                    field.isAccessible = true
                    return field.get(obj)
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private const val TAG = HotspotAdbModule.TAG
}
