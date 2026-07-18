package io.drsr.hotspotadb

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Modern libxposed entry point. Registered via META-INF/xposed/java_init.list; the
 * framework instantiates it once per hooked process and attaches itself before any
 * callback fires.
 *
 * The Settings hooks need the app classloader, so they run in [onPackageReady] (fired
 * once the target app is fully loaded). The framework hooks live in system_server and
 * run in [onSystemServerStarting].
 */
class HotspotAdbModule : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Xp.attach(this)
        Xp.log("HotspotAdb: onModuleLoaded ${param.processName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != "com.android.settings") return
        Xp.log("HotspotAdb: hooking Settings")
        SettingsHook.init(param.classLoader)
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        Xp.log("HotspotAdb: hooking framework")
        FrameworkHook.init(param.classLoader)
    }
}
