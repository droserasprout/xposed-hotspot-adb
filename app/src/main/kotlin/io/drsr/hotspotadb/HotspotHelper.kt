package io.drsr.hotspotadb

import android.content.Context
import android.net.wifi.WifiManager
import de.robv.android.xposed.XposedBridge
import java.net.Inet4Address
import java.net.NetworkInterface

object HotspotHelper {
    private const val WIFI_AP_STATE_ENABLED = 13

    fun isHotspotActive(context: Context): Boolean {
        return try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wifiManager.javaClass.getMethod("getWifiApState")
            val state = method.invoke(wifiManager) as Int
            state == WIFI_AP_STATE_ENABLED
        } catch (e: Exception) {
            XposedBridge.log("HotspotAdb: failed to check hotspot state: $e")
            false
        }
    }

    /**
     * Returns the IP address of the hotspot (AP) interface.
     * Filters out loopback, mobile data (rmnet*), and station Wi-Fi interfaces.
     */
    fun getHotspotIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                // Skip mobile data interfaces
                if (iface.name.startsWith("rmnet")) continue
                // Skip non-wlan interfaces (dummy, bond, p2p, etc.)
                if (!iface.name.startsWith("wlan") && !iface.name.startsWith("ap") && !iface.name.startsWith("swlan")) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("HotspotAdb: failed to get hotspot IP: $e")
        }
        return null
    }

    /**
     * Returns the hotspot IP, excluding the station Wi-Fi IP if Wi-Fi client is also connected.
     */
    fun getHotspotIpAddress(context: Context): String? {
        val stationIp = getStationWifiIp(context)
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                if (iface.name.startsWith("rmnet")) continue
                if (!iface.name.startsWith("wlan") && !iface.name.startsWith("ap") && !iface.name.startsWith("swlan")) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip != stationIp) return ip
                    }
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("HotspotAdb: failed to get hotspot IP: $e")
        }
        return null
    }

    private fun getStationWifiIp(context: Context): String? {
        return try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            if (ipInt == 0) return null
            "${ipInt and 0xFF}.${ipInt shr 8 and 0xFF}.${ipInt shr 16 and 0xFF}.${ipInt shr 24 and 0xFF}"
        } catch (_: Exception) {
            null
        }
    }
}
