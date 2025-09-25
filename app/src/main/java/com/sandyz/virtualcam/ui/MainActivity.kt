package com.sandyz.virtualcam.ui

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.sandyz.virtualcam.R
import java.io.File

/**
 * 逻辑全都被注释了，现在这个Activity还没什么用
 * 因为之前设置url的方式有问题（权限）
 *
 * 待设计一个方便配置各个app虚拟摄像头视频的ui界面
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val pathTextView = findViewById<TextView>(R.id.tv_path)
        val pathHintView = findViewById<TextView>(R.id.textView6)

        val videoPath = getExternalFilesDir(null)?.let { baseDir ->
            File(baseDir, "Camera1${File.separator}virtual.mp4").absolutePath
        }

        if (videoPath != null) {
            pathHintView.text = getString(R.string.virtual_camera_path_hint)
            pathTextView.text = videoPath
        } else {
            pathHintView.text = getString(R.string.virtual_camera_path_unavailable)
            pathTextView.text = ""
        }

        val surfaceView = findViewById<SurfaceView>(R.id.surfaceView)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                holder.lockCanvas()?.let {
                    it.drawColor(0xffff0000.toInt())
                    it.drawText("Hello World", 100f, 100f, TextView(this@MainActivity).paint)
                    holder.unlockCanvasAndPost(it)
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
            }
        })

    }
}
