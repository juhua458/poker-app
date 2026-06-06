package com.pokerhelper.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.io.ByteArrayOutputStream

/**
 * ★ V1.9 核心新增：无障碍截图服务 ★
 * 
 * 替代 MediaProjection 截图方案，解决游戏黑屏问题：
 * - AccessibilityService.takeScreenshot() 不走 MediaProjection
 * - 游戏检测不到录屏令牌 → 不会黑屏
 * - 双轨制：无障碍优先，MediaProjection降级
 * 
 * 截图结果统一存入 ScreenCaptureService.latestScreenshot
 * FloatingService 通过 onScreenshotReady 回调获知截图完成
 */
class PokerAccessibilityService : AccessibilityService() {

    companion object {
        var isRunning = false
            private set
        
        /** 截图完成回调：参数=true截图成功，false失败需降级 */
        var onScreenshotReady: ((Boolean) -> Unit)? = null

        private var instance: PokerAccessibilityService? = null

        fun isServiceRunning(): Boolean = instance != null

        /**
         * 发起无障碍截图
         * 结果通过 onScreenshotReady 回调通知
         */
        fun captureScreen() {
            val svc = instance
            if (svc == null) {
                onScreenshotReady?.invoke(false)
                return
            }
            svc.performCapture()
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isRunning = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不需要处理无障碍事件，此服务仅用于截图
    }

    override fun onInterrupt() {
        // 不需要处理中断
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
    }

    /**
     * ★ 核心方法：无障碍截图 ★
     * 
     * AccessibilityService.takeScreenshot() 走系统无障碍通道
     * 不创建 MediaProjection / VirtualDisplay → 游戏检测不到
     * 
     * 关键注意事项（风险#2，高严重度）：
     * HardwareBuffer → Bitmap 必须先 copy() 再 close()
     * 否则截图空白
     */
    @android.annotation.SuppressLint("NewApi")
    private fun performCapture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // API 30以下不支持takeScreenshot，回退
            ScreenCaptureService.lastError = "无障碍截图需Android 11+"
            handler.post { onScreenshotReady?.invoke(false) }
            return
        }

        try {
            // displayId=0 表示主屏幕
            @Suppress("DEPRECATION")
            val displayId = android.view.Display.DEFAULT_DISPLAY

            takeScreenshot(
                displayId,
                { runnable -> handler.post(runnable) },
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.Screenshot) {
                        try {
                            val hardwareBuffer = screenshot.hardwareBuffer

                            // ★★★ 关键：必须先copy再close，否则截图空白 ★★★
                            // 1. Wrap HardwareBuffer → Hardware Bitmap
                            val hardwareBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                // API 34+: 带 ColorSpace 的版本
                                Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                            } else {
                                // API 30-33: 不带 ColorSpace
                                @Suppress("DEPRECATION")
                                Bitmap.wrapHardwareBuffer(hardwareBuffer)
                            }

                            // 2. 复制为 ARGB_8888 软件Bitmap（独立于HardwareBuffer）
                            val softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)

                            // 3. ★ 安全关闭硬件资源（copy之后才能close）★
                            hardwareBitmap?.recycle()
                            hardwareBuffer.close()
                            screenshot.close()

                            if (softwareBitmap != null) {
                                // 4. 压缩为JPEG
                                val stream = ByteArrayOutputStream()
                                softwareBitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
                                val jpegBytes = stream.toByteArray()
                                softwareBitmap.recycle()

                                // 5. ★ 统一存入 ScreenCaptureService.latestScreenshot ★
                                //    FloatingService 和 HttpServerService 都从这里读取
                                ScreenCaptureService.latestScreenshot = jpegBytes
                                ScreenCaptureService.captureCount++
                                ScreenCaptureService.lastCaptureTime = System.currentTimeMillis()
                                ScreenCaptureService.lastError = ""

                                handler.post { onScreenshotReady?.invoke(true) }
                            } else {
                                // HardwareBuffer → Bitmap 失败，需降级到 MediaProjection
                                ScreenCaptureService.lastError = "无障碍截图: Bitmap转换失败(将降级到MediaProjection)"
                                handler.post { onScreenshotReady?.invoke(false) }
                            }
                        } catch (e: Exception) {
                            ScreenCaptureService.lastError = "无障碍截图处理失败: ${e.message}"
                            handler.post { onScreenshotReady?.invoke(false) }
                        }
                    }

                    override fun onError(errorCode: Int) {
                        val errMsg = when (errorCode) {
                            1 -> "内部错误"
                            2 -> "无无障碍权限"
                            3 -> "无效显示ID"
                            4 -> "窗口内容变化"
                            else -> "未知错误($errorCode)"
                        }
                        ScreenCaptureService.lastError = "无障碍截图失败: $errMsg"
                        handler.post { onScreenshotReady?.invoke(false) }
                    }
                }
            )
        } catch (e: Exception) {
            ScreenCaptureService.lastError = "无障碍截图异常: ${e.message}"
            handler.post { onScreenshotReady?.invoke(false) }
        }
    }
}
