package io.drsr.hotspotadb.compat

import android.os.IBinder

object AdbManagerCompat {
    /** Returns adbd's current wireless TLS port, or -1 if unavailable. */
    fun getWirelessPort(): Int {
        return try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val binder =
                serviceManager.getMethod("getService", String::class.java)
                    .invoke(null, "adb") as? IBinder ?: return -1
            val stub = Class.forName("android.debug.IAdbManager\$Stub")
            val service =
                stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: return -1
            service.javaClass.getMethod("getAdbWirelessPort").invoke(service) as Int
        } catch (_: Throwable) {
            -1
        }
    }
}
