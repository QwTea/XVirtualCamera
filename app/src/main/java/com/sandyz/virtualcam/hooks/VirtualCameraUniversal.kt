package com.sandyz.virtualcam.hooks

import android.content.ContentResolver
import android.content.ContentValues
import android.content.res.AssetFileDescriptor
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
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.min

object PhotoSwapState {
    @Volatile
    var lastStillCaptureAt: Long = 0L

    @Volatile
    private var active: Boolean = false

    private val uriSet = Collections.synchronizedSet(mutableSetOf<Uri>())
    private val activeUriSet = Collections.synchronizedSet(mutableSetOf<Uri>())
    private val pathSet = Collections.synchronizedSet(mutableSetOf<String>())
    private val activePathSet = Collections.synchronizedSet(mutableSetOf<String>())
    private val streamPathMap = Collections.synchronizedMap(WeakHashMap<Any, String>())
    private val pfdMap = Collections.synchronizedMap(WeakHashMap<ParcelFileDescriptor, Pair<ContentResolver, Uri>>())

    private val reentry = ThreadLocal<Boolean>()

    fun markStillCapture() {
        lastStillCaptureAt = System.currentTimeMillis()
        active = true
        xLog("[Swap] still capture window opened")
    }

    fun inWindow(ttlMs: Long = 5000L): Boolean {
        val within = System.currentTimeMillis() - lastStillCaptureAt < ttlMs
        if (!within) {
            active = false
            activeUriSet.clear()
            activePathSet.clear()
        }
        return within
    }

    private fun shouldIntercept(): Boolean = active && inWindow()

    fun trackUri(uri: Uri) {
        if (!inWindow()) {
            lastStillCaptureAt = System.currentTimeMillis()
        }
        uriSet.add(uri)
        active = true
    }

    fun claimUri(uri: Uri): Boolean {
        if (!shouldIntercept()) return false
        if (activeUriSet.contains(uri)) return true
        val claimed = uriSet.remove(uri) || active
        if (claimed) {
            activeUriSet.add(uri)
        }
        return claimed
    }

    fun releaseUri(uri: Uri) {
        activeUriSet.remove(uri)
    }

    fun trackPath(path: String) {
        if (!inWindow()) {
            lastStillCaptureAt = System.currentTimeMillis()
        }
        pathSet.add(path)
        active = true
    }

    fun claimPath(path: String): Boolean {
        if (!shouldIntercept()) return false
        if (activePathSet.contains(path)) return true
        val claimed = pathSet.remove(path) || active
        if (claimed) {
            activePathSet.add(path)
        }
        return claimed
    }

    fun releasePath(path: String) {
        activePathSet.remove(path)
    }

    fun associateStream(stream: Any, path: String) {
        streamPathMap[stream] = path
    }

    fun consumeStreamPath(stream: Any): String? = streamPathMap.remove(stream)

    fun trackPfd(pfd: ParcelFileDescriptor, resolver: ContentResolver, uri: Uri) {
        pfdMap[pfd] = resolver to uri
    }

    fun consumePfd(pfd: ParcelFileDescriptor): Pair<ContentResolver, Uri>? = pfdMap.remove(pfd)

    fun isSelfCall(): Boolean = reentry.get() == true

    fun enterSelf(): Boolean {
        if (isSelfCall()) return false
        reentry.set(true)
        return true
    }

    fun exitSelf() {
        reentry.set(false)
    }
}

class VirtualCameraUniversal : IHook {

    private fun logHookFailure(point: String, throwable: Throwable) {
        xLog("[VirtualCameraUniversal::$point] ${throwable.stackTraceToString()}")
    }

    override fun getName(): String = "通用虚拟摄像头模块"

    private val camera2Pipeline = Camera2Pipeline()
    private val camera1Pipeline = Camera1Pipeline()
    private val contentResolverHooksInstalled = AtomicBoolean(false)
    private val fileOutputStreamHooksInstalled = AtomicBoolean(false)
    private val parcelFileDescriptorHookInstalled = AtomicBoolean(false)

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
        private val protectedTargets = Collections.newSetFromMap(WeakHashMap<Surface, Boolean>())
        private val builderTemplates = WeakHashMap<Any, Int>()

