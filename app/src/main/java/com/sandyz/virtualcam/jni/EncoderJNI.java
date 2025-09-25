package com.sandyz.virtualcam.jni;

import android.annotation.SuppressLint;

import com.sandyz.virtualcam.HookMain;

import kotlin.jvm.Synchronized;

import static com.sandyz.virtualcam.utils.HookUtilsKt.xLog;

/**
 * @author zhangzhe
 * @date 2021/3/22
 * @description
 */

@SuppressLint("UnsafeDynamicallyLoadedCode")
public class EncoderJNI {

    static {
        String path = HookMain.Companion.getModulePath() + "/lib/arm64/libencoder.so";
        xLog("[EncoderJNI.<clinit>] Attempting to load encoder library from " + path);
        try {
            System.load(path);
            xLog("[EncoderJNI.<clinit>] Successfully loaded encoder library");
        } catch (Throwable throwable) {
            xLog("[EncoderJNI.<clinit>] Failed to load encoder library: " + throwable);
            throw throwable;
        }
    }

    @Synchronized
    public static native byte[] encodeYUV420SP(int[] argb, int width, int height);
}
