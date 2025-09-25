package com.sandyz.virtualcam.ui

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sandyz.virtualcam.R
import com.sandyz.virtualcam.utils.xLog
import java.io.File

/**
 * 逻辑全都被注释了，现在这个Activity还没什么用
 * 因为之前设置url的方式有问题（权限）
 *
 * 待设计一个方便配置各个app虚拟摄像头视频的ui界面
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        xLog("[MainActivity.onCreate] savedInstanceState=$savedInstanceState")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        xLog("[MainActivity.onCreate] layout inflated")

        val pathTextView = findViewById<TextView>(R.id.tv_path)
        val pathHintView = findViewById<TextView>(R.id.textView6)

        val videoPath = getExternalFilesDir(null)?.let { baseDir ->
            xLog("[MainActivity.onCreate] externalFilesDir=$baseDir")
            File(baseDir, "Camera1${File.separator}virtual.mp4").absolutePath
        }

        if (videoPath != null) {
            pathHintView.text = getString(R.string.virtual_camera_path_hint)
            pathTextView.text = videoPath
            xLog("[MainActivity.onCreate] videoPath available=$videoPath")
        } else {
            pathHintView.text = getString(R.string.virtual_camera_path_unavailable)
            pathTextView.text = ""
            xLog("[MainActivity.onCreate] videoPath unavailable")
        }

        val surfaceView = findViewById<SurfaceView>(R.id.surfaceView)
        xLog("[MainActivity.onCreate] surfaceView=$surfaceView holder=${surfaceView.holder}")
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                xLog("[MainActivity.SurfaceCallback] surfaceCreated holder=$holder")
                holder.lockCanvas()?.let {
                    xLog("[MainActivity.SurfaceCallback] lockCanvas success canvas=$it")
                    it.drawColor(0xffff0000.toInt())
                    it.drawText("Hello World", 100f, 100f, TextView(this@MainActivity).paint)
                    holder.unlockCanvasAndPost(it)
                    xLog("[MainActivity.SurfaceCallback] unlockCanvasAndPost invoked")
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                xLog("[MainActivity.SurfaceCallback] surfaceChanged holder=$holder format=$format width=$width height=$height")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                xLog("[MainActivity.SurfaceCallback] surfaceDestroyed holder=$holder")
            }
        })

        xLog("[MainActivity.onCreate] surface callback attached")
    }
}

