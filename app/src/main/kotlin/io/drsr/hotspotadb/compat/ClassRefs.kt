package io.drsr.hotspotadb.compat

import android.content.ContentResolver
import android.content.Context

object ClassRefs {
    fun tryFindClass(
        loader: ClassLoader,
        vararg names: String,
    ): Class<*>? {
        for (name in names) {
            try {
                return Class.forName(name, false, loader)
            } catch (_: ClassNotFoundException) {
                continue
            }
        }
        return null
    }

    fun contextFromResolver(resolver: ContentResolver): Context? {
        return try {
            resolver.javaClass.getMethod("getContext").invoke(resolver) as? Context
        } catch (_: NoSuchMethodException) {
            mContextField(resolver)
        } catch (_: Throwable) {
            mContextField(resolver)
        }
    }

    private fun mContextField(resolver: ContentResolver): Context? {
        return try {
            val field = ContentResolver::class.java.getDeclaredField("mContext")
            field.isAccessible = true
            field.get(resolver) as? Context
        } catch (_: Throwable) {
            null
        }
    }
}
