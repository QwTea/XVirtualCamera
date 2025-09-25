package com.sandyz.virtualcam.utils

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.sandyz.virtualcam.hooks.IHook
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import java.lang.ref.WeakReference
import kotlin.coroutines.CoroutineContext


/**
 *@author sandyz987
 *@date 2023/11/18
 *@description
 */

@SuppressLint("StaticFieldLeak")
object HookUtils {
    var app: Context? = null

    // 获取当前Activity用
    private val activityTop = mutableListOf<WeakReference<Activity>>()
    fun getActivities(): List<Activity> {
        xLog("[HookUtils.getActivities] start, cachedSize=${activityTop.size}")
        val activities = mutableListOf<Activity>()
        val iterator = activityTop.iterator()
        while (iterator.hasNext()) {
            val activity = iterator.next().get()
            if (activity != null && !activity.isFinishing) {
                activities.add(activity)
                xLog("[HookUtils.getActivities] retained activity=${activity::class.java.name}@${System.identityHashCode(activity)}")
            } else {
                xLog("[HookUtils.getActivities] removing leaked/finished activityRef=$activity")
                iterator.remove()
            }
        }
        xLog("[HookUtils.getActivities] resultSize=${activities.size}")
        return activities
    }

    fun getTopActivity(): Activity? {
        xLog("[HookUtils.getTopActivity] resolving top activity")
        val activities = getActivities()
        val activity = if (activities.isEmpty()) {
            null
        } else {
            activities[0]
        }
        xLog("[HookUtils.getTopActivity] top=${activity?.javaClass?.name}@${activity?.let { System.identityHashCode(it) }}")
        return activity
    }

    fun getLifecycle(): Lifecycle? {
        // 反射获取lifecycle提高成功率
        xLog("[HookUtils.getLifecycle] attempting to obtain Lifecycle instance from top activity")
        val activity = getTopActivity()
        mutableListOf(
            "androidx.lifecycle.LifecycleOwner",
            "android.arch.lifecycle.LifecycleOwner",
            "android.support.v4.app.FragmentActivity",
            "android.support.v4.app.SupportActivity",
            "androidx.fragment.app.FragmentActivity",
            "androidx.appcompat.app.AppCompatActivity",
            "androidx.activity.ComponentActivity",
            "androidx.core.app.ComponentActivity",
        ).forEach {
            try {
                xLog("[HookUtils.getLifecycle] probing lifecycle interface=$it")
                val clazz = try {
                    XposedHelpers.findClass(it, activity?.classLoader)
                } catch (t: Throwable) {
                    xLog("[HookUtils.getLifecycle] class not found via activity loader, fallback to system for $it error=$t")
                    Class.forName(it)
                }
                val activityCast = clazz?.cast(activity)
                val function = clazz?.getDeclaredMethod("getLifecycle")
                function?.isAccessible = true
                val lifecycle = function?.invoke(activityCast) as? Lifecycle
                if (lifecycle != null) {
                    xLog("[HookUtils.getLifecycle] obtained lifecycle=$lifecycle from class=$it")
                    return lifecycle
                } else {
                    xLog("lifecycle is null")
                }
            } catch (t: Throwable) {
                xLog(t.toString())
            }
        }
        return null
    }


    private val coroutineScopeMap = HashMap<Activity, CoroutineScope>()

