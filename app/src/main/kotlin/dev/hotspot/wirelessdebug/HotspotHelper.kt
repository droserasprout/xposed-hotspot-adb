package dev.hotspot.wirelessdebug

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
            XposedBridge.log("HotspotWirelessDebug: failed to check hotspot state: $e")
            false
        }
    }

    fun getHotspotIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("HotspotWirelessDebug: failed to get hotspot IP: $e")
        }
        return null
    }
}
