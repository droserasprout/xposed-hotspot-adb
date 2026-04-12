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
import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * Hooks inside the com.android.settings process.
 *
 * Three hooks:
 *
 * 1. WirelessDebuggingPreferenceController.isWifiConnected(Context)
 *    Returns true when hotspot is active so the Wireless Debugging UI stays usable.
 *
 * 2. AdbIpAddressPreferenceController.getIpv4Address()
 *    Returns the hotspot AP interface IP instead of the station Wi-Fi IP when hotspot is active.
 *
 * 3. WifiTetherSettings.onStart() / onStop()
 *    onStart: injects a "Wireless debugging" toggle into the hotspot settings screen and
 *             registers a ContentObserver + BroadcastReceiver to keep it in sync.
 *    onStop:  unregisters the ContentObserver and BroadcastReceiver, cancels pending handler
 *             callbacks.  Prevents leaks across navigation in/out of the hotspot settings screen.
 *
 * Lifecycle safety
 * - The preference injection and listener registration are separate concerns.
 * - When the fragment resumes after onStop (clean state), the preference is retrieved from the
 *   existing PreferenceScreen and listeners are re-registered.
 * - The fragmentExtras WeakHashMap holds state keyed on fragment instances; entries are eligible
 *   for GC when the fragment is destroyed.
 *
 * No XposedHelpers in modern API — all reflection uses java.lang.reflect directly.
 */
object SettingsHook {
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    // Per-fragment extra data (observer, receiver, handler, context, runnable).
    // Keyed weakly so GC'd fragment instances are automatically cleaned up.
    private val fragmentExtras: MutableMap<Any, MutableMap<String, Any?>> =
        Collections.synchronizedMap(WeakHashMap())

    fun install(
        classLoader: ClassLoader,
        module: XposedModule,
    ) {
        hookIsWifiConnected(classLoader, module)
        hookGetIpv4Address(classLoader, module)
        hookWifiTetherSettings(classLoader, module)
    }

    // ---- isWifiConnected ----

    private fun hookIsWifiConnected(
        classLoader: ClassLoader,
        module: XposedModule,
    ) {
        val clazz =
            tryFindClass(
                "com.android.settings.development.WirelessDebuggingPreferenceController",
                classLoader,
            ) ?: run {
                module.log(Log.WARN, TAG, "WirelessDebuggingPreferenceController not found; isWifiConnected hook skipped")
                return
            }
        val method =
            tryGetMethod(clazz, "isWifiConnected", Context::class.java) ?: run {
                module.log(Log.WARN, TAG, "isWifiConnected not found in WirelessDebuggingPreferenceController")
                return
            }

        module.hook(method).intercept { chain ->
            val result = chain.proceed()
            if (result == false) {
                val context = chain.getArg(0) as? Context ?: return@intercept result
                if (HotspotHelper.isHotspotActive(context)) {
                    module.log(Log.INFO, TAG, "isWifiConnected → true (hotspot active; allowing Wireless Debugging UI)")
                    true
                } else {
                    result
                }
            } else {
                result
            }
        }
        module.log(Log.INFO, TAG, "hooked WirelessDebuggingPreferenceController.isWifiConnected")
    }

    // ---- getIpv4Address ----

    private fun hookGetIpv4Address(
        classLoader: ClassLoader,
        module: XposedModule,
    ) {
        val clazz =
            tryFindClass(
                "com.android.settings.development.AdbIpAddressPreferenceController",
                classLoader,
            ) ?: run {
                module.log(Log.WARN, TAG, "AdbIpAddressPreferenceController not found; getIpv4Address hook skipped")
                return
            }
        val method =
            tryGetMethod(clazz, "getIpv4Address") ?: run {
                module.log(Log.WARN, TAG, "getIpv4Address not found in AdbIpAddressPreferenceController")
                return
            }

        module.hook(method).intercept { chain ->
            val thisObj = chain.getThisObject() ?: return@intercept chain.proceed()
            val context =
                getFieldValue(thisObj, "mContext") as? Context
                    ?: return@intercept chain.proceed()
            if (!HotspotHelper.isHotspotActive(context)) return@intercept chain.proceed()
            val ip = HotspotHelper.getHotspotIpAddress(context) ?: return@intercept chain.proceed()
            module.log(Log.INFO, TAG, "getIpv4Address → $ip (hotspot AP interface)")
            ip
        }
        module.log(Log.INFO, TAG, "hooked AdbIpAddressPreferenceController.getIpv4Address")
    }

    // ---- WifiTetherSettings.onStart / onStop ----

