package com.pokerhelper.app

/**
 * ★ V2.3: 彻底移除MediaProjection ★
 * 
 * 此文件仅作为共享数据存储（companion object），
 * 供 FloatingService / HttpServerService / ScreenOptService 读写截图数据。
 * 
 * ★ 永久规则：绝不引入MediaProjection/VirtualDisplay，游戏类App截图只用AccessibilityService ★
 * 任何MediaProjection代码都是定时炸弹，即使用户要求也不加。
 */
class ScreenCaptureService : android.app.Service() {

    companion object {
        var isRunning = false
        var latestScreenshot: ByteArray? = null
            internal set
        var captureCount: Int = 0
            internal set
        var lastCaptureTime: Long = 0
            internal set
        var lastError: String = ""
            internal set
        var lastChipStatus: String = ""
    }

    override fun onBind(intent: android.content.Intent?) = null

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        // V2.3: 此Service不再启动，仅保留类定义供companion object使用
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        stopSelf()
    }
}
