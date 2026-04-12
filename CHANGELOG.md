# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.1.0] - 2026-04-12

### Fixed

- **Critical: `AdbWifiNetworkMonitor` hook was not installed.**
  The previous implementation tried to cast `AdbWifiNetworkMonitor` as a `BroadcastReceiver`
  and silently skipped it.  `AdbWifiNetworkMonitor` is a `ConnectivityManager.NetworkCallback`
  (confirmed Android 16 QPR2 AOSP).  It is now hooked correctly via `onLost()` and
  `onCapabilitiesChanged()`.

- **Critical: removed `Settings.Global.putInt` fallback.**
  The previous last-resort fallback blocked any `adb_wifi_enabled=0` write while hotspot was
  active, including explicit user-initiated disables from Developer Options and the hotspot
  settings toggle.  This was incorrect and dangerous.  The fallback has been removed.  The
  proper Android 16 hooks (`AdbWifiNetworkMonitor`, `AdbBroadcastReceiver`) now cover all
  realistic framework-driven disable paths.

- **`SettingsHook` listener leak: `ContentObserver` and `BroadcastReceiver` were never
  unregistered.**  Added a `WifiTetherSettings.onStop` hook that unregisters the observer
  and receiver and cancels pending `Handler` callbacks.  Re-registration on subsequent
  `onStart` calls is now correct.

- **`SettingsHook` re-registration after cleanup was blocked by early return.**
  The previous code returned early from `injectWirelessDebuggingPref` when the preference
  was already present in the screen (resuming fragment), skipping listener re-registration
  entirely after the `onStop` cleanup.  The preference injection and listener registration
  are now separate; re-registration always runs unless listeners are already active.

### Changed

- `hookNetworkMonitorOrFallback` renamed to `hookNetworkMonitors`.  Now installs hooks
  on **both** `AdbWifiNetworkMonitor` (NetworkCallback) and `AdbBroadcastReceiver`
  independently, because `AdbDebuggingManager` selects between them at runtime via
  `allowAdbWifiReconnect()` and both may be compiled into the image.
- `AdbWifiNetworkMonitor.onLost` and `onCapabilitiesChanged` receive `deoptimize()` calls
  to prevent JIT from bypassing the hooks in system_server.
- `getContextFromMonitor()` helper added for extracting `Context` from
  `AdbWifiNetworkMonitor` instances (tries `mContext` field, then an `AdbDebuggingManager`
  field, then `this$0`).
- Logging improved throughout: each hook now logs whether it was installed or skipped, which
  ADB monitor path was selected, and (at INFO level) when a framework-driven disable is
  suppressed vs. when a user-driven disable passes through.
- `HotspotHelper.getApInterfaceIp` now logs which interface and IP are selected (at INFO)
  and which are skipped as the station Wi-Fi IP (at DEBUG).

### Removed

- `hookSettingsGlobalFallback` / `Settings.Global.putInt` intercept — replaced by correct
  Android 16 `AdbWifiNetworkMonitor` and `AdbBroadcastReceiver` hooks.
- `getContextFromResolver` helper — was only used by the removed fallback.

## [2.0.0] - 2026-04-12

### Changed

- **Migrated to modern libxposed API 101** — replaced legacy `de.robv.android.xposed:api:82`
  with `io.github.libxposed:api:101.0.1`.  The module now requires an API 101-compatible
  framework (LSPosed Vector era or equivalent).
- **Entry point**: replaced `assets/xposed_init` + `IXposedHookLoadPackage` with
  `META-INF/xposed/java_init.list` + `XposedModule`.  Lifecycle now uses
  `onSystemServerStarting` (framework hooks) and `onPackageLoaded` (Settings hooks).
- **Manifest**: removed all legacy `xposedmodule`, `xposeddescription`, `xposedminversion`,
  `xposedscope` metadata.  Module name and description come from `android:label`/
  `android:description`; scope from `META-INF/xposed/scope.list`; API version from
  `META-INF/xposed/module.prop`.
- **Hook API**: all hooks migrated from `XC_MethodHook` to the modern interceptor chain model
  (`hook(method).intercept { chain -> ... }`).  `XposedHelpers` is no longer used.
- **SDK**: `compileSdk` and `targetSdk` raised from 35 to **36** (Android 16).

### Added

- Android 16 compatibility for `AdbConnectionInfo`:  tries top-level
  `com.android.server.adb.AdbConnectionInfo` before falling back to the Android 15 nested
  class `AdbDebuggingManager$AdbConnectionInfo`.
- `deoptimize()` call on `getCurrentWifiApInfo` to prevent JIT inlining bypassing the hook
  in system_server.
- `getContext()` helper now handles both inner-class and (potential) top-level
  `AdbDebuggingHandler` field layouts.

### Removed

- `assets/xposed_init` — replaced by `META-INF/xposed/java_init.list`.
- `res/values/arrays.xml` (`xposed_scope` array) — replaced by `META-INF/xposed/scope.list`.
- All imports of `de.robv.android.xposed.*`.

## [1.0.1] - 2026-04-10

### Fixed

- Fixed wrong IP shown on Wireless Debugging screen when hotspot is active.
- Fixed button label not updating on hotspot/Wi-Fi state changes.

## [1.0.0] - 2026-04-09

Initial release.

[2.1.0]: https://github.com/cbkii/hotspotadb/compare/2.0.0...2.1.0
[2.0.0]: https://github.com/cbkii/hotspotadb/compare/1.0.1...2.0.0
[1.0.1]: https://github.com/cbkii/hotspotadb/compare/1.0.0...1.0.1
[1.0.0]: https://github.com/cbkii/hotspotadb/releases/tag/1.0.0