    private fun hookWifiTetherSettings(
        classLoader: ClassLoader,
        module: XposedModule,
    ) {
        val clazz =
            tryFindClass(
                "com.android.settings.wifi.tether.WifiTetherSettings",
                classLoader,
            ) ?: run {
                module.log(Log.WARN, TAG, "WifiTetherSettings not found; hotspot toggle injection skipped")
                return
            }

        // onStart: inject preference (if not already present) and register observers.
        val onStart =
            tryGetMethod(clazz, "onStart") ?: run {
                module.log(Log.WARN, TAG, "WifiTetherSettings.onStart not found")
                return
            }
        module.hook(onStart).intercept { chain ->
            chain.proceed()
            try {
                injectWirelessDebuggingPref(chain.getThisObject()!!, classLoader, module)
            } catch (e: Throwable) {
                module.log(Log.ERROR, TAG, "failed to inject wireless debugging preference: $e")
            }
            null
        }
        module.log(Log.INFO, TAG, "hooked WifiTetherSettings.onStart")

        // onStop: unregister observers and cancel pending handler callbacks.
        val onStop =
            tryGetMethod(clazz, "onStop") ?: run {
                module.log(Log.WARN, TAG, "WifiTetherSettings.onStop not found; cleanup hook skipped (potential listener leak)")
                return
            }
        module.hook(onStop).intercept { chain ->
            try {
                cleanupFragment(chain.getThisObject()!!, module)
            } catch (e: Throwable) {
                module.log(Log.ERROR, TAG, "fragment cleanup failed: $e")
            }
            chain.proceed()
            null
        }
        module.log(Log.INFO, TAG, "hooked WifiTetherSettings.onStop for listener cleanup")
    }

    /**
     * Inject the wireless debugging toggle into the hotspot PreferenceScreen, then register
     * the ContentObserver and BroadcastReceiver that keep it in sync.
     *
     * The preference injection and listener registration are intentionally separate:
     * - The preference is kept alive as long as the PreferenceScreen is retained.
     * - Listeners are registered on onStart and unregistered on onStop.
     * - After onStop cleanup, fragmentExtras is cleared; the next onStart re-registers listeners
     *   on the already-existing preference retrieved from the screen.
     */
    private fun injectWirelessDebuggingPref(
        fragment: Any,
        classLoader: ClassLoader,
        module: XposedModule,
    ) {
        val screen =
            callMethod(fragment, "getPreferenceScreen") ?: run {
                module.log(Log.WARN, TAG, "getPreferenceScreen returned null")
                return
            }
        val context =
            callMethod(screen, "getContext") as? Context ?: run {
                module.log(Log.WARN, TAG, "could not get Context from PreferenceScreen")
                return
            }

        // Step 1: get the preference, injecting it if this is the first onStart.
        val existingPref = callMethod(screen, "findPreference", "hotspot_adb_wireless_debugging")
        val pref: Any =
            if (existingPref != null) {
                existingPref
            } else {
                createAndAddPreference(screen, context, classLoader, module) ?: return
            }

        // Step 2: register state listeners, idempotent via fragmentExtras.
        // Always run this step; after onStop cleanup the fragmentExtras are cleared and
        // listeners must be re-registered even though the preference object is still present.
        registerListenersIfNeeded(fragment, context, pref, module)
    }

    /**
     * Create the wireless debugging SwitchPreference and add it to the PreferenceScreen.
     * Returns the created preference, or null if creation fails.
     */
    private fun createAndAddPreference(
        screen: Any,
        context: Context,
        classLoader: ClassLoader,
        module: XposedModule,
    ): Any? {
        val prefClass =
            tryFindClass("com.android.settingslib.PrimarySwitchPreference", classLoader)
                ?: tryFindClass("androidx.preference.SwitchPreferenceCompat", classLoader)
                ?: run {
                    module.log(Log.WARN, TAG, "no usable Preference subclass found; toggle injection skipped")
                    return null
                }
        val pref = prefClass.getConstructor(Context::class.java).newInstance(context)

        callMethod(pref, "setKey", "hotspot_adb_wireless_debugging")
        callMethod(pref, "setTitle", "Wireless debugging" as CharSequence)
        val enabled = isAdbWifiEnabled(context)
        callMethod(pref, "setChecked", enabled)
        callMethod(pref, "setSummary", getWirelessDebuggingSummary(context, enabled) as CharSequence)

        // Toggle switch — enable/disable ADB Wi-Fi via Settings.Global.
        // This is a user-initiated action: it writes directly and is not blocked by any hook.
        val changeListenerClass =
            tryFindClass(
                "androidx.preference.Preference\$OnPreferenceChangeListener",
                classLoader,
            ) ?: run {
                module.log(Log.WARN, TAG, "OnPreferenceChangeListener class not found; toggle will not respond")
                return null
            }
        val changeProxy =
            java.lang.reflect.Proxy.newProxyInstance(
                classLoader,
                arrayOf(changeListenerClass),
            ) { _, _, args ->
                val newValue = args!![1] as Boolean
                module.log(Log.INFO, TAG, "user toggled wireless debugging via hotspot screen: $newValue")
                Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, if (newValue) 1 else 0)
                callMethod(pref, "setSummary", getWirelessDebuggingSummary(context, newValue) as CharSequence)
                true
            }
        callMethod(pref, "setOnPreferenceChangeListener", changeProxy)

