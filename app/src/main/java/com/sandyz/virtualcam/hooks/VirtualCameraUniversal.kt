package com.sandyz.virtualcam.hooks

import android.content.res.XModuleResources
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.hardware.Camera.PreviewCallback
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.InputConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import com.sandyz.virtualcam.jni.EncoderJNI
import com.sandyz.virtualcam.utils.HookUtils
import com.sandyz.virtualcam.utils.PlayIjk
import com.sandyz.virtualcam.utils.xLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class VirtualCameraUniversal : IHook {

    override fun getName(): String = "通用虚拟摄像头模块"

    private val camera2Pipeline = Camera2Pipeline()
    private val camera1Pipeline = Camera1Pipeline()

    override fun init(cl: ClassLoader?) {
    }

    override fun registerRes(moduleRes: XModuleResources?) {
    }

    override fun hook(lpparam: LoadPackageParam?) {
        lpparam ?: return
        camera2Pipeline.installHooks(lpparam)
        camera1Pipeline.installHooks(lpparam)
    }

    private inner class Camera2Pipeline {

        private val hooksInstalled = AtomicBoolean(false)
        private var nullSurface: Surface? = null
        private var nullSurfaceTex: SurfaceTexture? = null
        private var virtualSurface: Surface? = null
        private var ijkMediaPlayer: IjkMediaPlayer? = null
        private var sessionStateCallbackClazz: Class<*>? = null
        private val intercepting = AtomicBoolean(false)

        fun installHooks(lpparam: LoadPackageParam) {
            if (!hooksInstalled.compareAndSet(false, true)) {
                return
            }
            val classLoader = lpparam.classLoader ?: return
            hookImageReader(classLoader)
            hookCreateCaptureSessionApi28(classLoader, lpparam)
            hookCreateCaptureSessionLegacy(classLoader)
            hookOppoCreateCaptureSession(classLoader)
            hookAddTarget(classLoader)
            hookTextureViewConstructors(classLoader)
        }

        private fun hookImageReader(classLoader: ClassLoader) {
            try {
                XposedHelpers.findAndHookMethod(
                    "android.media.ImageReader",
                    classLoader,
                    "newInstance",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            xLog(
                                "ImageReader.newInstance width:${param.args[0]} height:${param.args[1]} format:${param.args[2]}"
                            )
                        }
                    }
                )
            } catch (_: Throwable) {
            }
        }

        private fun hookCreateCaptureSessionApi28(classLoader: ClassLoader, lpparam: LoadPackageParam) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                return
            }
            try {
                XposedHelpers.findAndHookMethod(
                    "android.hardware.camera2.impl.CameraDeviceImpl",
                    classLoader,
                    "createCaptureSession",
                    SessionConfiguration::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val sessionConfiguration = param.args[0] as SessionConfiguration
                            resetSurface()
                            intercepting.set(true)
                            val surfaces = mutableListOf<Surface?>()
                            sessionConfiguration.outputConfigurations.forEach {
                                surfaces.add(it.surface)
                            }
                            xLog(
                                "${lpparam.packageName} createCaptureSession surfaces:$surfaces redirect:$nullSurface"
                            )
                            val outputConfiguration = nullSurface?.let { OutputConfiguration(it) }
                            if (outputConfiguration != null) {
                                val redirectedConfiguration = SessionConfiguration(
                                    sessionConfiguration.sessionType,
                                    listOf(outputConfiguration),
                                    sessionConfiguration.executor,
                                    sessionConfiguration.stateCallback
                                )
                                param.args[0] = redirectedConfiguration
                            }
                            hookSessionStateCallback(sessionConfiguration.stateCallback.javaClass)
                        }
                    }
                )
            } catch (_: Throwable) {
            }
        }

        private fun hookCreateCaptureSessionLegacy(classLoader: ClassLoader) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                return
            }
            try {
                XposedHelpers.findAndHookMethod(
                    "android.hardware.camera2.impl.CameraDeviceImpl",
                    classLoader,
                    "createCaptureSession",
                    java.util.List::class.java,
                    CameraCaptureSession.StateCallback::class.java,
                    Handler::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            @Suppress("UNCHECKED_CAST")
                            val surfaces = param.args[0] as? List<Surface>
                            if (surfaces.isNullOrEmpty()) {
                                return
                            }
                            resetSurface()
                            intercepting.set(true)
                            xLog("camera2 legacy createCaptureSession surfaces:$surfaces redirect:$nullSurface")
                            param.args[0] = listOfNotNull(nullSurface)
                        }
                    }
                )
            } catch (_: Throwable) {
            }
        }

        private fun hookOppoCreateCaptureSession(classLoader: ClassLoader) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return
            }
            try {
                XposedHelpers.findAndHookMethod(
                    "com.color.compat.hardware.camera2.CameraDevicesNative",
                    classLoader,
                    "createCustomCaptureSession",
                    CameraDevice::class.java,
                    InputConfiguration::class.java,
                    java.util.List::class.java,
                    Int::class.javaPrimitiveType,
                    CameraCaptureSession.StateCallback::class.java,
                    Handler::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            @Suppress("UNCHECKED_CAST")
                            val requestedSurfaces = (param.args[2] as? List<Any?>)?.mapNotNull {
                                it as? Surface
                            }
                            resetSurface()
                            intercepting.set(true)
                            xLog("oppo createCustomCaptureSession surfaces:$requestedSurfaces redirect:$nullSurface")
                            param.args[2] = listOfNotNull(nullSurface?.let { OutputConfiguration(it) })
                        }
                    }
                )
            } catch (_: Throwable) {
                xLog("oppo createCustomCaptureSession not available")
            }
        }

        private fun hookAddTarget(classLoader: ClassLoader) {
            try {
                XposedHelpers.findAndHookMethod(
                    "android.hardware.camera2.CaptureRequest.Builder",
                    classLoader,
                    "addTarget",
                    Surface::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!intercepting.get()) {
                                return
                            }
                            val surface = param.args[0] as? Surface ?: return
                            xLog("camera2 addTarget surface:$surface")
                            if (virtualSurface == null) {
                                virtualSurface = surface
                                resetIjkMediaPlayer()
                                PlayIjk.play(virtualSurface, ijkMediaPlayer)
                            }
                            ensureNullSurface()
                            param.args[0] = nullSurface
                        }
                    }
                )
            } catch (_: Throwable) {
            }
        }

        private fun hookTextureViewConstructors(classLoader: ClassLoader) {
            try {
                val clazz = XposedHelpers.findClass("android.view.TextureView", classLoader)
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        xLog("TextureView constructed:${param.thisObject}")
                    }
                })
            } catch (_: Throwable) {
            }
        }

        private fun hookSessionStateCallback(callbackClazz: Class<*>) {
            if (sessionStateCallbackClazz != null) {
                return
            }
            sessionStateCallbackClazz = callbackClazz
            try {
                XposedHelpers.findAndHookMethod(
                    sessionStateCallbackClazz,
                    "onConfigured",
                    CameraCaptureSession::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            xLog("camera2 session configured")
                        }
                    }
                )
            } catch (_: Throwable) {
            }

            try {
                XposedHelpers.findAndHookMethod(
                    sessionStateCallbackClazz,
                    "onConfigureFailed",
                    CameraCaptureSession::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            xLog("camera2 session configure failed")
                        }
                    }
                )
            } catch (_: Throwable) {
            }
        }

        private fun resetSurface() {
            virtualSurface = null
            nullSurfaceTex?.release()
            nullSurface?.release()
            nullSurfaceTex = SurfaceTexture(15)
            nullSurface = Surface(nullSurfaceTex)
        }

        private fun ensureNullSurface() {
            if (nullSurface == null || nullSurfaceTex == null) {
                nullSurfaceTex = SurfaceTexture(15)
                nullSurface = Surface(nullSurfaceTex)
            }
        }

        private fun resetIjkMediaPlayer() {
            if (ijkMediaPlayer?.isPlaying == true) {
                ijkMediaPlayer?.stop()
            }
            ijkMediaPlayer?.release()
            ijkMediaPlayer = IjkMediaPlayer {}
        }
    }

    private inner class Camera1Pipeline {

        private val hooksInstalled = AtomicBoolean(false)
        private val fps = 30
        private var lastDrawTimestamp = 0L
        private var previewCallbackClazz: Class<*>? = null
        private var virtualSurfaceView: SurfaceView? = null
        private var camera: Camera? = null

        @Volatile
        private var width = 0

        @Volatile
        private var height = 0

        @Volatile
        private var yuvByteArray: ByteArray? = null

        private var drawJob: Job? = null
        private var bitmap: Bitmap? = null
        private var ijkMediaPlayer: IjkMediaPlayer? = null

        fun installHooks(lpparam: LoadPackageParam) {
            if (!hooksInstalled.compareAndSet(false, true)) {
                return
            }
            val classLoader = lpparam.classLoader ?: return
            hookStartPreview(classLoader, lpparam)
            hookStopPreview(classLoader)
            hookSetPreviewCallbackWithBuffer(classLoader, lpparam)
            hookSetPreviewCallback(classLoader, lpparam)
        }

        private fun hookStartPreview(classLoader: ClassLoader, lpparam: LoadPackageParam) {
            try {
                XposedHelpers.findAndHookMethod(
                    "android.hardware.Camera",
                    classLoader,
                    "startPreview",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            xLog("camera1 startPreview package:${lpparam.packageName}")
                            stopPreview()
                            startPreview()
                            HookUtils.dumpView(HookUtils.getContentView(), 0)
                        }
                    }
                )
            } catch (_: Throwable) {
            }
        }

        private fun hookStopPreview(classLoader: ClassLoader) {
            try {
                XposedHelpers.findAndHookMethod(
                    "android.hardware.Camera",
                    classLoader,
                    "stopPreview",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            xLog("camera1 stopPreview")
                            stopPreview()
                        }
                    }
                )
            } catch (_: Throwable) {
            }
        }

        private fun hookSetPreviewCallbackWithBuffer(classLoader: ClassLoader, lpparam: LoadPackageParam) {
            try {
                XposedHelpers.findAndHookMethod(
                    "android.hardware.Camera",
                    classLoader,
                    "setPreviewCallbackWithBuffer",
                    PreviewCallback::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (previewCallbackClazz != null) {
                                return
                            }
                            previewCallbackClazz = param.args[0].javaClass
                            hookPreviewCallback(lpparam)
                        }
                    }
                )
            } catch (_: Throwable) {
            }
        }

        private fun hookSetPreviewCallback(classLoader: ClassLoader, lpparam: LoadPackageParam) {
            try {
                XposedHelpers.findAndHookMethod(
                    "android.hardware.Camera",
                    classLoader,
                    "setPreviewCallback",
                    PreviewCallback::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (previewCallbackClazz != null) {
                                return
                            }
                            previewCallbackClazz = param.args[0].javaClass
                            hookPreviewCallback(lpparam)
                        }
                    }
                )
            } catch (_: Throwable) {
            }
        }

        private fun hookPreviewCallback(lpparam: LoadPackageParam) {
            val clazz = previewCallbackClazz ?: return
            try {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "onPreviewFrame",
                    ByteArray::class.java,
                    Camera::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (drawJob?.isActive != true) {
                                return
                            }
                            camera = param.args[1] as? Camera
                            width = camera?.parameters?.previewSize?.width ?: 0
                            height = camera?.parameters?.previewSize?.height ?: 0
                            val byteArray = param.args[0] as? ByteArray ?: return
                            val source = yuvByteArray
                            if (source != null) {
                                System.arraycopy(source, 0, byteArray, 0, min(byteArray.size, source.size))
                            } else {
                                byteArray.fill(0)
                            }
                        }
                    }
                )
            } catch (_: Throwable) {
            }
        }

        private fun startPreview() {
            if (drawJob?.isActive == true) {
                drawJob?.cancel()
            }
            drawJob = HookUtils.coroutineScope().launch {
                drawer()
            }
            ensureVirtualSurfaceView()
        }

        private fun stopPreview() {
            drawJob?.cancel()
            resetIjkMediaPlayer()
            if (virtualSurfaceView != null) {
                HookUtils.getTopActivity()?.runOnUiThread {
                    HookUtils.getContentView()?.removeView(virtualSurfaceView)
                }
                virtualSurfaceView = null
            }
        }

        private fun ensureVirtualSurfaceView() {
            if (virtualSurfaceView != null) {
                resetIjkMediaPlayer()
                PlayIjk.play(virtualSurfaceView?.holder?.surface, ijkMediaPlayer)
                return
            }
            val context = HookUtils.getTopActivity() ?: return
            virtualSurfaceView = SurfaceView(context)
            context.runOnUiThread {
                virtualSurfaceView ?: return@runOnUiThread
                HookUtils.getContentView()?.addView(virtualSurfaceView)
                HookUtils.getContentView()?.getChildAt(0)?.bringToFront()
                virtualSurfaceView?.layoutParams = FrameLayout.LayoutParams(2, 2)
                virtualSurfaceView?.visibility = View.VISIBLE
            }
            virtualSurfaceView?.holder?.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    resetIjkMediaPlayer()
                    PlayIjk.play(holder.surface, ijkMediaPlayer)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                }
            })
        }

        private suspend fun drawer() {
            while (true) {
                try {
                    if (width == 0 || height == 0) {
                        delay(1000L / fps.toLong())
                        continue
                    }
                    lastDrawTimestamp = System.currentTimeMillis()
                    bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    virtualSurfaceView?.let {
                        captureSurfaceBitmap(it)
                    }
                    bitmap = getRotateBitmap(bitmap, -90f, width, height)
                    yuvByteArray = bitmap?.let { bitmapToYuv(it, width, height) }
                    val cost = System.currentTimeMillis() - lastDrawTimestamp
                    val interval = 1000L / fps.toLong()
                    if (cost < interval) {
                        delay(interval - cost)
                    }
                } catch (throwable: Throwable) {
                    xLog("drawer exception:$throwable")
                    stopPreview()
                    return
                }
            }
        }

        private fun resetIjkMediaPlayer() {
            if (ijkMediaPlayer?.isPlaying == true) {
                ijkMediaPlayer?.stop()
            }
            ijkMediaPlayer?.release()
            ijkMediaPlayer = IjkMediaPlayer {}
        }

        private fun getRotateBitmap(bitmap: Bitmap?, rotateDegree: Float, width: Int, height: Int): Bitmap? {
            bitmap ?: return null
            val matrix = Matrix()
            matrix.postRotate(rotateDegree)
            matrix.postScale(width / height.toFloat(), height / width.toFloat())
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
        }

        private fun bitmapToYuv(bitmap: Bitmap, width: Int, height: Int): ByteArray? {
            val intArray = IntArray(width * height)
            bitmap.getPixels(intArray, 0, width, 0, 0, width, height)
            return EncoderJNI.encodeYUV420SP(intArray, width, height)
        }

        private fun captureSurfaceBitmap(surfaceView: SurfaceView) {
            val targetBitmap = bitmap ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                PixelCopy.request(
                    surfaceView,
                    targetBitmap,
                    { copyResult ->
                        xLog("PixelCopy result:$copyResult")
                    },
                    Handler(HookUtils.getTopActivity()?.mainLooper ?: return)
                )
            } else {
                xLog("PixelCopy unsupported below Android N")
            }
        }
    }
}
