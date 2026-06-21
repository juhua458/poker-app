package com.pokerhelper.app

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * V2.9.39: 快捷设置瓷砖 — 下拉通知栏顶部一键截屏
 * 用户下拉通知栏即可看到"截屏"瓷砖，点击直接触发截图识别
 */
class CaptureTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (FloatingService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (FloatingService.isRunning) {
            // 发送截屏广播，FloatingService接收后触发截图
            val intent = Intent(FloatingService.ACTION_CAPTURE)
            intent.setPackage(packageName)
            sendBroadcast(intent)
        } else {
            // 服务未运行，打开App主界面启动服务
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityAndCollapse(launchIntent)
        }
    }
}
