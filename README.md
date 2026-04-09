# Hotspot Wireless Debugging

Xposed module that allows Wireless Debugging (ADB over Wi-Fi) to work over Wi-Fi Hotspot on Android 15.

Android 11+ only enables Wireless Debugging when the device is connected to a Wi-Fi network as a client. This module hooks the Settings app and system framework to bypass that restriction, so hotspot guests can connect via ADB.

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
2. Go to Settings > Developer Options > Wireless Debugging
3. Enable the toggle, pair your client device
4. From a hotspot guest:

```shell
adb pair <ip>:<pairing_port>
adb connect <ip>:<port>
```

On first use, Android prompts to trust a network called "HotspotAP" — this is a virtual identity for the hotspot interface. Fixed values are used because Android randomizes hotspot MAC on each enable, which would reset trust every time.

## Building from source

Requires JDK 21 and Android SDK.

```shell
make build     # build debug APK
make install   # install via Gradle
make clean     # clean build artifacts
```
