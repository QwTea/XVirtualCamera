package com.sandyz.virtualcam.utils

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.annotation.DrawableRes

/**
 * 测试用
 */
object BitmapLoader {
    fun decodeBitmapFromResource(
        res: Resources?,
        @DrawableRes resId: Int,
        decodeWidth: Int,
        decodeHeight: Int
    ): Bitmap? {
        xLog("[BitmapLoader.decodeBitmapFromResource] res=$res resId=$resId targetWidth=$decodeWidth targetHeight=$decodeHeight")
        if (resId == 0) {
            xLog("[BitmapLoader.decodeBitmapFromResource] invalid resId, returning null")
            return null
        }
        if (decodeHeight <= 0 || decodeWidth <= 0) {
            xLog("[BitmapLoader.decodeBitmapFromResource] invalid target size, returning null")
            return null
        }
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true //预加载
        BitmapFactory.decodeResource(res, resId, options)
        val imgWidth = options.outWidth //要加载的图片的宽
        val imgHeight = options.outHeight //要加载的图片的高
        var inSampleSize = 1
        if (imgWidth > decodeWidth || imgHeight > decodeHeight) {
            val halfWidth = imgWidth / 2
            val halfHeight = imgHeight / 2
            while (halfWidth / inSampleSize >= decodeWidth &&
                halfHeight / inSampleSize >= decodeHeight
            ) {
                inSampleSize *= 2
            }
        }
        xLog("[BitmapLoader.decodeBitmapFromResource] calculated inSampleSize=$inSampleSize sourceWidth=$imgWidth sourceHeight=$imgHeight")
        options.inJustDecodeBounds = false
        options.inSampleSize = inSampleSize
        val bitmap = BitmapFactory.decodeResource(res, resId, options) ?: return null
        xLog("[BitmapLoader.decodeBitmapFromResource] decoded bitmap size=${bitmap.width}x${bitmap.height}")
        val matrix = Matrix().apply {
            postScale((decodeWidth / bitmap.width.toFloat()), (decodeHeight / bitmap.height.toFloat()))
        }
        val scaled = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        xLog("[BitmapLoader.decodeBitmapFromResource] scaled bitmap size=${scaled.width}x${scaled.height}")
        return scaled
    }

    fun decodeBitmapFromResourceByWidth(
        res: Resources?,
        @DrawableRes resId: Int,
        decodeWidth: Int
    ): Bitmap? {
        xLog("[BitmapLoader.decodeBitmapFromResourceByWidth] resId=$resId targetWidth=$decodeWidth")
        if (resId == 0) {
            xLog("[BitmapLoader.decodeBitmapFromResourceByWidth] invalid resId, returning null")
            return null
        }
        if (decodeWidth <= 0) {
            xLog("[BitmapLoader.decodeBitmapFromResourceByWidth] invalid target width, returning null")
            return null
        }
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true //预加载
        BitmapFactory.decodeResource(res, resId, options)
        val imgWidth = options.outWidth //要加载的图片的宽
        val imgHeight = options.outHeight //要加载的图片的高
        val heightDivWidth = imgHeight / imgWidth.toFloat()
        val decodeHeight = decodeWidth * heightDivWidth
        var inSampleSize = 1
        if (imgWidth > decodeWidth || imgHeight > decodeHeight) {
            val halfWidth = imgWidth / 2
            val halfHeight = imgHeight / 2
            while (halfWidth / inSampleSize >= decodeWidth &&
                halfHeight / inSampleSize >= decodeHeight
            ) {
                inSampleSize *= 2
            }
        }
        xLog("[BitmapLoader.decodeBitmapFromResourceByWidth] calculated inSampleSize=$inSampleSize decodeHeight=$decodeHeight")
        options.inJustDecodeBounds = false
        options.inSampleSize = inSampleSize
        val bitmap = BitmapFactory.decodeResource(res, resId, options) ?: return null
        xLog("[BitmapLoader.decodeBitmapFromResourceByWidth] decoded bitmap size=${bitmap.width}x${bitmap.height}")
        val matrix = Matrix().apply {
            postScale((decodeWidth.toFloat() / bitmap.width), (decodeHeight / bitmap.height))
        }
        val scaled = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        xLog("[BitmapLoader.decodeBitmapFromResourceByWidth] scaled bitmap size=${scaled.width}x${scaled.height}")
        return scaled
    }

    fun decodeBitmapFromResourceByHeight(
        res: Resources?,
        @DrawableRes resId: Int,
        decodeHeight: Int
    ): Bitmap? {
        xLog("[BitmapLoader.decodeBitmapFromResourceByHeight] resId=$resId targetHeight=$decodeHeight")
        if (resId == 0) {
            xLog("[BitmapLoader.decodeBitmapFromResourceByHeight] invalid resId, returning null")
            return null
        }
        if (decodeHeight <= 0) {
            xLog("[BitmapLoader.decodeBitmapFromResourceByHeight] invalid target height, returning null")
            return null
        }
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true //预加载
        BitmapFactory.decodeResource(res, resId, options)
        val imgWidth = options.outWidth //要加载的图片的宽
        val imgHeight = options.outHeight //要加载的图片的高
        val widthDivHeight = imgWidth / imgHeight.toFloat()
        val decodeWidth = decodeHeight * widthDivHeight
        var inSampleSize = 1
        if (imgWidth > decodeWidth || imgHeight > decodeHeight) {
            val halfWidth = imgWidth / 2
            val halfHeight = imgHeight / 2
            while (halfWidth / inSampleSize >= decodeWidth &&
                halfHeight / inSampleSize >= decodeHeight
            ) {
                inSampleSize *= 2
            }
        }
        xLog("[BitmapLoader.decodeBitmapFromResourceByHeight] calculated inSampleSize=$inSampleSize decodeWidth=$decodeWidth")
        options.inJustDecodeBounds = false
        options.inSampleSize = inSampleSize
        val bitmap = BitmapFactory.decodeResource(res, resId, options) ?: return null
        xLog("[BitmapLoader.decodeBitmapFromResourceByHeight] decoded bitmap size=${bitmap.width}x${bitmap.height}")
        val matrix = Matrix().apply {
            postScale((decodeWidth / bitmap.width), (decodeHeight / bitmap.height.toFloat()))
        }
        val scaled = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        xLog("[BitmapLoader.decodeBitmapFromResourceByHeight] scaled bitmap size=${scaled.width}x${scaled.height}")
        return scaled
    }

    fun resizeBitmap(bitmapSrc: Bitmap?, targetWidth: Int, targetHeight: Int): Bitmap? {
        xLog("[BitmapLoader.resizeBitmap] source=$bitmapSrc targetWidth=$targetWidth targetHeight=$targetHeight")
        val bitmap = bitmapSrc ?: return null
        val matrix = Matrix().apply {
            postScale((targetWidth / bitmap.width.toFloat()), (targetHeight / bitmap.height.toFloat()))
        }
        val scaled = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        xLog("[BitmapLoader.resizeBitmap] scaled bitmap size=${scaled.width}x${scaled.height}")
        return scaled
    }
}
