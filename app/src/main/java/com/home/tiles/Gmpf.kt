package com.home.tiles

import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * The one place that calls into XGIMI's platform library (com.xgimi.gmpf.api.* and the other
 * com.xgimi.* managers). They exist only on XGIMI firmware, so everything goes through reflection.
 *
 * A manager is named by its class ("DisplayManager" is com.xgimi.gmpf.api.DisplayManager; a name
 * with a dot is taken as is) and reached through its static getInstance(). Calls return a [Result]:
 * a failure is told apart from a void method (a success holding null), the real cause is unwrapped
 * from InvocationTargetException, and it is logged once here instead of at every call site.
 *
 * Arguments are matched to the method by count and type, and numbers are converted to what the
 * method declares (the firmware mixes byte, int and boolean parameters), so callers just pass an
 * Int where a byte is wanted. Overloads with the same count pick the best matching one.
 */
object Gmpf {
    private const val TAG = "Gmpf"
    private const val API = "com.xgimi.gmpf.api."

    private class Target(val cls: Class<*>, val getInstance: Method)

    private val targets = ConcurrentHashMap<String, Target>()
    private val absent = ConcurrentHashMap.newKeySet<String>()
    private val methodsByClass = ConcurrentHashMap<Class<*>, List<Method>>()

    private fun qualified(className: String) = if ('.' in className) className else API + className

    private fun target(className: String): Result<Target> {
        val name = qualified(className)
        targets[name]?.let { return Result.success(it) }
        if (name in absent) return Result.failure(ClassNotFoundException(name))
        return attempt {
            val cls = try {
                Class.forName(name)
            } catch (e: ClassNotFoundException) {
                // Not an XGIMI firmware: say so once, and don't look again.
                if (absent.add(name)) Log.w(TAG, "$name unavailable")
                throw e
            }
            Target(cls, cls.getMethod("getInstance")).also { targets[name] = it }
        }.onFailure { if (name !in absent) Log.w(TAG, "$name unavailable: $it") }
    }

    /** Whether the manager class exists on this device. */
    fun available(className: String): Boolean = target(className).isSuccess

    /** Calls [name] on the manager [className]. Success holds the return value (null for void). */
    fun call(className: String, name: String, vararg args: Any?): Result<Any?> {
        val t = target(className).getOrElse { return Result.failure(it) }
        // Fetched on every call, as XGIMI's own apps do: the service behind it may come and go.
        val instance = attempt { t.getInstance.invoke(null) ?: error("${t.cls.name}.getInstance() returned null") }
            .getOrElse {
                Log.w(TAG, "${t.cls.simpleName}.getInstance failed: $it")
                return Result.failure(it)
            }
        return run(t.cls, instance, name, args)
    }

    /** Calls [name] on an object obtained some other way (e.g. a manager built with a constructor). */
    fun invoke(receiver: Any, name: String, vararg args: Any?): Result<Any?> = run(receiver.javaClass, receiver, name, args)

    /** True if the call went through. */
    fun ok(className: String, name: String, vararg args: Any?): Boolean = call(className, name, *args).isSuccess

    /** A numeric result as an Int; null if the call failed or returned something else. */
    fun int(className: String, name: String, vararg args: Any?): Int? =
        (call(className, name, *args).getOrNull() as? Number)?.toInt()

    fun bool(className: String, name: String, vararg args: Any?): Boolean? =
        call(className, name, *args).getOrNull() as? Boolean

    private fun run(cls: Class<*>, receiver: Any, name: String, args: Array<out Any?>): Result<Any?> {
        val label = "${cls.simpleName}.$name"
        val methods = methodsByClass.getOrPut(cls) { cls.methods.toList() }
        val candidates = methods.filter { it.name == name && it.parameterTypes.size == args.size }
        if (candidates.isEmpty()) {
            return Result.failure<Any?>(NoSuchMethodException("$label/${args.size}")).also { Log.w(TAG, "no method $label/${args.size}") }
        }
        var best: Method? = null
        var bestArgs: Array<Any?> = emptyArray()
        var bestScore = -1
        for (method in candidates) {
            val converted = arrayOfNulls<Any?>(args.size)
            var score = 0
            var matches = true
            for (i in args.indices) {
                val type = method.parameterTypes[i]
                val value = coerce(type, args[i])
                if (value === INCOMPATIBLE) {
                    matches = false
                    break
                }
                converted[i] = value
                // An argument that needed no conversion beats one that did.
                if (args[i] != null && args[i]!!.javaClass == boxed(type)) score++
            }
            if (matches && score > bestScore) {
                best = method
                bestArgs = converted
                bestScore = score
            }
        }
        val method = best ?: return Result.failure<Any?>(NoSuchMethodException("$label: no overload fits ${args.toList()}"))
            .also { Log.w(TAG, "no overload of $label fits ${args.toList()}") }
        return attempt { method.invoke(receiver, *bestArgs) }.onFailure { Log.w(TAG, "$label failed: $it") }
    }

    private fun <T> attempt(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: InvocationTargetException) {
        Result.failure(e.cause ?: e)
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private val INCOMPATIBLE = Any()

    private val boxes: Map<Class<*>, Class<*>> = mapOf(
        Byte::class.javaPrimitiveType!! to Byte::class.javaObjectType,
        Short::class.javaPrimitiveType!! to Short::class.javaObjectType,
        Int::class.javaPrimitiveType!! to Int::class.javaObjectType,
        Long::class.javaPrimitiveType!! to Long::class.javaObjectType,
        Float::class.javaPrimitiveType!! to Float::class.javaObjectType,
        Double::class.javaPrimitiveType!! to Double::class.javaObjectType,
        Boolean::class.javaPrimitiveType!! to Boolean::class.javaObjectType,
    )

    private fun boxed(type: Class<*>): Class<*> = boxes[type] ?: type

    /** [arg] as the parameter [type] wants it, or [INCOMPATIBLE]. */
    private fun coerce(type: Class<*>, arg: Any?): Any? {
        if (arg == null) return if (type.isPrimitive) INCOMPATIBLE else null
        return when (boxed(type)) {
            Byte::class.javaObjectType -> if (arg is Number) arg.toByte() else INCOMPATIBLE
            Short::class.javaObjectType -> if (arg is Number) arg.toShort() else INCOMPATIBLE
            Int::class.javaObjectType -> if (arg is Number) arg.toInt() else INCOMPATIBLE
            Long::class.javaObjectType -> if (arg is Number) arg.toLong() else INCOMPATIBLE
            Float::class.javaObjectType -> if (arg is Number) arg.toFloat() else INCOMPATIBLE
            Double::class.javaObjectType -> if (arg is Number) arg.toDouble() else INCOMPATIBLE
            Boolean::class.javaObjectType -> when (arg) {
                is Boolean -> arg
                is Number -> arg.toInt() != 0
                else -> INCOMPATIBLE
            }
            else -> if (type.isInstance(arg)) arg else INCOMPATIBLE
        }
    }
}
