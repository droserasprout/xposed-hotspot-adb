package io.drsr.hotspotadb

import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * Minimal reflection helpers replacing the XposedHelpers utilities that the modern
 * libxposed API no longer bundles. Scoped to exactly what this module needs.
 *
 * Semantics deliberately mirror the legacy helpers we replaced:
 *   - [findMethod] resolves against the *exact* class only (like findMethodExact /
 *     findAndHookMethod). This matters where callers rely on NoSuchMethodException to
 *     fall back to another method name (e.g. onDestroyView -> onStop on Android 16).
 *   - [call] and [getField] walk up the hierarchy (like callMethod / getObjectField).
 */
object Reflect {
    /** Exact-class method lookup for hooking. Throws if not declared on [cls] itself. */
    fun findMethod(
        cls: Class<*>,
        name: String,
        vararg paramTypes: Class<*>,
    ): Method = cls.getDeclaredMethod(name, *paramTypes).apply { isAccessible = true }

    /** All methods named [name] declared directly on [cls] (like hookAllMethods). */
    fun methodsNamed(
        cls: Class<*>,
        name: String,
    ): List<Method> = cls.declaredMethods.filter { it.name == name }.onEach { it.isAccessible = true }

    /** Invoke [name] on [receiver], resolving the overload by runtime argument types. */
    fun call(
        receiver: Any,
        name: String,
        vararg args: Any?,
    ): Any? {
        val method =
            resolveMethod(receiver.javaClass, name, args)
                ?: throw NoSuchMethodException("$name/${args.size} on ${receiver.javaClass.name}")
        return method.invoke(receiver, *args)
    }

    /** Read instance field [name], searching up the hierarchy. */
    fun getField(
        receiver: Any,
        name: String,
    ): Any? {
        var cls: Class<*>? = receiver.javaClass
        while (cls != null) {
            try {
                return cls.getDeclaredField(name).apply { isAccessible = true }.get(receiver)
            } catch (_: NoSuchFieldException) {
                cls = cls.superclass
            }
        }
        throw NoSuchFieldException("$name on ${receiver.javaClass.name}")
    }

    /** Construct [cls], resolving the constructor by runtime argument types. */
    fun newInstance(
        cls: Class<*>,
        vararg args: Any?,
    ): Any {
        val ctor =
            resolveConstructor(cls, args)
                ?: throw NoSuchMethodException("<init>/${args.size} on ${cls.name}")
        return ctor.newInstance(*args)
    }

    private fun resolveMethod(
        start: Class<*>,
        name: String,
        args: Array<out Any?>,
    ): Method? {
        var cls: Class<*>? = start
        while (cls != null) {
            for (method in cls.declaredMethods) {
                if (method.name == name &&
                    method.parameterCount == args.size &&
                    argsMatch(method.parameterTypes, args)
                ) {
                    return method.apply { isAccessible = true }
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun resolveConstructor(
        cls: Class<*>,
        args: Array<out Any?>,
    ): Constructor<*>? {
        for (ctor in cls.declaredConstructors) {
            if (ctor.parameterCount == args.size && argsMatch(ctor.parameterTypes, args)) {
                return ctor.apply { isAccessible = true }
            }
        }
        return null
    }

    private fun argsMatch(
        types: Array<Class<*>>,
        args: Array<out Any?>,
    ): Boolean {
        for (i in types.indices) {
            val arg = args[i] ?: continue // null is assignable to any reference type
            if (!boxed(types[i]).isInstance(arg)) return false
        }
        return true
    }

    private fun boxed(type: Class<*>): Class<*> =
        when (type) {
            java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
            java.lang.Integer.TYPE -> java.lang.Integer::class.java
            java.lang.Long.TYPE -> java.lang.Long::class.java
            java.lang.Short.TYPE -> java.lang.Short::class.java
            java.lang.Byte.TYPE -> java.lang.Byte::class.java
            java.lang.Character.TYPE -> java.lang.Character::class.java
            java.lang.Float.TYPE -> java.lang.Float::class.java
            java.lang.Double.TYPE -> java.lang.Double::class.java
            else -> type
        }

    // "Additional instance fields" - scratch storage keyed by instance identity, as the
    // legacy XposedHelpers.{get,set,remove}AdditionalInstanceField provided.
    private val instanceFields = WeakHashMap<Any, MutableMap<String, Any?>>()

    fun setInstanceField(
        receiver: Any,
        key: String,
        value: Any?,
    ) = synchronized(instanceFields) {
        instanceFields.getOrPut(receiver) { HashMap() }[key] = value
    }

    fun getInstanceField(
        receiver: Any,
        key: String,
    ): Any? =
        synchronized(instanceFields) {
            instanceFields[receiver]?.get(key)
        }

    fun removeInstanceField(
        receiver: Any,
        key: String,
    ) = synchronized(instanceFields) {
        instanceFields[receiver]?.remove(key)
    }
}
