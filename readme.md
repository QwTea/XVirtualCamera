# XVirtualCamera（Xposed虚拟摄像头插件）
[English Version Readme](https://github.com/sandyz987/XVirtualCamera/blob/master/readme_en.md)

A virtual camera module based on the Xposed framework suitable for Android 9.0 and above, written in Kotlin.

适用于Android9.0以上的、基于Xposed框架的**虚拟摄像头**插件。插件由Kotlin编写。

支持替换虚拟摄像头内容为“直播推流 或 视频“，支持的协议有：http、rtsp、rtmp、rtp等。

**使用本开源项目产生的一切后果请自行承担。**

[![Download](https://img.shields.io/github/v/release/sandyz987/XVirtualCamera?label=Download)](https://github.com/sandyz987/XVirtualCamera/releases/latest)
[![Stars](https://img.shields.io/github/stars/sandyz987/XVirtualCamera?label=Stars)](https://github.com/sandyz987/XVirtualCamera)
[![License](https://img.shields.io/github/license/sandyz987/XVirtualCamera?label=License)](https://choosealicense.com/licenses/gpl-3.0/)

<img src="preview.gif" width="300px"><img src="preview.png" width="300px">



## 插件作用范围

可以将虚拟视频作用在如下地方：

- B站预览、录像
- 抖音预览、录像、直播
- 快手预览、录像、直播[todo：目前新版本存在兼容性问题，容易闪退，待解决]
- 微信视频号预览、录像
- 小米原生相机预览
- WhatsApp视频通话[Sep 18 2024新增]

其他地方插件均不生效（因为未测试，为了兼容性就禁用了），如需使用请修改源代码并重新编译。



## 使用方法

可以以**本地视频**作为虚拟摄像头的视频源。

- 1、请手动到Xposed管理器打开插件并且选择作用域APP。
- 2、在设备上创建目录`/storage/emulated/0/Android/data/[你要使用虚拟摄像头的应用包名]/files/Camera1/`（若不存在）。
- 3、将需要播放的视频命名为`virtual.mp4`并放入上述目录，插件只会读取此文件作为虚拟摄像头内容。

**推流内容推荐方案：** 使用 OBS 录制到本地 mp4 文件，然后通过数据线、ADB push 或网络同步的方式将录制好的文件覆盖为上述目录中的`virtual.mp4`。这样即可用实时录制内容更新虚拟摄像头画面。


## 免责声明
仅供学习交流使用，或提供一种上传视频的方式。请勿播放其他人的一切视频/作品。**使用本开源项目产生的一切后果请自行承担。**



## 感谢

播放器能支持多种数据源，是因为基于[bilibili/ijkplayer](https://github.com/bilibili/ijkplayer)开发。



## 下载
[Github Release](https://github.com/sandyz987/XVirtualCamera/releases/latest)



## License

This project is licensed under the [GNU General Public Licence 3.0](https://choosealicense.com/licenses/gpl-3.0/).



## 赞助

维护它需要花费我的空余时间，希望你能给予一些赞助~，感激不尽 : )

<img src="zfb.jpg" width="300px"><img src="wx.jpg" width="300px">
