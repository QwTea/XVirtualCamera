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

    private fun logHookFailure(point: String, throwable: Throwable) {
        xLog("[VirtualCameraUniversal::$point] ${throwable.stackTraceToString()}")
    }

    override fun getName(): String = "通用虚拟摄像头模块"

    private val camera2Pipeline = Camera2Pipeline()
    private val camera1Pipeline = Camera1Pipeline()

    override fun init(cl: ClassLoader?) {
        xLog("[VirtualCameraUniversal.init] classLoader=$cl")
    }

    override fun registerRes(moduleRes: XModuleResources?) {
        xLog("[VirtualCameraUniversal.registerRes] moduleRes=$moduleRes")
    }

    override fun hook(lpparam: LoadPackageParam?) {
        xLog("[VirtualCameraUniversal.hook] start with lpparam=$lpparam classLoader=${lpparam?.classLoader}")
        lpparam ?: return
        camera2Pipeline.installHooks(lpparam)
        camera1Pipeline.installHooks(lpparam)
        xLog("[VirtualCameraUniversal.hook] hook installation complete for package=${lpparam.packageName}")
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
            xLog("[Camera2Pipeline.installHooks] installing for package=${lpparam.packageName} classLoader=${lpparam.classLoader}")
            if (!hooksInstalled.compareAndSet(false, true)) {
                xLog("[Camera2Pipeline.installHooks] hooks already installed, skipping")
                return
            }
            val classLoader = lpparam.classLoader ?: return
            xLog("[Camera2Pipeline.installHooks] proceeding with classLoader=$classLoader")
            hookImageReader(classLoader)
            hookCreateCaptureSessionApi28(classLoader, lpparam)
            hookCreateCaptureSessionLegacy(classLoader)
            hookOppoCreateCaptureSession(classLoader)
            hookAddTarget(classLoader)
            hookTextureViewConstructors(classLoader)
            xLog("[Camera2Pipeline.installHooks] hook registration finished")
        }

        private fun hookImageReader(classLoader: ClassLoader) {
            xLog("[Camera2Pipeline.hookImageReader] classLoader=$classLoader")
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
                xLog("[Camera2Pipeline.hookImageReader] hook attached successfully")
            } catch (throwable: Throwable) {
                logHookFailure("Camera2.hookImageReader", throwable)
            }
        }

        private fun hookCreateCaptureSessionApi28(classLoader: ClassLoader, lpparam: LoadPackageParam) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                xLog("[Camera2Pipeline.hookCreateCaptureSessionApi28] skipping due to SDK ${Build.VERSION.SDK_INT}")
                return
            }
            xLog("[Camera2Pipeline.hookCreateCaptureSessionApi28] installing hook classLoader=$classLoader")
            try {
                XposedHelpers.findAndHookMethod(
                    "android.hardware.camera2.impl.CameraDeviceImpl",
                    classLoader,
                    "createCaptureSession",
                    SessionConfiguration::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val sessionConfiguration = param.args[0] as SessionConfiguration
                            xLog("[Camera2Pipeline.hookCreateCaptureSessionApi28] original sessionConfiguration=$sessionConfiguration")
                            resetSurface()
                            intercepting.set(true)
                            xLog("[Camera2Pipeline.hookCreateCaptureSessionApi28] intercepting capture session, intercepting=${intercepting.get()}")
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
                                xLog("[Camera2Pipeline.hookCreateCaptureSessionApi28] redirecting to placeholder surface=$nullSurface")
                                param.args[0] = redirectedConfiguration
                            }
                            hookSessionStateCallback(sessionConfiguration.stateCallback.javaClass)
                        }
                    }
                )
                xLog("[Camera2Pipeline.hookCreateCaptureSessionApi28] hook attached successfully")
            } catch (throwable: Throwable) {
                logHookFailure("Camera2.hookCreateCaptureSessionApi28", throwable)
            }
        }

        private fun hookCreateCaptureSessionLegacy(classLoader: ClassLoader) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                xLog("[Camera2Pipeline.hookCreateCaptureSessionLegacy] skipping due to SDK ${Build.VERSION.SDK_INT}")
                return
            }
            xLog("[Camera2Pipeline.hookCreateCaptureSessionLegacy] installing hook classLoader=$classLoader")
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
                                xLog("[Camera2Pipeline.hookCreateCaptureSessionLegacy] no surfaces provided, skipping redirect")
                                return
                            }
                            resetSurface()
                            intercepting.set(true)
                            xLog("camera2 legacy createCaptureSession surfaces:$surfaces redirect:$nullSurface")
                            param.args[0] = listOfNotNull(nullSurface)
                        }
                    }
                )
                xLog("[Camera2Pipeline.hookCreateCaptureSessionLegacy] hook attached successfully")
            } catch (throwable: Throwable) {
                logHookFailure("Camera2.hookCreateCaptureSessionLegacy", throwable)
            }
        }

        private fun hookOppoCreateCaptureSession(classLoader: ClassLoader) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                xLog("[Camera2Pipeline.hookOppoCreateCaptureSession] skipping due to SDK ${Build.VERSION.SDK_INT}")
                return
            }
            xLog("[Camera2Pipeline.hookOppoCreateCaptureSession] installing hook classLoader=$classLoader")
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
                xLog("[Camera2Pipeline.hookOppoCreateCaptureSession] hook attached successfully")
            } catch (throwable: Throwable) {
                logHookFailure("Camera2.hookOppoCreateCaptureSession", throwable)
            }
        }

        private fun hookAddTarget(classLoader: ClassLoader) {
            xLog("[Camera2Pipeline.hookAddTarget] installing hook classLoader=$classLoader")
            try {
                XposedHelpers.findAndHookMethod(
                    "android.hardware.camera2.CaptureRequest.Builder",
                    classLoader,
                    "addTarget",
                    Surface::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!intercepting.get()) {
                                xLog("[Camera2Pipeline.hookAddTarget] not intercepting, leaving surface untouched")
                                return
                            }
                            val surface = param.args[0] as? Surface ?: return
                            xLog("camera2 addTarget surface:$surface")
                            if (virtualSurface == null) {
                                xLog("[Camera2Pipeline.hookAddTarget] caching virtual surface=$surface")
                                virtualSurface = surface
                                resetIjkMediaPlayer()
                                PlayIjk.play(virtualSurface, ijkMediaPlayer)
                            }
                            ensureNullSurface()
                            param.args[0] = nullSurface
                        }
                    }
                )
                xLog("[Camera2Pipeline.hookAddTarget] hook attached successfully")
            } catch (throwable: Throwable) {
                logHookFailure("Camera2.hookAddTarget", throwable)
            }
        }

        private fun hookTextureViewConstructors(classLoader: ClassLoader) {
            xLog("[Camera2Pipeline.hookTextureViewConstructors] installing hook classLoader=$classLoader")
            try {
                val clazz = XposedHelpers.findClass("android.view.TextureView", classLoader)
                XposedBridge.hookAllConstructors(clazz, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        xLog("TextureView constructed:${param.thisObject}")
                    }
                })
                xLog("[Camera2Pipeline.hookTextureViewConstructors] hook attached successfully")
            } catch (throwable: Throwable) {
                logHookFailure("Camera2.hookTextureViewConstructors", throwable)
            }
        }

        private fun hookSessionStateCallback(callbackClazz: Class<*>) {
            if (sessionStateCallbackClazz != null) {
                xLog("[Camera2Pipeline.hookSessionStateCallback] callbacks already hooked for class=$sessionStateCallbackClazz")
                return
            }
            sessionStateCallbackClazz = callbackClazz
            xLog("[Camera2Pipeline.hookSessionStateCallback] installing for class=$callbackClazz")
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
                xLog("[Camera2Pipeline.hookSessionStateCallback] onConfigured hook attached")
            } catch (throwable: Throwable) {
                logHookFailure("Camera2.hookSessionStateCallback.onConfigured", throwable)
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
                xLog("[Camera2Pipeline.hookSessionStateCallback] onConfigureFailed hook attached")
            } catch (throwable: Throwable) {
                logHookFailure("Camera2.hookSessionStateCallback.onConfigureFailed", throwable)
            }
        }

        private fun resetSurface() {
            xLog("[Camera2Pipeline.resetSurface] resetting cached surfaces. virtualSurface=$virtualSurface nullSurface=$nullSurface")
            virtualSurface = null
            nullSurfaceTex?.release()
            nullSurface?.release()
            nullSurfaceTex = SurfaceTexture(15)
            nullSurface = Surface(nullSurfaceTex)
            xLog("[Camera2Pipeline.resetSurface] new nullSurface=$nullSurface nullSurfaceTex=$nullSurfaceTex")
        }

        private fun ensureNullSurface() {
            xLog("[Camera2Pipeline.ensureNullSurface] current nullSurface=$nullSurface nullSurfaceTex=$nullSurfaceTex")
            if (nullSurface == null || nullSurfaceTex == null) {
                nullSurfaceTex = SurfaceTexture(15)
                nullSurface = Surface(nullSurfaceTex)
                xLog("[Camera2Pipeline.ensureNullSurface] created new null surface=$nullSurface")
            }
        }

        private fun resetIjkMediaPlayer() {
            xLog("[Camera2Pipeline.resetIjkMediaPlayer] resetting media player=$ijkMediaPlayer isPlaying=${ijkMediaPlayer?.isPlaying}")
            if (ijkMediaPlayer?.isPlaying == true) {
                ijkMediaPlayer?.stop()
                xLog("[Camera2Pipeline.resetIjkMediaPlayer] stopped current playback")
            }
            ijkMediaPlayer?.release()
            ijkMediaPlayer = IjkMediaPlayer {}
            xLog("[Camera2Pipeline.resetIjkMediaPlayer] created new IjkMediaPlayer instance=$ijkMediaPlayer")
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
            xLog("[Camera1Pipeline.installHooks] installing for package=${lpparam.packageName} classLoader=${lpparam.classLoader}")
            if (!hooksInstalled.compareAndSet(false, true)) {
                xLog("[Camera1Pipeline.installHooks] hooks already installed, skipping")
                return
            }
            val classLoader = lpparam.classLoader ?: return
            xLog("[Camera1Pipeline.installHooks] proceeding with classLoader=$classLoader")
            hookStartPreview(classLoader, lpparam)
            hookStopPreview(classLoader)
            hookSetPreviewCallbackWithBuffer(classLoader, lpparam)
            hookSetPreviewCallback(classLoader, lpparam)
            xLog("[Camera1Pipeline.installHooks] hook registration finished")
        }

        private fun hookStartPreview(classLoader: ClassLoader, lpparam: LoadPackageParam) {
            xLog("[Camera1Pipeline.hookStartPreview] installing hook classLoader=$classLoader")
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
                xLog("[Camera1Pipeline.hookStartPreview] hook attached successfully")
            } catch (throwable: Throwable) {
                logHookFailure("Camera1.hookStartPreview", throwable)
            }
        }

        private fun hookStopPreview(classLoader: ClassLoader) {
            xLog("[Camera1Pipeline.hookStopPreview] installing hook classLoader=$classLoader")
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
                xLog("[Camera1Pipeline.hookStopPreview] hook attached successfully")
            } catch (throwable: Throwable) {
                logHookFailure("Camera1.hookStopPreview", throwable)
            }
        }

        private fun hookSetPreviewCallbackWithBuffer(classLoader: ClassLoader, lpparam: LoadPackageParam) {
            xLog("[Camera1Pipeline.hookSetPreviewCallbackWithBuffer] installing hook classLoader=$classLoader")
            try {
                XposedHelpers.findAndHookMethod(
                    "android.hardware.Camera",
                    classLoader,
                    "setPreviewCallbackWithBuffer",
                    PreviewCallback::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (previewCallbackClazz != null) {
                                xLog("[Camera1Pipeline.hookSetPreviewCallbackWithBuffer] previewCallbackClazz already hooked=$previewCallbackClazz")
                                return
                            }
                            previewCallbackClazz = param.args[0].javaClass
                            xLog("[Camera1Pipeline.hookSetPreviewCallbackWithBuffer] captured callback class=$previewCallbackClazz")
                            hookPreviewCallback(lpparam)
                        }
                    }
                )
                xLog("[Camera1Pipeline.hookSetPreviewCallbackWithBuffer] hook attached successfully")
            } catch (throwable: Throwable) {
                logHookFailure("Camera1.hookSetPreviewCallbackWithBuffer", throwable)
            }
        }

        private fun hookSetPreviewCallback(classLoader: ClassLoader, lpparam: LoadPackageParam) {
            xLog("[Camera1Pipeline.hookSetPreviewCallback] installing hook classLoader=$classLoader")
            try {
                XposedHelpers.findAndHookMethod(
                    "android.hardware.Camera",
                    classLoader,
                    "setPreviewCallback",
                    PreviewCallback::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (previewCallbackClazz != null) {
                                xLog("[Camera1Pipeline.hookSetPreviewCallback] previewCallbackClazz already hooked=$previewCallbackClazz")
                                return
                            }
                            previewCallbackClazz = param.args[0].javaClass
                            xLog("[Camera1Pipeline.hookSetPreviewCallback] captured callback class=$previewCallbackClazz")
                            hookPreviewCallback(lpparam)
                        }
                    }
                )
                xLog("[Camera1Pipeline.hookSetPreviewCallback] hook attached successfully")
            } catch (throwable: Throwable) {
                logHookFailure("Camera1.hookSetPreviewCallback", throwable)
            }
        }

        private fun hookPreviewCallback(lpparam: LoadPackageParam) {
            val clazz = previewCallbackClazz ?: return
            xLog("[Camera1Pipeline.hookPreviewCallback] installing for class=$clazz")
            try {
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "onPreviewFrame",
                    ByteArray::class.java,
                    Camera::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (drawJob?.isActive != true) {
                                xLog("[Camera1Pipeline.hookPreviewCallback] drawJob inactive, skipping frame override")
                                return
                            }
                            camera = param.args[1] as? Camera
                            width = camera?.parameters?.previewSize?.width ?: 0
                            height = camera?.parameters?.previewSize?.height ?: 0
                            xLog("[Camera1Pipeline.hookPreviewCallback] camera=$camera width=$width height=$height")
                            val byteArray = param.args[0] as? ByteArray ?: return
                            val source = yuvByteArray
                            if (source != null) {
                                System.arraycopy(source, 0, byteArray, 0, min(byteArray.size, source.size))
                                xLog("[Camera1Pipeline.hookPreviewCallback] copied YUV buffer size=${source.size}")
                            } else {
                                byteArray.fill(0)
                                xLog("[Camera1Pipeline.hookPreviewCallback] source buffer null, filled zeros")
                            }
                        }
                    }
                )
                xLog("[Camera1Pipeline.hookPreviewCallback] hook attached successfully")
            } catch (throwable: Throwable) {
                logHookFailure("Camera1.hookPreviewCallback", throwable)
            }
        }

        private fun startPreview() {
            xLog("[Camera1Pipeline.startPreview] requested startPreview")
            if (drawJob?.isActive == true) {
                xLog("[Camera1Pipeline.startPreview] cancelling existing draw job")
                drawJob?.cancel()
            }
            drawJob = HookUtils.coroutineScope().launch {
                xLog("[Camera1Pipeline.startPreview] coroutine launched for drawer")
                drawer()
            }
            ensureVirtualSurfaceView()
        }

        private fun stopPreview() {
            xLog("[Camera1Pipeline.stopPreview] requested stopPreview")
            drawJob?.cancel()
            resetIjkMediaPlayer()
            if (virtualSurfaceView != null) {
                HookUtils.getTopActivity()?.runOnUiThread {
                    xLog("[Camera1Pipeline.stopPreview] removing virtualSurfaceView from contentView")
                    HookUtils.getContentView()?.removeView(virtualSurfaceView)
                }
                virtualSurfaceView = null
                xLog("[Camera1Pipeline.stopPreview] virtualSurfaceView cleared")
            }
        }

        private fun ensureVirtualSurfaceView() {
            xLog("[Camera1Pipeline.ensureVirtualSurfaceView] current view=$virtualSurfaceView")
            if (virtualSurfaceView != null) {
                resetIjkMediaPlayer()
                PlayIjk.play(virtualSurfaceView?.holder?.surface, ijkMediaPlayer)
                xLog("[Camera1Pipeline.ensureVirtualSurfaceView] reused existing surface view")
                return
            }
            val context = HookUtils.getTopActivity() ?: return
            xLog("[Camera1Pipeline.ensureVirtualSurfaceView] creating new SurfaceView for context=$context")
            virtualSurfaceView = SurfaceView(context)
            context.runOnUiThread {
                virtualSurfaceView ?: return@runOnUiThread
                HookUtils.getContentView()?.addView(virtualSurfaceView)
                HookUtils.getContentView()?.getChildAt(0)?.bringToFront()
                virtualSurfaceView?.layoutParams = FrameLayout.LayoutParams(2, 2)
                virtualSurfaceView?.visibility = View.VISIBLE
                xLog("[Camera1Pipeline.ensureVirtualSurfaceView] surfaceView added to hierarchy")
            }
            virtualSurfaceView?.holder?.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    xLog("[Camera1Pipeline.SurfaceCallback] surfaceCreated holder=$holder")
                    resetIjkMediaPlayer()
                    PlayIjk.play(holder.surface, ijkMediaPlayer)
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    xLog("[Camera1Pipeline.SurfaceCallback] surfaceChanged holder=$holder format=$format width=$width height=$height")
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    xLog("[Camera1Pipeline.SurfaceCallback] surfaceDestroyed holder=$holder")
                }
            })
        }

        private suspend fun drawer() {
            xLog("[Camera1Pipeline.drawer] start drawing loop")
            while (true) {
                try {
                    if (width == 0 || height == 0) {
                        xLog("[Camera1Pipeline.drawer] waiting for valid preview size width=$width height=$height")
                        delay(1000L / fps.toLong())
                        continue
                    }
                    lastDrawTimestamp = System.currentTimeMillis()
                    bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    xLog("[Camera1Pipeline.drawer] created bitmap size=${bitmap?.width}x${bitmap?.height}")
                    virtualSurfaceView?.let {
                        xLog("[Camera1Pipeline.drawer] capturing surface bitmap from view=$it")
                        captureSurfaceBitmap(it)
                    }
                    bitmap = getRotateBitmap(bitmap, -90f, width, height)
                    xLog("[Camera1Pipeline.drawer] rotated bitmap size=${bitmap?.width}x${bitmap?.height}")
                    yuvByteArray = bitmap?.let { bitmapToYuv(it, width, height) }
                    xLog("[Camera1Pipeline.drawer] updated YUV buffer size=${yuvByteArray?.size}")
                    val cost = System.currentTimeMillis() - lastDrawTimestamp
                    val interval = 1000L / fps.toLong()
                    if (cost < interval) {
                        xLog("[Camera1Pipeline.drawer] sleep ${(interval - cost)}ms to maintain fps=$fps")
                        delay(interval - cost)
                    }
                } catch (throwable: Throwable) {
                    logHookFailure("Camera1.drawer", throwable)
                    stopPreview()
                    return
                }
            }
        }

        private fun resetIjkMediaPlayer() {
            xLog("[Camera1Pipeline.resetIjkMediaPlayer] resetting media player=$ijkMediaPlayer isPlaying=${ijkMediaPlayer?.isPlaying}")
            if (ijkMediaPlayer?.isPlaying == true) {
                ijkMediaPlayer?.stop()
                xLog("[Camera1Pipeline.resetIjkMediaPlayer] stopped current playback")
            }
            ijkMediaPlayer?.release()
            ijkMediaPlayer = IjkMediaPlayer {}
            xLog("[Camera1Pipeline.resetIjkMediaPlayer] created new IjkMediaPlayer instance=$ijkMediaPlayer")
        }

        private fun getRotateBitmap(bitmap: Bitmap?, rotateDegree: Float, width: Int, height: Int): Bitmap? {
            xLog("[Camera1Pipeline.getRotateBitmap] bitmap=$bitmap rotateDegree=$rotateDegree width=$width height=$height")
            bitmap ?: return null
            val matrix = Matrix()
            matrix.postRotate(rotateDegree)
            matrix.postScale(width / height.toFloat(), height / width.toFloat())
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false)
            xLog("[Camera1Pipeline.getRotateBitmap] rotated bitmap size=${rotated.width}x${rotated.height}")
            return rotated
        }

        private fun bitmapToYuv(bitmap: Bitmap, width: Int, height: Int): ByteArray? {
            xLog("[Camera1Pipeline.bitmapToYuv] bitmap=${bitmap.width}x${bitmap.height} width=$width height=$height")
            val intArray = IntArray(width * height)
            bitmap.getPixels(intArray, 0, width, 0, 0, width, height)
            val result = EncoderJNI.encodeYUV420SP(intArray, width, height)
            xLog("[Camera1Pipeline.bitmapToYuv] native encode resultSize=${result?.size}")
            return result
        }

        private fun captureSurfaceBitmap(surfaceView: SurfaceView) {
            val targetBitmap = bitmap ?: return
            xLog("[Camera1Pipeline.captureSurfaceBitmap] targetBitmap=${targetBitmap.width}x${targetBitmap.height} surfaceView=$surfaceView")
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
