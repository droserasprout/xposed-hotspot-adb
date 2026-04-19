package io.drsr.hotspotadb

import android.content.Context
import android.provider.Settings
import de.robv.android.xposed.XposedBridge
import io.drsr.hotspotadb.compat.AdbManagerCompat
import io.drsr.hotspotadb.compat.HotspotApi
import java.net.Inet4Address
import java.net.NetworkInterface

object HotspotHelper {
    const val FIXED_ENDPOINT_KEY = "hotspot_adb_fixed_endpoint"
    const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    const val FIXED_IP = "192.168.49.1"
    const val FIXED_PORT = 5555

    fun isFixedEndpointEnabled(context: Context): Boolean {
        return Settings.Global.getInt(context.contentResolver, FIXED_ENDPOINT_KEY, 0) == 1
    }

    fun isAdbWifiEnabled(context: Context): Boolean {
        return Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) == 1
    }

    fun getAdbWirelessPort(): Int = AdbManagerCompat.getWirelessPort()

    fun isHotspotActive(context: Context): Boolean = HotspotApi.isApEnabled(context)

    /**
     * Returns the IP address of the hotspot (AP) interface.
     * Filters out loopback, mobile data (rmnet*), non-wlan interfaces, and the station Wi-Fi IP.
     */
    fun getHotspotIpAddress(context: Context): String? {
        val stationIp = HotspotApi.getStationWifiIp(context)
        return getApInterfaceIp(excludeIp = stationIp)
    }

    /** Returns any wlan/ap interface IP, optionally excluding one. */
    fun getAnyWlanIp(excludeIp: String? = null): String? = getApInterfaceIp(excludeIp)

    /** Returns the AP interface name (e.g. "wlan1"), excluding the station Wi-Fi iface. */
    fun getApInterfaceName(context: Context): String? {
        val stationIp = HotspotApi.getStationWifiIp(context)
        return findApInterface(excludeIp = stationIp)?.name
    }

    private fun getApInterfaceIp(excludeIp: String? = null): String? {
        val iface = findApInterface(excludeIp) ?: return null
        for (addr in iface.inetAddresses) {
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                val ip = addr.hostAddress ?: continue
                if (ip != excludeIp) return ip
            }
        }
        return null
    }

    private fun findApInterface(excludeIp: String? = null): NetworkInterface? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                if (iface.name.startsWith("rmnet")) continue
                if (!iface.name.startsWith("wlan") &&
                    !iface.name.startsWith("ap") &&
                    !iface.name.startsWith("swlan")
                ) {
                    continue
                }
                val hasMatchingIp =
                    iface.inetAddresses.toList().any { addr ->
                        addr is Inet4Address && !addr.isLoopbackAddress && addr.hostAddress != excludeIp
                    }
                if (hasMatchingIp) return iface
            }
        } catch (e: Exception) {
            XposedBridge.log("HotspotAdb: failed to find AP interface: $e")
        }
        return null
    }
}
