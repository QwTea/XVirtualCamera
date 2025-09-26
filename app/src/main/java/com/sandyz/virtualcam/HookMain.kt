package com.sandyz.virtualcam

import android.annotation.SuppressLint
import android.app.Application
import android.app.Instrumentation
import android.content.res.XModuleResources
import android.content.res.XResources
import com.sandyz.virtualcam.hooks.IHook
import com.sandyz.virtualcam.hooks.VirtualCameraUniversal
import com.sandyz.virtualcam.utils.HookUtils
import com.sandyz.virtualcam.utils.xLog
import de.robv.android.xposed.IXposedHookInitPackageResources
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.IXposedHookZygoteInit.StartupParam
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_InitPackageResources
import de.robv.android.xposed.callbacks.XC_LoadPackage


/**
 *@author sandyz987
 *@date 2023/11/17
 *@description
 */

class HookMain : IXposedHookLoadPackage, IXposedHookZygoteInit, IXposedHookInitPackageResources {

    companion object {
        private const val MODULE_PACKAGE = "com.sandyz.virtualcam"
        public var modulePath: String? = null
        private var moduleRes: String? = null
        var xResources: XResources? = null
        private var nativeLoaded = false

        @SuppressLint("UnsafeDynamicallyLoadedCode")
        fun loadNative() {
            if (nativeLoaded) {
                xLog("[HookMain.loadNative] native libraries already loaded, skip on thread=${Thread.currentThread().name}")
                return
            }
            xLog("[HookMain.loadNative] starting to load native libraries from modulePath=$modulePath")
            nativeLoaded = true
            val libs = arrayOf(
                "$modulePath/lib/arm64-v8a/libijkffmpeg.so",
                "$modulePath/lib/arm64-v8a/libijksdl.so",
                "$modulePath/lib/arm64-v8a/libijkplayer.so",
                "$modulePath/lib/arm64-v8a/libencoder.so",

                "$modulePath/lib/armeabi-v7a/libijkffmpeg.so",
                "$modulePath/lib/armeabi-v7a/libijksdl.so",
                "$modulePath/lib/armeabi-v7a/libijkplayer.so",
                "$modulePath/lib/armeabi-v7a/libencoder.so",

                "$modulePath/lib/arm64/libijkffmpeg.so",
                "$modulePath/lib/arm64/libijksdl.so",
                "$modulePath/lib/arm64/libijkplayer.so",
                "$modulePath/lib/arm64/libencoder.so",

                "$modulePath/lib/x86/libijkffmpeg.so",
                "$modulePath/lib/x86/libijksdl.so",
                "$modulePath/lib/x86/libijkplayer.so",
                "$modulePath/lib/x86/libencoder.so",

                "$modulePath/lib/x86_64/libijkffmpeg.so",
                "$modulePath/lib/x86_64/libijksdl.so",
                "$modulePath/lib/x86_64/libijkplayer.so",
                "$modulePath/lib/x86_64/libencoder.so",

            )
            xLog("[HookMain.loadNative] prepared ${libs.size} library paths for loading")
            libs.forEach {
                try {
                    System.load(it)
                    xLog("loadNative success $it")
                } catch (throwable: Throwable) {
                    xLog("[HookMain.loadNative] failed to load $it\n${throwable.stackTraceToString()}")
                }
            }
            xLog("[HookMain.loadNative] finished loading native libraries")
        }
    }

    private val hooks = listOf(
        VirtualCameraUniversal(),
    )


    override fun initZygote(startupParam: StartupParam) {
        xLog("[HookMain.initZygote] startupParam=${startupParam.modulePackageName} modulePath=${startupParam.modulePath}")
        modulePath = startupParam.modulePath.substring(0, startupParam.modulePath.lastIndexOf('/'))
        moduleRes = startupParam.modulePath
        xLog("[HookMain.initZygote] resolved modulePath=$modulePath moduleRes=$moduleRes")
    }

    override fun handleInitPackageResources(resparam: XC_InitPackageResources.InitPackageResourcesParam?) {
        xLog("[HookMain.handleInitPackageResources] called for package=${resparam?.packageName} res=${resparam?.res}")
        xResources = resparam?.res
        if (moduleRes == null || resparam?.res == null) {
            xLog("[HookMain.handleInitPackageResources] moduleRes or res is null, skip registering resources")
            return
        }
        val modRes = XModuleResources.createInstance(moduleRes, resparam.res)
        xLog("[HookMain.handleInitPackageResources] created module resources, dispatching to hooks=${hooks.size}")
        hooks.forEach {
            xLog("[HookMain.handleInitPackageResources] registering resources for hook=${it.getName()}")
            it.registerRes(modRes)
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        xLog("[HookMain.handleLoadPackage] incoming package=${lpparam.packageName} process=${lpparam.processName} appInfo=${lpparam.appInfo}")
        // 1) Сразу выходим для собственного APK модуля
        if (lpparam.packageName == null || lpparam.packageName == MODULE_PACKAGE) {
            xLog("skip init for package: ${lpparam.packageName} process: ${lpparam.processName}")
            return
        }

        // 2) Общая инициализация — только в целевых процессах
        HookUtils.init(lpparam)

        hooks.forEach {
            xLog("init>>>>${it.getName()}>>>> package: ${lpparam.packageName} process: ${lpparam.processName}")
            loadNative()
            xLog("[HookMain.handleLoadPackage] installing Instrumentation hook for ${it.getName()}")
            XposedHelpers.findAndHookMethod(
                Instrumentation::class.java,
                "callApplicationOnCreate",
                Application::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        xLog("[HookMain.handleLoadPackage] Instrumentation.callApplicationOnCreate afterHooked for ${lpparam.packageName}")
                        init(it, lpparam)
                    }
                }
            )
        }
    }


    fun init(hook: IHook, lpparam: XC_LoadPackage.LoadPackageParam?) {
        xLog("[HookMain.init] preparing hook=${hook.getName()} with classLoader=${lpparam?.classLoader}")
        hook.init(lpparam?.classLoader)
        xLog("[HookMain.init] invoking hook.hook for ${hook.getName()} on package=${lpparam?.packageName}")
        hook.hook(lpparam)
        xLog("[HookMain.init] completed hook initialisation for ${hook.getName()}")
    }


}
