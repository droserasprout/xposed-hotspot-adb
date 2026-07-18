package io.drsr.hotspotadb.compat

import android.os.IBinder
import io.drsr.hotspotadb.Xp

/**
 * INetd access. AOSP moved connectivity services into the Tethering/Connectivity
 * mainline module in Android 13 and applied a jarjar rename to the INetd AIDL.
 *
 * Candidate class names in priority order:
 *   - `android.net.INetd$Stub`                                  — legacy (A11/A12, still present on many A13+ builds)
 *   - `com.android.connectivity.android.net.INetd$Stub`         — AOSP jarjar rename (A13+)
 *   - `android.net.connectivity.android.net.INetd$Stub`         — alternative rename spelling seen on some A15/A16 builds
 *
 * INetd$Stub lives in services.jar / the connectivity APEX jar, not on the default
 * classloader. The framework classloader (`lpparam.classLoader` for the "android"
 * package) must be passed in explicitly.
 *
 * Method signatures (`interfaceAddAddress`, `interfaceDelAddress`) are stable across
 * A11+ AIDL versions, so per-method reflection is fine.
 */
object NetdCompat {
    private val CANDIDATE_STUB_NAMES =
        arrayOf(
            "android.net.INetd\$Stub",
            "com.android.connectivity.android.net.INetd\$Stub",
            "android.net.connectivity.android.net.INetd\$Stub",
        )

    fun getNetd(loader: ClassLoader): Any? {
        return try {
            val sm = Class.forName("android.os.ServiceManager", true, loader)
            val binder =
                sm.getMethod("getService", String::class.java).invoke(null, "netd") as? IBinder
                    ?: return null
            val stub =
                ClassRefs.tryFindClass(loader, *CANDIDATE_STUB_NAMES) ?: run {
                    Xp.log("HotspotAdb: INetd\$Stub not found on framework classloader")
                    return null
                }
            stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        } catch (e: Throwable) {
            Xp.log("HotspotAdb: getNetd failed: $e")
            null
        }
    }

    fun interfaceAddAddress(
        netd: Any,
        iface: String,
        address: String,
        prefixLength: Int,
    ) {
        netd.javaClass.getMethod(
            "interfaceAddAddress",
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
        ).invoke(netd, iface, address, prefixLength)
    }

    fun interfaceDelAddress(
        netd: Any,
        iface: String,
        address: String,
        prefixLength: Int,
    ) {
        netd.javaClass.getMethod(
            "interfaceDelAddress",
            String::class.java,
            String::class.java,
            Int::class.javaPrimitiveType,
        ).invoke(netd, iface, address, prefixLength)
    }
}