    fun coroutineScope(): CoroutineScope = if (coroutineScopeMap[getTopActivity()] != null) {
        xLog("[HookUtils.coroutineScope] reusing existing scope for activity=${getTopActivity()}")
        coroutineScopeMap[getTopActivity()]!!
    } else {
        xLog("[HookUtils.coroutineScope] creating new scope for activity=${getTopActivity()}")
        MyCoroutineScope().also {
            xLog("activity: ${getTopActivity()}")
            xLog("lifecycle2: ${getLifecycle()}")
            val activity = getTopActivity()?: return@also
            val activityLifecycle = getLifecycle()?: return@also
            val lifecycleEventObserver = object :LifecycleEventObserver {
                override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                    xLog("[HookUtils.coroutineScope] lifecycle event=$event for activity=$source")
                    if (event == Lifecycle.Event.ON_DESTROY) {
                        xLog("[HookUtils.coroutineScope] cancelling scope because activity destroyed")
                        it.cancel()
                        activityLifecycle.removeObserver(this)
                        coroutineScopeMap.remove(activity)
                    }
                }
            }
            xLog("[HookUtils.coroutineScope] registering lifecycle observer for activity=$activity")
            activityLifecycle.addObserver(lifecycleEventObserver)
            xLog("[HookUtils.coroutineScope] caching scope=$it for activity=$activity")
            coroutineScopeMap[activity] = it
        }
    }

    fun getView(): View? {
        val view = getTopActivity()?.window?.decorView
        xLog("[HookUtils.getView] decorView=$view")
        return view
    }

    fun getContentView(): ViewGroup? {
        val content = getView()?.findViewById(android.R.id.content) as? ViewGroup
        xLog("[HookUtils.getContentView] contentView=$content childCount=${content?.childCount}")
        return content
    }

    fun dumpView(v: View?, depth: Int) {
        v ?: return
        xLog("[HookUtils.dumpView] depth=$depth view=${v.javaClass.name}@${System.identityHashCode(v)}")
        xLog("${"  ".repeat(depth)}${v.javaClass.name}")
        if (v is ViewGroup) {
            v.children.forEach {
                dumpView(it, depth + 1)
            }
        }
    }

    fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        xLog("[HookUtils.init] preparing instrumentation hooks for package=${lpparam.packageName}")
        val instrumentation = XposedHelpers.findClass(
            "android.app.Instrumentation", lpparam.classLoader
        )
        XposedBridge.hookAllMethods(instrumentation, "callApplicationOnCreate", object : XC_MethodHook() {
            @Throws(Throwable::class)
            override fun afterHookedMethod(param: MethodHookParam) {
                xLog("[HookUtils.init] callApplicationOnCreate afterHooked, captured application=${param.args.getOrNull(0)}")
                app = param.args[0] as Context
            }
        })

        xLog("[HookUtils.init] installing Activity constructors hook")
        val activity = XposedHelpers.findClass(
            "android.app.Activity", lpparam.classLoader
        )
        XposedBridge.hookAllConstructors(activity, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                xLog("[HookUtils.init] Activity constructed instance=${param.thisObject}")
                if (!getActivities().contains(param.thisObject)) {
                    activityTop.add(0, WeakReference(param.thisObject as Activity))
                    xLog("[HookUtils.init] tracked activity list size=${activityTop.size}")
                }
            }
        })
    }

}

fun IHook.xLog(msg: String?) {
    XposedBridge.log("[${this::class.java.simpleName} ${Thread.currentThread().id}] $msg")
}

fun xLog(msg: String?) {
    XposedBridge.log("[${Thread.currentThread().id}] $msg")
}

fun xLog(param: XC_MethodHook.MethodHookParam?, msg: String?, depth: Int = 15) {
    xLog(msg)
    if (param == null) {
        return
    }
    val stackTrace = Thread.currentThread().stackTrace as Array<StackTraceElement>
    stackTrace.forEachIndexed { index, stackTraceElement ->
        if (stackTraceElement.className.equals("LSPHooker_")) {
            for (i in index + 1..index + depth) {
                if (i < stackTrace.size) {
                    xLog("          ${stackTrace[i].className}.${stackTrace[i].methodName}")
                }
            }
        }
    }
}

fun xLogTrace(param: XC_MethodHook.MethodHookParam?, msg: String?) {
    if (param == null) {
        xLog(msg)
        return
    }
    xLog(msg)
    val stackTrace = Thread.currentThread().stackTrace as Array<StackTraceElement>
    stackTrace.forEach {
        xLog("          ${it.className}.${it.methodName}")

    }
}

fun toast(context: Context?, text: CharSequence, duration: Int) {
    try {
        xLog("[HookUtils.toast] showing toast text=$text duration=$duration context=$context")
        context?.let {
            Toast.makeText(it, text, duration).show()
        }
    } catch (e: Throwable) {
        xLog("toast: $text")
    }
}

class MyCoroutineScope: CoroutineScope {
    private val job = Job()
    override val coroutineContext: CoroutineContext = Dispatchers.IO +
            job +
            CoroutineName("MyCoroutineScope") +
            CoroutineExceptionHandler{ coroutineContext, throwable ->
                xLog("[HookUtils.MyCoroutineScope] coroutineException in $coroutineContext: $throwable")
            }
}