        // Click on left/title area → open the full Wireless Debugging screen.
        val clickListenerClass =
            tryFindClass(
                "androidx.preference.Preference\$OnPreferenceClickListener",
                classLoader,
            )
        if (clickListenerClass != null) {
            val clickProxy =
                java.lang.reflect.Proxy.newProxyInstance(
                    classLoader,
                    arrayOf(clickListenerClass),
                ) { _, _, _ ->
                    try {
                        val subSettingsClass =
                            tryFindClass("com.android.settings.SubSettings", context.classLoader)
                                ?: tryFindClass("com.android.settings.SubSettings", classLoader)
                        if (subSettingsClass != null) {
                            val intent = android.content.Intent(context, subSettingsClass)
                            intent.putExtra(
                                ":settings:show_fragment",
                                "com.android.settings.development.WirelessDebuggingFragment",
                            )
                            context.startActivity(intent)
                        }
                    } catch (e: Exception) {
                        module.log(Log.WARN, TAG, "failed to open Wireless Debugging screen: $e")
                    }
                    true
                }
            callMethod(pref, "setOnPreferenceClickListener", clickProxy)
        }

        callMethod(screen, "addPreference", pref)
        module.log(Log.INFO, TAG, "injected wireless debugging toggle into hotspot settings")
        return pref
    }

    /**
     * Register ContentObserver and BroadcastReceiver to keep the toggle in sync.
     *
     * Idempotent via fragmentExtras: does nothing if listeners are already registered.
     * After onStop cleanup (fragmentExtras cleared), this will re-register on the next onStart.
     */
    private fun registerListenersIfNeeded(
        fragment: Any,
        context: Context,
        pref: Any,
        module: XposedModule,
    ) {
        // Store context unconditionally so cleanupFragment can unregister regardless of which
        // listener block succeeds (e.g. if registerContentObserver throws before storing it).
        setFragmentExtra(fragment, "hotspot_adb_context", context)

        if (getFragmentExtra(fragment, "hotspot_adb_observer") == null) {
            val handler = Handler(Looper.getMainLooper())
            val uri = Settings.Global.getUriFor(ADB_WIFI_ENABLED)
            val observer =
                object : ContentObserver(handler) {
                    override fun onChange(
                        selfChange: Boolean,
                        uri: Uri?,
                    ) {
                        val on = isAdbWifiEnabled(context)
                        callMethod(pref, "setChecked", on)
                        callMethod(pref, "setSummary", getWirelessDebuggingSummary(context, on) as CharSequence)
                    }
                }
            context.contentResolver.registerContentObserver(uri, false, observer)
            setFragmentExtra(fragment, "hotspot_adb_observer", observer)
            module.log(Log.DEBUG, TAG, "registered ContentObserver for WifiTetherSettings")
        }

        if (getFragmentExtra(fragment, "hotspot_adb_receiver") == null) {
            val handler = Handler(Looper.getMainLooper())
            val updatePref =
                Runnable {
                    val on = isAdbWifiEnabled(context)
                    callMethod(pref, "setChecked", on)
                    callMethod(pref, "setSummary", getWirelessDebuggingSummary(context, on) as CharSequence)
                }
            val receiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        ctx: Context,
                        intent: Intent,
                    ) {
                        // Update immediately and again after 1 s — hotspot IP may not be available yet.
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
            setFragmentExtra(fragment, "hotspot_adb_receiver", receiver)
            setFragmentExtra(fragment, "hotspot_adb_handler", handler)
            setFragmentExtra(fragment, "hotspot_adb_runnable", updatePref)
            module.log(Log.DEBUG, TAG, "registered BroadcastReceiver for WifiTetherSettings")
        }
    }

    /**
     * Unregister ContentObserver and BroadcastReceiver, cancel pending handler callbacks.
     * Called from the WifiTetherSettings.onStop hook.
     */
    private fun cleanupFragment(
        fragment: Any,
        module: XposedModule,
    ) {
        val extras = fragmentExtras.remove(fragment) ?: return

        val context = extras["hotspot_adb_context"] as? Context
        val observer = extras["hotspot_adb_observer"] as? ContentObserver
        val receiver = extras["hotspot_adb_receiver"] as? BroadcastReceiver
        val handler = extras["hotspot_adb_handler"] as? Handler
        val runnable = extras["hotspot_adb_runnable"] as? Runnable

        if (runnable != null) handler?.removeCallbacks(runnable)
        handler?.removeCallbacksAndMessages(null)

        if (context != null) {
            if (observer != null) {
                try {
                    context.contentResolver.unregisterContentObserver(observer)
                } catch (e: Exception) {
                    Log.d(TAG, "$TAG: unregisterContentObserver failed: $e")
                }
            }
            if (receiver != null) {
                try {
                    context.unregisterReceiver(receiver)
                } catch (e: Exception) {
                    Log.d(TAG, "$TAG: unregisterReceiver failed: $e")
                }
            }
        }

        module.log(Log.DEBUG, TAG, "cleaned up listeners for WifiTetherSettings")
    }

    // ---- Helpers ----

    private fun isAdbWifiEnabled(context: Context): Boolean = Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1

    private fun getWirelessDebuggingSummary(
        context: Context,
        enabled: Boolean,
    ): String {
        if (!enabled) return ""
        val ip =
            HotspotHelper.getHotspotIpAddress(context)
                ?: HotspotHelper.getAnyWlanIp()
                ?: return ""
        val port = getAdbWirelessPort()
        return if (port > 0) "$ip:$port" else ip
    }

    private fun getAdbWirelessPort(): Int =
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val binder =
                serviceManagerClass
                    .getMethod("getService", String::class.java)
                    .invoke(null, "adb")
            val stub = Class.forName("android.debug.IAdbManager\$Stub")
            val adbService =
                stub
                    .getMethod("asInterface", android.os.IBinder::class.java)
                    .invoke(null, binder)
            adbService.javaClass.getMethod("getAdbWirelessPort").invoke(adbService) as Int
        } catch (_: Throwable) {
            -1
        }

    private fun tryFindClass(
        name: String,
        classLoader: ClassLoader,
    ): Class<*>? =
        try {
            Class.forName(name, false, classLoader)
        } catch (_: ClassNotFoundException) {
            null
        }

    private fun tryGetMethod(
        clazz: Class<*>,
        name: String,
        vararg params: Class<*>,
    ): Method? =
        try {
            clazz.getDeclaredMethod(name, *params).also { it.isAccessible = true }
        } catch (_: NoSuchMethodException) {
            null
        }

    /**
     * Calls a method by name, choosing the best overload by matching parameter count and
     * argument types.  Handles boxed→primitive coercion so setChecked(Boolean) works correctly.
     */
    @Suppress("SpreadOperator")
    private fun callMethod(
        obj: Any,
        name: String,
        vararg args: Any?,
    ): Any? =
        try {
            val method =
                obj.javaClass.methods.firstOrNull { m ->
                    m.name == name &&
                        m.parameterCount == args.size &&
                        args.indices.all { i ->
                            val param = m.parameterTypes[i]
                            val arg = args[i]
                            arg == null || param.isInstance(arg) || isPrimitiveCompatible(param, arg)
                        }
                } ?: throw NoSuchMethodException(
                    "${obj.javaClass.name}.$name(${args.joinToString { it?.javaClass?.simpleName ?: "null" }})",
                )
            method.invoke(obj, *args)
        } catch (e: Exception) {
            Log.w(TAG, "$TAG: callMethod($name) failed: $e")
            null
        }

    private fun isPrimitiveCompatible(
        primitive: Class<*>,
        value: Any,
    ): Boolean =
        when (primitive) {
            Boolean::class.javaPrimitiveType -> value is Boolean
            Int::class.javaPrimitiveType -> value is Int
            Long::class.javaPrimitiveType -> value is Long
            Float::class.javaPrimitiveType -> value is Float
            Double::class.javaPrimitiveType -> value is Double
            Byte::class.javaPrimitiveType -> value is Byte
            Short::class.javaPrimitiveType -> value is Short
            Char::class.javaPrimitiveType -> value is Char
            else -> false
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

    // WeakHashMap-based replacement for XposedHelpers.setAdditionalInstanceField /
    // getAdditionalInstanceField.  Keys are held weakly so GC'd fragments are cleaned up.
    private fun setFragmentExtra(
        obj: Any,
        key: String,
        value: Any?,
    ) {
        fragmentExtras.getOrPut(obj) { mutableMapOf() }[key] = value
    }

    private fun getFragmentExtra(
        obj: Any,
        key: String,
    ): Any? = fragmentExtras[obj]?.get(key)

    private const val TAG = HotspotAdbModule.TAG
}