        fun installHooks(lpparam: LoadPackageParam) {
            xLog("[Camera2Pipeline.installHooks] installing for package=${lpparam.packageName} classLoader=${lpparam.classLoader}")
            if (!hooksInstalled.compareAndSet(false, true)) {
                xLog("[Camera2Pipeline.installHooks] hooks already installed, skipping")
                return
            }
            val classLoader = lpparam.classLoader ?: return
            xLog("[Camera2Pipeline.installHooks] proceeding with classLoader=$classLoader")
            hookImageReader(classLoader)
            hookCreateCaptureRequest(classLoader)
            hookCreateCaptureSessionApi28(classLoader, lpparam)
            hookCreateCaptureSessionLegacy(classLoader)
            hookOppoCreateCaptureSession(classLoader)
            hookProtectConsumerSurfaces(classLoader)
            hookAddTarget(classLoader)
            hookTextureViewConstructors(classLoader)
            hookContentResolver(classLoader)
            hookFileOutputStream()
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

        private fun hookCreateCaptureRequest(classLoader: ClassLoader) {
            try {
                XposedHelpers.findAndHookMethod(
                    "android.hardware.camera2.impl.CameraDeviceImpl",
                    classLoader,
                    "createCaptureRequest",
                    Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val template = param.args[0] as? Int ?: return
                            val builder = param.result ?: return
                            builderTemplates[builder] = template
                            if (template == CaptureRequest.TEMPLATE_STILL_CAPTURE) {
                                PhotoSwapState.markStillCapture()
                            }
                            xLog("[C2] createCaptureRequest template=$template builder=$builder")
                        }
                    }
                )
            } catch (t: Throwable) {
                logHookFailure("Camera2.hookCreateCaptureRequest", t)
            }
        }

        private fun hookProtectConsumerSurfaces(classLoader: ClassLoader) {
            try {
                XposedHelpers.findAndHookMethod(
                    "android.media.ImageReader",
                    classLoader,
                    "getSurface",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            (param.result as? Surface)?.let {
                                protectedTargets.add(it)
                                xLog("[C2] protect ImageReader surface=$it")
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                logHookFailure("Camera2.hookProtect.ImageReader", t)
            }

            try {
                XposedHelpers.findAndHookMethod(
                    "android.media.MediaRecorder",
                    classLoader,
                    "getSurface",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            (param.result as? Surface)?.let {
                                protectedTargets.add(it)
                                xLog("[C2] protect MediaRecorder surface=$it")
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                logHookFailure("Camera2.hookProtect.MediaRecorder", t)
            }

            try {
                XposedHelpers.findAndHookMethod(
                    "android.media.MediaCodec",
                    classLoader,
                    "createPersistentInputSurface",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            (param.result as? Surface)?.let {
                                protectedTargets.add(it)
                                xLog("[C2] protect persistent input surface=$it")
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                logHookFailure("Camera2.hookProtect.MediaCodec", t)
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
                            resetSurface()
                            intercepting.set(true)
                            val outputs = sessionConfiguration.outputConfigurations.toMutableList()
                            ensureNullSurface()
                            nullSurface?.let { outputs.add(OutputConfiguration(it)) }

                            val redirected = SessionConfiguration(
                                sessionConfiguration.sessionType,
                                outputs,
                                sessionConfiguration.executor,
                                sessionConfiguration.stateCallback
                            )
                            sessionConfiguration.inputConfiguration?.let { redirected.setInputConfiguration(it) }
                            try {
                                val sessionParams = sessionConfiguration.sessionParameters
                                if (sessionParams != null) {
                                    redirected.setSessionParameters(sessionParams)
                                }
                            } catch (_: Throwable) {
                                // ignore reflection issues on lower API levels
                            }
                            param.args[0] = redirected
                            hookSessionStateCallback(sessionConfiguration.stateCallback.javaClass)
                            xLog("[C2] createCaptureSession(api28+) added nullSurface=$nullSurface outputs=${outputs.size}")
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
                            resetSurface()
                            intercepting.set(true)
                            ensureNullSurface()
                            @Suppress("UNCHECKED_CAST")
                            val surfaces = (param.args[0] as? List<Surface>)?.toMutableList() ?: return
                            nullSurface?.let { surfaces.add(it) }
                            param.args[0] = surfaces
                            xLog("[C2] createCaptureSession(legacy) appended nullSurface=$nullSurface size=${surfaces.size}")
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
                            resetSurface()
                            intercepting.set(true)
                            ensureNullSurface()
                            @Suppress("UNCHECKED_CAST")
                            val requested = (param.args[2] as? List<OutputConfiguration>)?.toMutableList() ?: return
                            nullSurface?.let { requested.add(OutputConfiguration(it)) }
                            param.args[2] = requested
                            xLog("[C2] OPPO createCustomCaptureSession appended nullSurface=$nullSurface size=${requested.size}")
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
                            if (!intercepting.get()) return
                            val builder = param.thisObject
                            val template = builderTemplates[builder] ?: -1
                            val surface = param.args[0] as? Surface ?: return

                            if (protectedTargets.contains(surface)) {
                                xLog("[C2] addTarget protected -> pass $surface (template=$template)")
                                return
                            }

                            xLog("[C2] addTarget PREVIEW-like surface=$surface (template=$template)")

                            if (virtualSurface == null) {
                                virtualSurface = surface
                                resetIjkMediaPlayer()
                                PlayIjk.play(virtualSurface, ijkMediaPlayer)
                            }
                            ensureNullSurface()
                            nullSurface?.let {
                                param.args[0] = it
                            }
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
                            intercepting.set(false)
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
                            intercepting.set(false)
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

        fun currentVirtualSurface(): Surface? = virtualSurface
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

        fun currentSurface(): Surface? = virtualSurfaceView?.holder?.surface

        fun previewSize(): Pair<Int, Int>? = if (width > 0 && height > 0) {
            width to height
        } else {
            null
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

    private fun hookContentResolver(classLoader: ClassLoader) {
        if (!contentResolverHooksInstalled.compareAndSet(false, true)) {
            return
        }

        try {
            XposedHelpers.findAndHookMethod(
                ContentResolver::class.java,
                "insert",
                Uri::class.java,
                ContentValues::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (PhotoSwapState.isSelfCall()) return
                        val uri = param.result as? Uri ?: return
                        val values = param.args[1] as? ContentValues
                        val mime = values?.getAsString(MediaStore.MediaColumns.MIME_TYPE)
                        if (!"image/jpeg".equals(mime, ignoreCase = true)) return
                        if (!PhotoSwapState.inWindow()) return
                        PhotoSwapState.trackUri(uri)
                        xLog("[Swap] track insert uri=$uri mime=$mime")
                    }
                }
            )
        } catch (t: Throwable) {
            logHookFailure("Swap.hookCR.insert", t)
        }

        val openOutputStreamHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (PhotoSwapState.isSelfCall()) return
                val uri = param.args[0] as? Uri ?: return
                val mode = param.args.getOrNull(1) as? String
                if (mode != null && !mode.contains("w", ignoreCase = true)) return
                if (!PhotoSwapState.claimUri(uri)) return
                val original = param.result as? OutputStream ?: return
                val resolver = param.thisObject as? ContentResolver ?: return
                param.result = SwappingOutputStream(resolver, uri, original)
                xLog("[Swap] wrapped OutputStream for $uri mode=$mode")
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                ContentResolver::class.java,
                "openOutputStream",
                Uri::class.java,
                openOutputStreamHook
            )
        } catch (t: Throwable) {
            logHookFailure("Swap.hookCR.openOutputStream", t)
        }

        try {
            XposedHelpers.findAndHookMethod(
                ContentResolver::class.java,
                "openOutputStream",
                Uri::class.java,
                String::class.java,
                openOutputStreamHook
            )
        } catch (t: Throwable) {
            logHookFailure("Swap.hookCR.openOutputStreamMode", t)
        }

        val openFileDescriptorHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (PhotoSwapState.isSelfCall()) return
                val uri = param.args[0] as? Uri ?: return
                val mode = param.args[1] as? String ?: return
                if (!mode.contains("w", ignoreCase = true)) return
                if (!PhotoSwapState.claimUri(uri)) return
                val resolver = param.thisObject as? ContentResolver ?: return
                val pfd = param.result as? ParcelFileDescriptor ?: return
                PhotoSwapState.trackPfd(pfd, resolver, uri)
                xLog("[Swap] track ParcelFileDescriptor uri=$uri mode=$mode")
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                ContentResolver::class.java,
                "openFileDescriptor",
                Uri::class.java,
                String::class.java,
                openFileDescriptorHook
            )
        } catch (t: Throwable) {
            logHookFailure("Swap.hookCR.openFileDescriptor", t)
        }

        try {
            XposedHelpers.findAndHookMethod(
                ContentResolver::class.java,
                "openFileDescriptor",
                Uri::class.java,
                String::class.java,
                CancellationSignal::class.java,
                openFileDescriptorHook
            )
        } catch (t: Throwable) {
            logHookFailure("Swap.hookCR.openFileDescriptorSignal", t)
        }

        val openAssetFileDescriptorHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (PhotoSwapState.isSelfCall()) return
                val uri = param.args[0] as? Uri ?: return
                val mode = param.args[1] as? String ?: return
                if (!mode.contains("w", ignoreCase = true)) return
                if (!PhotoSwapState.claimUri(uri)) return
                val resolver = param.thisObject as? ContentResolver ?: return
                val asset = param.result as? AssetFileDescriptor ?: return
                val pfd = asset.parcelFileDescriptor ?: return
                PhotoSwapState.trackPfd(pfd, resolver, uri)
                xLog("[Swap] track AssetFileDescriptor uri=$uri mode=$mode")
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                ContentResolver::class.java,
                "openAssetFileDescriptor",
                Uri::class.java,
                String::class.java,
                openAssetFileDescriptorHook
            )
        } catch (t: Throwable) {
            logHookFailure("Swap.hookCR.openAssetFileDescriptor", t)
        }

        hookParcelFileDescriptor()
    }

    private fun hookParcelFileDescriptor() {
        if (!parcelFileDescriptorHookInstalled.compareAndSet(false, true)) {
            return
        }
        try {
            XposedHelpers.findAndHookMethod(
                ParcelFileDescriptor::class.java,
                "close",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (PhotoSwapState.isSelfCall()) return
                        val pair = PhotoSwapState.consumePfd(param.thisObject as ParcelFileDescriptor) ?: return
                        val (resolver, uri) = pair
                        if (!PhotoSwapState.enterSelf()) {
                            PhotoSwapState.releaseUri(uri)
                            return
                        }
                        try {
                            val bytes = captureReplacementJpeg()
                            if (bytes != null) {
                                resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                                xLog("[Swap] replaced JPEG for $uri via PFD size=${bytes.size}")
                            } else {
                                xLog("[Swap] capture frame failed for $uri via PFD")
                            }
                        } catch (t: Throwable) {
                            xLog("[Swap] replace failed for $uri via PFD: ${t.message}")
                        } finally {
                            PhotoSwapState.exitSelf()
                            PhotoSwapState.releaseUri(uri)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            logHookFailure("Swap.hookPFD.close", t)
        }
    }

    private fun hookFileOutputStream() {
        if (!fileOutputStreamHooksInstalled.compareAndSet(false, true)) {
            return
        }
        val className = "java.io.FileOutputStream"

        fun track(stream: Any, path: String?) {
            if (PhotoSwapState.isSelfCall()) return
            val actual = path ?: return
            if (!PhotoSwapState.inWindow()) return
            val lower = actual.lowercase(Locale.ROOT)
            if (!lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) return
            PhotoSwapState.trackPath(actual)
            PhotoSwapState.associateStream(stream, actual)
            xLog("[Swap] track path=$actual stream=$stream")
        }

        try {
            XposedHelpers.findAndHookConstructor(
                className,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        track(param.thisObject, param.args[0] as? String)
                    }
                }
            )
        } catch (t: Throwable) {
            logHookFailure("Swap.hookFOS.string", t)
        }

        try {
            XposedHelpers.findAndHookConstructor(
                className,
                File::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        track(param.thisObject, (param.args[0] as? File)?.absolutePath)
                    }
                }
            )
        } catch (t: Throwable) {
            logHookFailure("Swap.hookFOS.file", t)
        }

        try {
            XposedHelpers.findAndHookConstructor(
                className,
                File::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        track(param.thisObject, (param.args[0] as? File)?.absolutePath)
                    }
                }
            )
        } catch (t: Throwable) {
            logHookFailure("Swap.hookFOS.fileAppend", t)
        }

        try {
            XposedHelpers.findAndHookMethod(
                className,
                null,
                "close",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (PhotoSwapState.isSelfCall()) return
                        val stream = param.thisObject
                        val path = PhotoSwapState.consumeStreamPath(stream) ?: return
                        val shouldSwap = PhotoSwapState.claimPath(path)
                        if (!shouldSwap) {
                            PhotoSwapState.releasePath(path)
                            return
                        }
                        if (!PhotoSwapState.enterSelf()) {
                            PhotoSwapState.releasePath(path)
                            return
                        }
                        try {
                            val bytes = captureReplacementJpeg()
                            if (bytes != null) {
                                FileOutputStream(File(path), false).use { it.write(bytes) }
                                xLog("[Swap] replaced JPEG file $path size=${bytes.size}")
                            } else {
                                xLog("[Swap] capture frame failed for file $path")
                            }
                        } catch (t: Throwable) {
                            xLog("[Swap] file replace failed for $path: ${t.message}")
                        } finally {
                            PhotoSwapState.exitSelf()
                            PhotoSwapState.releasePath(path)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            logHookFailure("Swap.hookFOS.close", t)
        }
    }

    private inner class SwappingOutputStream(
        private val resolver: ContentResolver,
        private val uri: Uri,
        out: OutputStream
    ) : FilterOutputStream(out) {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            try {
                super.close()
            } catch (t: Throwable) {
                xLog("[Swap] original stream close failed for $uri: ${t.message}")
            }
            if (!PhotoSwapState.enterSelf()) {
                PhotoSwapState.releaseUri(uri)
                return
            }
            try {
                val bytes = captureReplacementJpeg()
                if (bytes != null) {
                    resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                    xLog("[Swap] replaced JPEG for $uri size=${bytes.size}")
                } else {
                    xLog("[Swap] capture frame failed for $uri")
                }
            } catch (t: Throwable) {
                xLog("[Swap] replace failed for $uri: ${t.message}")
            } finally {
                PhotoSwapState.exitSelf()
                PhotoSwapState.releaseUri(uri)
            }
        }
    }

    private fun captureReplacementJpeg(): ByteArray? {
        val previewSize = camera1Pipeline.previewSize()
        val frame = captureVideoFrameBitmapSync(previewSize?.first, previewSize?.second) ?: return null
        val rotated = rotateUpright(frame)
        return try {
            val bytes = bitmapToJpegBytes(rotated, 90)
            if (rotated !== frame) {
                frame.recycle()
            }
            rotated.recycle()
            bytes
        } catch (t: Throwable) {
            xLog("[Swap] encode JPEG failed: ${t.message}")
            if (!frame.isRecycled) frame.recycle()
            if (!rotated.isRecycled) rotated.recycle()
            null
        }
    }

    private fun captureVideoFrameBitmapSync(targetW: Int?, targetH: Int?, timeoutMs: Long = 200L): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            xLog("[Swap] PixelCopy unsupported on API ${Build.VERSION.SDK_INT}")
            return null
        }
        val surface = camera2Pipeline.currentVirtualSurface()
            ?: camera1Pipeline.currentSurface()
            ?: return null
        val defaultSize = camera1Pipeline.previewSize()
            ?: HookUtils.getTopActivity()?.window?.decorView?.let { view ->
                if (view.width > 0 && view.height > 0) view.width to view.height else null
            }
            ?: (1080 to 1920)
        val width = targetW ?: defaultSize.first
        val height = targetH ?: defaultSize.second
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val handlerLooper = HookUtils.getTopActivity()?.mainLooper ?: Looper.getMainLooper()
        val looper = handlerLooper ?: return null
        val handler = Handler(looper)
        val latch = CountDownLatch(1)
        var success = false
        try {
            PixelCopy.request(
                surface,
                bitmap,
                { result ->
                    success = result == PixelCopy.SUCCESS
                    latch.countDown()
                },
                handler
            )
        } catch (t: Throwable) {
            xLog("[Swap] PixelCopy request failed: ${t.message}")
            bitmap.recycle()
            return null
        }
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS) || !success) {
                bitmap.recycle()
                return null
            }
        } catch (t: InterruptedException) {
            Thread.currentThread().interrupt()
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    private fun rotateUpright(src: Bitmap): Bitmap {
        val activity = HookUtils.getTopActivity()
        val rotation = activity?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        val degrees = when (rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        if (degrees == 0) {
            return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply {
            postRotate(degrees.toFloat())
        }, true)
    }

    private fun bitmapToJpegBytes(bitmap: Bitmap, quality: Int = 90): ByteArray {
        val stream = ByteArrayOutputStream()
        return stream.use {
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)
            it.toByteArray()
        }
    }
}
