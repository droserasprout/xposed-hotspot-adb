<img src="screenshot.png" align="right" width="300">

# Hotspot Wireless Debugging

LSPosed module that allows Wireless Debugging (ADB over Wi-Fi / TLS pairing) to work over
Wi-Fi Hotspot on Android 15 and Android 16.

Android 11+ only enables Wireless Debugging when the device is connected to Wi-Fi as a client.
This module hooks the Settings app and system framework to bypass that restriction, so hotspot
guests can connect via ADB while the device acts as a SoftAP / hotspot.

## Requirements

| Item | Requirement |
|------|-------------|
| Android | **16** (primary target, Pixel 9a / `tegu`); Android 15 also supported |
| Framework | **LSPosed** (Vector or other modern API 101-compatible fork) |
| Xposed API | Modern libxposed **API 101** — legacy XposedBridge not supported |

> **Note**: This module targets the modern libxposed API 101.  It will **not** load on older
> frameworks that only support the legacy `de.robv.android.xposed` API.  You need a current
> LSPosed build (Vector era or equivalent).

## Installation

Grab the latest signed APK from [GitHub Releases](https://github.com/cbkii/hotspotadb/releases), or [build from source](#building-from-source).

1. Install the APK
2. Enable the module in LSPosed for both scopes:
   - `com.android.settings`
   - `android` (System Framework)
3. Reboot

## Usage

1. Enable Wi-Fi Hotspot
2. Use the Wireless Debugging toggle on the hotspot settings screen, or go to
   Developer Options > Wireless Debugging
3. Pair your client device: `adb pair <ip>:<pairing_port> <pairing_code>`
4. Connect: `adb connect <ip>:<port>`

On first use, Android prompts to trust a network matching your hotspot name.  Renaming the
hotspot will reset this trust.  The MAC address used in the synthetic `AdbConnectionInfo` is
hardcoded (`02:00:00:00:00:00`) because Android randomises the hotspot MAC on each enable, which
would reset trust every time.

## Architecture

The module has two hook domains:

### `com.android.settings` scope
- `WirelessDebuggingPreferenceController.isWifiConnected(Context)` — returns `true` when
  hotspot is active, so the Wireless Debugging UI stays usable
- `AdbIpAddressPreferenceController.getIpv4Address()` — returns the hotspot AP IP instead of
  the station Wi-Fi IP when hotspot is active
- `WifiTetherSettings.onStart()` — injects a Wireless Debugging toggle directly into the
  hotspot settings screen

### `android` scope (system_server)
- `AdbDebuggingHandler.getCurrentWifiApInfo()` — synthesises an `AdbConnectionInfo` when
  hotspot is active but no station Wi-Fi is present
- `AdbWifiNetworkMonitor.onLost()` / `onCapabilitiesChanged()` (Android 16 primary path) —
  suppresses framework-driven `NetworkCallback` events that would disable wireless debugging
  when the device is no longer a Wi-Fi client
- `AdbBroadcastReceiver.onReceive()` (Android 16 secondary path) — suppresses
  `WIFI_STATE_CHANGED` / `NETWORK_STATE_CHANGED` broadcasts on the path active when
  `allowAdbWifiReconnect` is disabled
- Anonymous inner `BroadcastReceiver.onReceive()` scan (Android 15 fallback) — same broadcast
  suppression for Android 15 which uses anonymous inner classes

### Android 16 compatibility

All Android 16 QPR2 class names have been confirmed against AOSP source:

- **AdbConnectionInfo**: `com.android.server.adb.AdbConnectionInfo` (top-level, package-private
  `(String bssid, String ssid)` constructor).  Falls back to nested
  `AdbDebuggingManager$AdbConnectionInfo` for Android 15.
- **Network monitor path A** (default, `allowAdbWifiReconnect` enabled): `AdbWifiNetworkMonitor`
  is a `ConnectivityManager.NetworkCallback`.  `onLost()` and `onCapabilitiesChanged()` are
  hooked to suppress framework-driven ADB Wi-Fi teardown while hotspot is active.
- **Network monitor path B** (`allowAdbWifiReconnect` disabled): `AdbBroadcastReceiver`
  handles `WIFI_STATE_CHANGED` / `NETWORK_STATE_CHANGED` broadcasts.  Hooked via `onReceive()`.
- Both paths are hooked whenever the classes are present; the runtime choice between them is
  made by `AdbDebuggingManager` at boot and cannot be predicted at hook installation time.
- **Android 15 fallback**: if neither named class is found, anonymous inner `BroadcastReceiver`
  classes of `AdbDebuggingHandler` are scanned and hooked.

User-initiated disables (Developer Options toggle, hotspot settings toggle) write
`Settings.Global.ADB_WIFI_ENABLED` directly and are **not** routed through the network monitor
or `AdbBroadcastReceiver`, so the hooks above do not interfere with user intent.

## Building from source

Requires JDK 21 and Android SDK with API 36.

```shell
make build     # build debug APK
make install   # install via Gradle
make clean     # clean build artifacts
```

## Known limitations / runtime verification needed

- `AdbDebuggingHandler.getCurrentWifiApInfo()` is confirmed in AOSP Android 16 QPR2 source.
  On-device validation on build `CP1A.260305.018` is still pending.
- `AdbWifiNetworkMonitor.onLost()` and `onCapabilitiesChanged()` override resolution requires
  on-device validation (method deoptimize + hook chain); if either is not overridden in the
  concrete class, the hook will not be installed but a WARN log entry will appear.
- Settings UI class paths (`WirelessDebuggingPreferenceController`, `WifiTetherSettings`) are
  confirmed standard AOSP names.  On the target device (`SettingsGoogle`,
  `codePath=/system_ext/priv-app/SettingsGoogle`), `pm path` is unreliable; use
  `dumpsys package com.android.settings` to confirm the codePath.
- `WIFI_AP_STATE_ENABLED = 13` is confirmed for Android 15/16.  If a device OEM changes this
  constant, `isHotspotActive()` would return false silently; the WARN log from
  `getWifiApState()` reflection would surface this.

## Troubleshooting

To verify the module loaded correctly, filter LSPosed / logcat for `HotspotAdb`:

```shell
adb logcat -s HotspotAdb
```

Expected log entries after boot:
- `module loaded in system_server` (framework version line follows)
- `hooked AdbDebuggingHandler.getCurrentWifiApInfo`
- `resolved AdbConnectionInfo ctor: com.android.server.adb.AdbConnectionInfo`
- `found AdbWifiNetworkMonitor; installing Android 16 NetworkCallback hooks` (Android 16)
- `hooked AdbWifiNetworkMonitor.onLost`
- `hooked AdbWifiNetworkMonitor.onCapabilitiesChanged`
- `hooked BroadcastReceiver.onReceive via AdbBroadcastReceiver path` (Android 16)
- `module loaded in com.android.settings`
- `hooked WirelessDebuggingPreferenceController.isWifiConnected`
- `hooked AdbIpAddressPreferenceController.getIpv4Address`
- `hooked WifiTetherSettings.onStart`
- `hooked WifiTetherSettings.onStop for listener cleanup`

When hotspot is active and wireless debugging starts:
- `getCurrentWifiApInfo → synthetic (bssid=02:00:00:00:00:00 ssid=...)`
- `blocked AdbWifiNetworkMonitor.onLost (hotspot active; framework-driven disable suppressed)`
- `hotspot IP via wlan1: 10.x.x.x` (or similar interface/IP)

## Other solutions

[Magisk-WiFiADB](https://github.com/mrh929/magisk-wifiadb) — Magisk module, enables legacy
`adb tcpip` on boot.  Simpler (just Magisk, any Android), but unencrypted and not
hotspot-aware.  This module hooks native Wireless Debugging (TLS, pairing) with Settings UI,
but needs LSPosed and Android 15+.

## License

[GPL-3.0](LICENSE)
