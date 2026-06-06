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
import android.os.HandlerThread
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
            private set
        private const val CHANNEL_ID = "poker_screenshot"
        private const val NOTIFICATION_ID = 1
    }

    private var mediaProjection: MediaProjection? = null
    private val handler = Handler(Looper.getMainLooper())
    private var resultCode: Int = 0
    private var resultData: Intent? = null
    private var screenWidth = 540
    private var screenHeight = 1200
    private var screenDensity = 160
    private var isCapturing = false
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        captureThread = HandlerThread("CaptureThread").apply { start() }
        captureHandler = Handler(captureThread!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> {
                stopSelf()
                return START_NOT_STICKY
            }
            "CAPTURE_ONCE" -> {
                performCaptureOnce()
                return START_STICKY
            }
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

        initMediaProjection()
        isRunning = true
        return START_STICKY
    }

    /**
     * 只初始化MediaProjection，不创建VirtualDisplay
     * 这样游戏不会检测到录屏，不会黑屏
     */
    private fun initMediaProjection() {
        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, resultData!!)
            
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    lastError = "MediaProjection被停止(可回主界面重新授权)"
                    mediaProjection = null
                }
            }, handler)

            lastError = ""
        } catch (e: Exception) {
            lastError = "MediaProjection启动失败: ${e.message}"
            e.printStackTrace()
            isRunning = false
            stopSelf()
        }
    }

    /**
     * ★ 核心改进：按需单次截屏 ★
     * 流程：创建VirtualDisplay → 等500ms截一帧 → 立即释放VirtualDisplay
     * VirtualDisplay只存在<1秒，游戏检测不到，不会黑屏
     */
    fun performCaptureOnce() {
        if (isCapturing) {
            return // 防止重复触发
        }
        if (mediaProjection == null) {
            lastError = "MediaProjection未就绪(可回主界面重新授权)"
            return
        }

        isCapturing = true
        var virtualDisplay: VirtualDisplay? = null
        var imageReader: ImageReader? = null

        captureHandler?.post {
            try {
                // 获取屏幕尺寸
                val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bounds = wm.currentWindowMetrics.bounds
                    screenWidth = bounds.width() * 3 / 4
                    screenHeight = bounds.height() * 3 / 4
                    screenDensity = wm.currentWindowMetrics.density.toInt() * 3 / 4
                } else {
                    val metrics = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.getRealMetrics(metrics)
                    screenWidth = metrics.widthPixels * 3 / 4
                    screenHeight = metrics.heightPixels * 3 / 4
                    screenDensity = metrics.densityDpi * 3 / 4
                }

                // 创建ImageReader
                imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)

                // 创建VirtualDisplay
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "PokerCaptureOnce",
                    screenWidth, screenHeight, screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface,
                    null, null
                )

                // 等500ms让VirtualDisplay产出第一帧
                Thread.sleep(500)

                // 读取帧
                val image: Image? = imageReader?.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * screenWidth

                    val bitmap = Bitmap.createBitmap(
                        screenWidth + rowPadding / pixelStride,
                        screenHeight, Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    image.close()

                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
                    latestScreenshot = stream.toByteArray()
                    bitmap.recycle()
                    
                    captureCount++
                    lastCaptureTime = System.currentTimeMillis()
                    lastError = ""
                } else {
                    // 第一帧没拿到，再等300ms试一次
                    Thread.sleep(300)
                    val image2: Image? = imageReader?.acquireLatestImage()
                    if (image2 != null) {
                        val planes = image2.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride
                        val rowPadding = rowStride - pixelStride * screenWidth

                        val bitmap = Bitmap.createBitmap(
                            screenWidth + rowPadding / pixelStride,
                            screenHeight, Bitmap.Config.ARGB_8888
                        )
                        bitmap.copyPixelsFromBuffer(buffer)
                        image2.close()

                        val stream = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
                        latestScreenshot = stream.toByteArray()
                        bitmap.recycle()
                        
                        captureCount++
                        lastCaptureTime = System.currentTimeMillis()
                        lastError = ""
                    } else {
                        lastError = "按需截屏: 未获取到帧(可重试)"
                    }
                }
            } catch (e: Exception) {
                lastError = "按需截屏失败: ${e.message}"
                e.printStackTrace()
            } finally {
                // ★ 立即释放VirtualDisplay，游戏不会检测到 ★
                try { virtualDisplay?.release() } catch (_: Exception) {}
                try { imageReader?.close() } catch (_: Exception) {}
                isCapturing = false
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        captureThread?.quitSafely()
        mediaProjection?.stop()
        mediaProjection = null
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
                .setContentText("截屏服务就绪(按需截屏)")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("🃏 扑克AI助手")
                .setContentText("截屏服务就绪")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true)
                .build()
        }
    }
}
