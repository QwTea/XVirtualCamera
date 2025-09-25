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
                return
            }
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
            libs.forEach {
                try {
                    System.load(it)
                    xLog("loadNative success $it")
                } catch (_: Throwable) {
                }
            }
        }
    }

    private val hooks = listOf(
        VirtualCameraUniversal(),
    )


    override fun initZygote(startupParam: StartupParam) {
        modulePath = startupParam.modulePath.substring(0, startupParam.modulePath.lastIndexOf('/'))
        moduleRes = startupParam.modulePath
    }

    override fun handleInitPackageResources(resparam: XC_InitPackageResources.InitPackageResourcesParam?) {
        xResources = resparam?.res
        if (moduleRes == null || resparam?.res == null) {
            return
        }
        val modRes = XModuleResources.createInstance(moduleRes, resparam.res)
        hooks.forEach {
            it.registerRes(modRes)
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        HookUtils.init(lpparam)

        if (lpparam.packageName == null || lpparam.packageName == MODULE_PACKAGE) {
            xLog("skip init for package: ${lpparam.packageName} process: ${lpparam.processName}")
            return
        }

        hooks.forEach {
            xLog("init>>>>${it.getName()}>>>> package: ${lpparam.packageName} process: ${lpparam.processName}")
            loadNative()
            XposedHelpers.findAndHookMethod(
                Instrumentation::class.java,
                "callApplicationOnCreate",
                Application::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        init(it, lpparam)
                    }
                }
            )
        }
    }


    fun init(hook: IHook, lpparam: XC_LoadPackage.LoadPackageParam?) {
        hook.init(lpparam?.classLoader)
        hook.hook(lpparam)
    }


}