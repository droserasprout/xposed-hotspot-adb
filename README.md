<img src="screenshot.png" align="right" width="300">

# Hotspot Wireless Debugging

Xposed module that allows Wireless Debugging (ADB over Wi-Fi) to work over Wi-Fi Hotspot on Android 15.

Android 11+ only enables Wireless Debugging when the device is connected to Wi-Fi as a client. This module hooks the Settings app and system framework to bypass that restriction, so hotspot guests can connect via ADB.

## Requirements

- Android 15 (older versions not tested)
- LSPosed or compatible Xposed framework

## Installation

Grab the latest APK from [GitHub Actions](https://github.com/droserasprout/xposed-hotspot-adb/actions) artifacts, or [build from source](#building-from-source).

1. Install the APK
2. Enable the module in LSPosed for both scopes:
   - `com.android.settings`
   - `android` (System Framework)
3. Reboot

## Usage

1. Enable Wi-Fi Hotspot
2. Use the Wireless Debugging toggle on the hotspot settings screen, or go to Developer Options > Wireless Debugging
3. Pair your client device: `adb pair <ip>:<pairing_port> <pairing_code>`
4. Connect: `adb connect <ip>:<port>`

On first use, Android prompts to trust a network matching your hotspot name. Renaming the hotspot will reset this trust. The MAC address is hardcoded because Android randomizes the hotspot MAC on each enable, which would reset trust every time.

## Other solutions

[Magisk-WiFiADB](https://github.com/mrh929/magisk-wifiadb) — a Magisk module that enables legacy `adb tcpip` on boot by setting system properties. Simpler setup (no Xposed needed, just Magisk), works on older Android versions, and enables ADB automatically on boot. However, it uses the old unencrypted ADB protocol (no TLS, no pairing required but also no authentication), has no Settings UI integration, and isn't hotspot-aware.

This module uses Android's native Wireless Debugging (TLS-encrypted, with device pairing) and integrates into the Settings app, but requires an Xposed framework (LSPosed) and currently targets Android 15 only.

## Building from source

Requires JDK 21 and Android SDK.

```shell
make build     # build debug APK
make install   # install via Gradle
make clean     # clean build artifacts
```
