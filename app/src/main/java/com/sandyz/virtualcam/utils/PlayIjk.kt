package com.sandyz.virtualcam.utils

import android.view.Surface
import android.widget.Toast
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import java.io.File

/**
 *@author sandyz987
 *@date 2023/11/27
 *@description
 */

object PlayIjk {
    /**
     * 播放视频总逻辑
     * vSurface: 要播放虚拟视频的surface
     * ijkMP: 播放器
     */
    fun play(vSurface: Surface?, ijkMP: IjkMediaPlayer?) {
        xLog("请求开始播放，virtualSurface: $vSurface, ijkMediaPlayer: $ijkMP")
        if (vSurface == null) {
            xLog("播放失败，virtualSurface为空！")
            toast(HookUtils.app, "播放失败！", Toast.LENGTH_SHORT)
            return
        } else if (ijkMP == null) {
            xLog("播放失败，ijkMediaPlayer为空！")
            toast(HookUtils.app, "播放失败！", Toast.LENGTH_SHORT)
            return
        }
        val baseDir = HookUtils.app?.getExternalFilesDir(null)
        if (baseDir == null) {
            toast(HookUtils.app, "无法访问外部文件目录！", Toast.LENGTH_SHORT)
            xLog("无法访问外部文件目录！")
            return
        }
        val cameraDir = File(baseDir, "Camera1")
        if (!cameraDir.exists() && !cameraDir.mkdirs()) {
            toast(HookUtils.app, "创建Camera1目录失败！", Toast.LENGTH_SHORT)
            xLog("创建Camera1目录失败！${cameraDir.absolutePath}")
            return
        }
        val virtualFile = File(cameraDir, "virtual.mp4")
        if (!virtualFile.exists()) {
            val message = "请将virtual.mp4放置在files/Camera1/目录 (${cameraDir.absolutePath})"
            toast(HookUtils.app, message, Toast.LENGTH_LONG)
            xLog(message)
            return
        }
        val urlStr = virtualFile.absolutePath
        toast(HookUtils.app, "播放本地视频：$urlStr", Toast.LENGTH_LONG)
        xLog("播放本地视频：$urlStr")
        vSurface.let {
            ijkMP.setSurface(it)
            ijkMP.isLooping = true
            ijkMP.dataSource = urlStr
            ijkMP.prepareAsync()
            ijkMP.setOnPreparedListener {
                ijkMP.start()
            }
        }
        toast(HookUtils.app, "开始播放，ijk:$ijkMP，surface:$vSurface url:$urlStr", Toast.LENGTH_SHORT)
        xLog("开始播放，ijk:$ijkMP，surface:$vSurface url:$urlStr")
        xLog("currentActivity: ${HookUtils.getActivities()}, currentTopActivity: ${HookUtils.getTopActivity()}")
    }
}