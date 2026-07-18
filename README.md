<!-- markdownlint-disable MD033 MD041 -->
<img src="screen1.png" alt="Hotspot settings screen" align="right" width="220">

# Hotspot Wireless Debugging

Xposed module that makes Wireless Debugging (ADB over Wi-Fi) work over Wi-Fi Hotspot on Android 15/16.

Android 11+ only enables Wireless Debugging when the device is connected to Wi-Fi as a client. This module removes that restriction so hotspot guests can connect via ADB.

## Requirements

- Android 15 to 16 QPR1
- Magisk or other Zygisk implementation
- LSPosed/Vector

### Tested configurations

| Device    | Android | ROM            | Zygisk                          | Xposed                         |
| --------- | ------- | -------------- | ------------------------------- | ------------------------------ |
| enchilada | 15      | LineageOS 22.2 | Magisk 30.7 </br> NeoZygisk 2.3 | LSPosed 1.9.2 </br> Vector 2.0 |
| tucana    | 16      | LineageOS 23.2 | Magisk 30.7                     | Vector 2.0                     |
| enchilada | 16 QPR1 | crDroid 12.5   | Magisk 30.7                     | LSPosed 2.1.0                  |

If this module works (or not) on your device/ROM, please [open an issue](https://github.com/droserasprout/io.drsr.hotspotadb/issues).

## Installation

Grab the APK from Xposed Module Repo, [GitHub Releases](https://github.com/droserasprout/io.drsr.hotspotadb/releases), or [build from source](#building-from-source).

1. Install the APK
2. Enable the module in LSPosed for two scopes:
   - `com.android.settings`
   - `system` (System Framework)
3. Reboot

## Usage

1. Enable Wi-Fi Hotspot
2. Use the Wireless Debugging toggle on the hotspot settings screen, or go to Developer Options > Wireless Debugging
3. On the first connection, pair your client device: `adb pair <ip>:<pairing_port> <pairing_code>`
4. Connect: `adb connect <ip>:<port>`

### Fixed IP/port (optional)

<img src="screen2.png" alt="Fixes IP/port setting" align="right" width="220">

Flip **Fixed IP/port** to always listen on `192.168.49.1:5555`. Lets you script the command without needing mDNS in your `adb` build. Pairing still uses the ephemeral port shown on screen (one-time step).

How it works:

- `192.168.49.1/24` is aliased on the hotspot interface via netd (secondary address)
- A TCP proxy in `system_server` listens on `:5555` and forwards to adbd's ephemeral TLS port (TLS is end-to-end, proxy is just a byte pipe)

Trade-off: if your upstream network also uses `192.168.49.0/24`, leave this feature off to avoid routing collisions.

## Building from source

Requires JDK 21 and Android SDK.

```shell
make build     # debug APK
make install   # install via Gradle
make clean
```

## Other solutions

[Magisk-WiFiADB](https://github.com/mrh929/magisk-wifiadb) — enables legacy `adb tcpip` on boot. Simpler (Magisk only, any Android), but unencrypted and not hotspot-aware.

## License

[GPL-3.0](LICENSE)
