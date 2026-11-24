package dev.trinhhg.vapp.engine.js

import dev.trinhhg.vapp.engine.log.EngineLogger
import dev.trinhhg.vapp.engine.security.SecurityConstants
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.Undefined

/**
 * Hardened Rhino ContextFactory enabling Java Map dot access and enforcing ClassShutter sandbox.
 */
object SandboxContextFactory : ContextFactory() {
    private val shutter =
        ClassShutter { className ->
            if (className.startsWith("dev.trinhhg.vapp.engine.js.api.") ||
                className.startsWith("dev.trinhhg.vapp.engine.js.loader.")
            ) {
                true
            } else {
                SecurityConstants.isClassAllowed(className)
            }
        }

    override fun hasFeature(
        cx: Context,
        featureIndex: Int,
    ): Boolean = featureIndex == Context.FEATURE_ENABLE_JAVA_MAP_ACCESS || super.hasFeature(cx, featureIndex)

    override fun onContextCreated(cx: Context) {
        super.onContextCreated(cx)
        cx.optimizationLevel = -1
        cx.languageVersion = Context.VERSION_ES6
        cx.setMaximumInterpreterStackDepth(1000)
        cx.setClassShutter(shutter)
        cx.wrapFactory.isJavaPrimitiveWrap = false
    }
}

/**
 * Bidirectional value conversion between Rhino JS objects and Kotlin structures.
 */
object JsValueBridge {
    fun arrayOf(items: List<Any?>): Any {
        val current = Context.getCurrentContext()
        val ctx = current ?: SandboxContextFactory.enterContext()
        return try {
            val scope = ctx.initStandardObjects()
            ctx.newArray(scope, items.toTypedArray())
        } finally {
            if (current == null) Context.exit()
        }
    }

    fun objectOf(pairs: List<Pair<String, Any?>>): Any {
        val current = Context.getCurrentContext()
        val ctx = current ?: SandboxContextFactory.enterContext()
        return try {
            val obj = ctx.newObject(ctx.initStandardObjects())
            for ((k, v) in pairs) obj.put(k, obj, v)
            obj
        } finally {
            if (current == null) Context.exit()
        }
    }

    fun parseJson(json: String): Any? {
        val current = Context.getCurrentContext()
        val ctx = current ?: SandboxContextFactory.enterContext()
        return try {
            val scope = ctx.initStandardObjects()
            val reviver = org.mozilla.javascript.Callable { _, _, _, args -> args[1] }
            NativeJSON.parse(ctx, scope, json, reviver)
        } finally {
            if (current == null) Context.exit()
        }
    }

    fun isFunction(value: Any?): Boolean = value is Function

    fun call(
        fn: Any?,
        args: List<Any?>,
    ): Any? {
        if (fn !is Function) return null
        val ctx = Context.getCurrentContext() ?: return null
        val scope = fn.parentScope ?: fn
        return fn.call(ctx, scope, scope, args.toTypedArray())
    }

    fun normalize(value: Any?): Any? =
        when {
            value == null || value === Undefined.instance -> null
            value is NativeObject -> {
                val map = LinkedHashMap<String, Any?>()
                for (id in value.ids) {
                    val key = if (id is Number) id.toInt().toString() else id.toString()
                    map[key] = normalize(value[key, value])
                }
                map
            }
            value is NativeArray -> {
                val list = ArrayList<Any?>(value.length.toInt())
                for (i in 0 until value.length.toInt()) {
                    list.add(normalize(value[i, value]))
                }
                list
            }
            value is NativeJavaObject -> {
                val unwrapped = value.unwrap()
                if (unwrapped is Scriptable) normalize(unwrapped) else unwrapped
            }
            value is Function -> null
            value is Scriptable -> null
            else -> value
        }

    fun toJsString(value: Any?): String? {
        if (value == null || value === Undefined.instance) return null
        return runCatching {
            if (Context.getCurrentContext() != null) Context.toString(value) else value.toString()
        }.getOrNull()
    }
}

/**
 * Isolated Rhino execution sandbox for a single VBook script invocation.
 */
class RhinoSandbox(
    private val log: EngineLogger,
    configureScope: (Context, Scriptable) -> Unit,
) : AutoCloseable {
    val context: Context = SandboxContextFactory.enterContext()
    val scope: Scriptable = context.initStandardObjects()

    init {
        configureScope(context, scope)
        installSafeToStringWrapper()
    }

    fun evaluate(
        script: String,
        scriptName: String,
    ): Result<Unit> =
        runCatching {
            context.evaluateString(scope, script, scriptName, 1, null)
            Unit
        }.onFailure { err ->
            log.error("JS evaluation error in $scriptName", err, tag = "Sandbox")
        }

    fun callFunction(
        functionName: String,
        vararg args: Any?,
    ): Result<Any?> =
        runCatching {
            val fn = scope[functionName, scope]
            if (fn !is Function) {
                error("Function '$functionName' not found in script")
            }
            JsValueBridge.normalize(fn.call(context, scope, scope, args))
        }.onFailure { err ->
            log.error("JS function '$functionName' failed", err, tag = "Sandbox")
        }

    override fun close() {
        runCatching { Context.exit() }
    }

    private fun installSafeToStringWrapper() {
        val wrapper =
            """
            (function() {
                const origFnToStr = Function.prototype.toString;
                Function.prototype.toString = function() {
                    try { return origFnToStr.call(this); } catch (e) { return 'function () { [native code] }'; }
                };
                const origObjToStr = Object.prototype.toString;
                Object.prototype.toString = function() {
                    try { return origObjToStr.call(this); } catch (e) { return '[object Object]'; }
                };
            })();
            """.trimIndent()
        runCatching { context.evaluateString(scope, wrapper, "safeToString", 1, null) }
    }
}
