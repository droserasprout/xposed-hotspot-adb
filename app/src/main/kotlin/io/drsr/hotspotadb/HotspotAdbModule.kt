package io.drsr.hotspotadb

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Modern libxposed API 101 entry point.
 *
 * Lifecycle:
 *  - onModuleLoaded    : called once per process load; log framework info.
 *  - onSystemServerStarting : called in system_server; install framework (ADB) hooks.
 *  - onPackageLoaded   : called when an app package's classloader is ready;
 *                        install Settings UI hooks for com.android.settings.
 */
class HotspotAdbModule : XposedModule() {
    companion object {
        const val TAG = "HotspotAdb"
    }

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "module loaded in ${param.processName}")
        log(Log.INFO, TAG, "framework: $frameworkName $frameworkVersion (API $apiVersion)")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        log(Log.INFO, TAG, "onSystemServerStarting — installing framework (ADB) hooks")
        FrameworkHook.install(param.classLoader, this)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName == "com.android.settings") {
            log(Log.INFO, TAG, "onPackageLoaded com.android.settings — installing Settings hooks")
            SettingsHook.install(param.defaultClassLoader, this)
        }
    }
}
