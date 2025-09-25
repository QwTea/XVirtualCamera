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
        xLog("[PlayIjk.play] 请求开始播放，virtualSurface: $vSurface, ijkMediaPlayer: $ijkMP thread=${Thread.currentThread().name}")
        if (vSurface == null) {
            xLog("[PlayIjk.play] 播放失败，virtualSurface为空！")
            toast(HookUtils.app, "播放失败！", Toast.LENGTH_SHORT)
            return
        } else if (ijkMP == null) {
            xLog("[PlayIjk.play] 播放失败，ijkMediaPlayer为空！")
            toast(HookUtils.app, "播放失败！", Toast.LENGTH_SHORT)
            return
        }
        val baseDir = HookUtils.app?.getExternalFilesDir(null)
        if (baseDir == null) {
            toast(HookUtils.app, "无法访问外部文件目录！", Toast.LENGTH_SHORT)
            xLog("[PlayIjk.play] 无法访问外部文件目录！app=${HookUtils.app}")
            return
        }
        xLog("[PlayIjk.play] externalFilesDir=$baseDir")
        val cameraDir = File(baseDir, "Camera1")
        if (!cameraDir.exists()) {
            xLog("[PlayIjk.play] Camera1目录不存在，尝试创建 ${cameraDir.absolutePath}")
        }
        if (!cameraDir.exists() && !cameraDir.mkdirs()) {
            toast(HookUtils.app, "创建Camera1目录失败！", Toast.LENGTH_SHORT)
            xLog("[PlayIjk.play] 创建Camera1目录失败！${cameraDir.absolutePath}")
            return
        } else {
            xLog("[PlayIjk.play] Camera1目录就绪=${cameraDir.absolutePath}")
        }
        val virtualFile = File(cameraDir, "virtual.mp4")
        if (!virtualFile.exists()) {
            val message = "请将virtual.mp4放置在files/Camera1/目录 (${cameraDir.absolutePath})"
            toast(HookUtils.app, message, Toast.LENGTH_LONG)
            xLog("[PlayIjk.play] $message")
            return
        }
        val urlStr = virtualFile.absolutePath
        xLog("[PlayIjk.play] 虚拟视频文件存在 fileSize=${virtualFile.length()} modified=${virtualFile.lastModified()}")
        toast(HookUtils.app, "播放本地视频：$urlStr", Toast.LENGTH_LONG)
        xLog("[PlayIjk.play] 播放本地视频：$urlStr")
        vSurface.let {
            xLog("[PlayIjk.play] 为播放器绑定Surface")
            ijkMP.setSurface(it)
            ijkMP.isLooping = true
            xLog("[PlayIjk.play] 配置ijkMediaPlayer looping=${ijkMP.isLooping}")
            ijkMP.dataSource = urlStr
            xLog("[PlayIjk.play] 设置数据源完成，准备异步播放")
            ijkMP.prepareAsync()
            ijkMP.setOnPreparedListener {
                xLog("[PlayIjk.play] onPrepared 回调，开始播放")
                ijkMP.start()
            }
        }
        toast(HookUtils.app, "开始播放，ijk:$ijkMP，surface:$vSurface url:$urlStr", Toast.LENGTH_SHORT)
        xLog("[PlayIjk.play] 开始播放，ijk:$ijkMP，surface:$vSurface url:$urlStr")
        xLog("[PlayIjk.play] currentActivity: ${HookUtils.getActivities()}, currentTopActivity: ${HookUtils.getTopActivity()}")
    }
}
