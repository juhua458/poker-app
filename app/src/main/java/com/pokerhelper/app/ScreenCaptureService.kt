package com.pokerhelper.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

class ScreenCaptureService : Service() {

    companion object {
        var isRunning = false
        var latestScreenshot: ByteArray? = null
            private set
        var captureCount: Int = 0
            private set
        var lastCaptureTime: Long = 0
            private set
        var lastError: String = ""
            private set
        var lastChipStatus: String = ""
            internal set
        var onCaptureComplete: (() -> Unit)? = null
        private const val CHANNEL_ID = "poker_screenshot"
        private const val NOTIFICATION_ID = 1
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var resultCode: Int = 0
    private var resultData: Intent? = null
    private val handler = Handler(Looper.getMainLooper())
    private var screenWidth = 540
    private var screenHeight = 1200
    private var screenDensity = 160
    private var consecutiveFails = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == "RESET_CHIPS") {
            ChipTracker.reset()
            lastChipStatus = "已重置"
            return START_STICKY
        }

        if (intent?.action == "CAPTURE_ONCE") {
            handler.post { captureAndAnalyze() }
            return START_STICKY
        }

        if (isRunning && mediaProjection != null) {
            return START_STICKY
        }

        resultCode = intent?.getIntExtra("RESULT_CODE", 0) ?: 0
        resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("RESULT_DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("RESULT_DATA")
        }

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startScreenCapture()
        isRunning = true

        return START_STICKY
    }

    private fun startScreenCapture() {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData!!)
            
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    lastError = "MediaProjection被停止(可能需要重新授权)"
                }
            }, handler)

            setupVirtualDisplay()

            lastError = ""
            captureCount = 0

            // V1.3: 按需截屏模式 - 轻量保活（只丢帧不分析，省电省资源）
            handler.postDelayed(object : Runnable {
                override fun run() {
                    if (isRunning) {
                        try {
                            val img = imageReader?.acquireLatestImage()
                            img?.close()
                        } catch (_: Exception) {}
                        handler.postDelayed(this, 3000)
                    }
                }
            }, 500)
        } catch (e: Exception) {
            lastError = "启动失败: ${e.message}"
            e.printStackTrace()
            isRunning = false
            stopSelf()
        }
    }

    private fun setupVirtualDisplay() {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val realW: Int
        val realH: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            realW = bounds.width()
            realH = bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            realW = metrics.widthPixels
            realH = metrics.heightPixels
        }
        val newWidth = realW * 3 / 4
        val newHeight = realH * 3 / 4
        val newDensity = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.density.toInt() * 3 / 4
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics.densityDpi * 3 / 4
        }

        screenWidth = newWidth
        screenHeight = newHeight
        screenDensity = newDensity

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "PokerScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null, null
        )
    }

    private fun captureAndAnalyze() {
        try {
            val image: Image? = imageReader?.acquireLatestImage()
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                var bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight, Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()

                // 检查旋转
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val realW: Int
                val realH: Int
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bounds = wm.currentWindowMetrics.bounds
                    realW = bounds.width()
                    realH = bounds.height()
                } else {
                    val dm = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.getRealMetrics(dm)
                    realW = dm.widthPixels
                    realH = dm.heightPixels
                }
                val isRealLandscape = realW > realH
                val isCaptureLandscape = bitmap.width > bitmap.height

                if (isRealLandscape != isCaptureLandscape) {
                    try {
                        try { virtualDisplay?.release() } catch (_: Exception) {}
                        try { imageReader?.close() } catch (_: Exception) {}

                        screenWidth = realW * 3 / 4
                        screenHeight = realH * 3 / 4
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            screenDensity = wm.currentWindowMetrics.density.toInt() * 3 / 4
                        } else {
                            val m = DisplayMetrics()
                            @Suppress("DEPRECATION")
                            wm.defaultDisplay.getRealMetrics(m)
                            screenDensity = m.densityDpi * 3 / 4
                        }

                        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
                        virtualDisplay = mediaProjection?.createVirtualDisplay(
                            "PokerScreenCapture",
                            screenWidth, screenHeight, screenDensity,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            imageReader?.surface,
                            null, null
                        )
                        lastError = "旋转重建VD ${screenWidth}x${screenHeight}"
                    } catch (re: Exception) {
                        lastError = "旋转重建失败: ${re.message}"
                    }
                    bitmap.recycle()
                    return
                }

                // 检查bitmap方向（Android 15旋转修正）
                if (bitmap.width < bitmap.height && isRealLandscape) {
                    val matrix = android.graphics.Matrix()
                    matrix.postRotate(-90f)
                    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    bitmap.recycle()
                    bitmap = rotated
                }

                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
                val jpegData = stream.toByteArray()
                latestScreenshot = jpegData
                bitmap.recycle()
                
                captureCount++
                lastCaptureTime = System.currentTimeMillis()
                consecutiveFails = 0
                lastError = ""
                
                // 触发按需截屏回调
                onCaptureComplete?.invoke()
                
                // 异步OCR分析筹码 + API视觉识别
                Thread {
                    try {
                        val frame = ChipTracker.analyzeScreenshot(jpegData)
                        if (frame != null) {
                            lastChipStatus = "${frame.tablePlayerCount}人 | 活跃${frame.activePlayerCount} | 下注${frame.totalBetAmount}"
                        }
                    } catch (e: Exception) {
                        lastChipStatus = "OCR错误: ${e.message}"
                    }
                    
                    // V1.3: 如果有API Key，用视觉模型识别牌面
                    if (VisionApiClient.apiKey.isNotEmpty()) {
                        try {
                            lastChipStatus = (lastChipStatus ?: "") + " | API分析中..."
                            val visionResult = VisionApiClient.analyzeScreenshot(jpegData)
                            if (visionResult != null) {
                                lastChipStatus = "${visionResult.totalPlayers}人 | ${visionResult.street} | ${visionResult.holeCards.map { it.rank + it.suit }.joinToString(" ")}"
                            } else {
                                lastChipStatus = (lastChipStatus ?: "").replace(" | API分析中...", "") + " | API:" + VisionApiClient.lastError
                            }
                        } catch (e: Exception) {
                            lastChipStatus = (lastChipStatus ?: "") + " | API错误"
                        }
                    }
                }.start()
                
            } else {
                consecutiveFails++
                if (consecutiveFails > 10) {
                    lastError = "连续${consecutiveFails}次未获取到帧"
                }
            }
        } catch (e: Exception) {
            consecutiveFails++
            lastError = "截屏错误: ${e.message}"
        }
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        latestScreenshot = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "扑克截屏", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "后台截屏服务运行中" }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("🃏 扑克AI助手")
                .setContentText("截屏+OCR服务运行中")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("🃏 扑克AI助手")
                .setContentText("截屏+OCR服务运行中")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
        }
    }
}
