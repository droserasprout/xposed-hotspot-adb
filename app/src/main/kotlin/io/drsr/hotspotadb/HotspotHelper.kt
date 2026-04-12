package io.drsr.hotspotadb

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

object HotspotHelper {
    private const val TAG = HotspotAdbModule.TAG
    private const val WIFI_AP_STATE_ENABLED = 13

    fun isHotspotActive(context: Context): Boolean =
        try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wifiManager.javaClass.getMethod("getWifiApState")
            val state = method.invoke(wifiManager) as Int
            state == WIFI_AP_STATE_ENABLED
        } catch (e: Exception) {
            Log.w(TAG, "HotspotAdb: failed to check hotspot state: $e")
            false
        }

    /**
     * Returns the IP address of the hotspot (AP) interface.
     * Filters out loopback, mobile data (rmnet*), non-wlan interfaces, and the station Wi-Fi IP.
     */
    fun getHotspotIpAddress(context: Context): String? {
        val stationIp = getStationWifiIp(context)
        return getApInterfaceIp(excludeIp = stationIp)
    }

    /** Returns any wlan/ap interface IP, optionally excluding one. */
    fun getAnyWlanIp(excludeIp: String? = null): String? = getApInterfaceIp(excludeIp)

    private fun getApInterfaceIp(excludeIp: String? = null): String? {
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
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip == excludeIp) {
                            Log.d(TAG, "$TAG: skipping ${iface.name} ($ip) — station Wi-Fi IP")
                            continue
                        }
                        Log.i(TAG, "$TAG: hotspot IP via ${iface.name}: $ip")
                        return ip
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "$TAG: failed to get hotspot IP: $e")
        }
        return null
    }

    @Suppress("DEPRECATION")
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
