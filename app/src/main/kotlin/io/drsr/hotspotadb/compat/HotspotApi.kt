package io.drsr.hotspotadb.compat

import android.content.Context
import android.net.wifi.WifiManager
import io.drsr.hotspotadb.Xp

object HotspotApi {
    private const val WIFI_AP_STATE_ENABLED = 13
    private const val DEFAULT_SSID = "HotspotAP"

    fun isApEnabled(context: Context): Boolean {
        return try {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val state = wm.javaClass.getMethod("getWifiApState").invoke(wm) as Int
            state == WIFI_AP_STATE_ENABLED
        } catch (e: Exception) {
            Xp.log("HotspotAdb: failed to check hotspot state: $e")
            false
        }
    }

    /**
     * Reads the SoftAp SSID. `getWifiSsid()` (returning WifiSsid) was added in Android 13;
     * earlier versions only expose `getSsid()` returning a String. Falls back to a constant
     * so the framework's synthetic AdbConnectionInfo always gets a non-null value.
     */
    fun getHotspotSsid(context: Context): String {
        return try {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val config = wm.javaClass.getMethod("getSoftApConfiguration").invoke(wm) ?: return DEFAULT_SSID
            ssidFromWifiSsid(config) ?: ssidFromString(config) ?: DEFAULT_SSID
        } catch (_: Throwable) {
            DEFAULT_SSID
        }
    }

    private fun ssidFromWifiSsid(config: Any): String? {
        return try {
            config.javaClass.getMethod("getWifiSsid").invoke(config)?.toString()
        } catch (_: Throwable) {
            null
        }
    }

    private fun ssidFromString(config: Any): String? {
        return try {
            config.javaClass.getMethod("getSsid").invoke(config) as? String
        } catch (_: Throwable) {
            null
        }
    }

    @Suppress("DEPRECATION")
    fun getStationWifiIp(context: Context): String? {
        return try {
            val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wm.connectionInfo.ipAddress
            if (ipInt == 0) return null
            "${ipInt and 0xFF}.${ipInt shr 8 and 0xFF}.${ipInt shr 16 and 0xFF}.${ipInt shr 24 and 0xFF}"
        } catch (_: Exception) {
            null
        }
    }
}
