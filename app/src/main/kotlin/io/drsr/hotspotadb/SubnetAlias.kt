package io.drsr.hotspotadb

import android.content.Context
import de.robv.android.xposed.XposedBridge
import io.drsr.hotspotadb.compat.NetdCompat
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Adds FIXED_IP as a secondary address on the hotspot interface via netd.
 * Runs in system_server, which has NETWORK_STACK permission. Clients on the
 * primary hotspot subnet can reach FIXED_IP via their default gateway (the device).
 */
object SubnetAlias {
    @Volatile
    private var appliedIface: String? = null

    @Volatile
    private var frameworkLoader: ClassLoader? = null

    fun setClassLoader(loader: ClassLoader) {
        frameworkLoader = loader
    }

    @Synchronized
    fun apply(context: Context) {
        val iface = HotspotHelper.getApInterfaceName(context) ?: return
        if (appliedIface == iface && hasFixedIp(iface)) return
        if (appliedIface != null && appliedIface != iface) remove()
        val netd = getNetd() ?: return
        try {
            NetdCompat.interfaceAddAddress(netd, iface, HotspotHelper.FIXED_IP, 24)
            appliedIface = iface
            XposedBridge.log("HotspotAdb: aliased ${HotspotHelper.FIXED_IP}/24 on $iface")
        } catch (e: Throwable) {
            XposedBridge.log("HotspotAdb: interfaceAddAddress failed on $iface: $e")
        }
    }

    @Synchronized
    fun remove() {
        val iface = appliedIface ?: return
        appliedIface = null
        val netd = getNetd() ?: return
        try {
            NetdCompat.interfaceDelAddress(netd, iface, HotspotHelper.FIXED_IP, 24)
            XposedBridge.log("HotspotAdb: removed ${HotspotHelper.FIXED_IP}/24 from $iface")
        } catch (e: Throwable) {
            XposedBridge.log("HotspotAdb: interfaceDelAddress failed on $iface: $e")
        }
    }

    private fun hasFixedIp(iface: String): Boolean {
        return try {
            val ni = NetworkInterface.getByName(iface) ?: return false
            ni.inetAddresses.toList().any {
                it is Inet4Address && it.hostAddress == HotspotHelper.FIXED_IP
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun getNetd(): Any? {
        val loader = frameworkLoader ?: return null
        return NetdCompat.getNetd(loader)
    }
}
