package com.pokerhelper.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.content.pm.ServiceInfo
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream

class HttpServerService : Service() {

    companion object {
        private const val CHANNEL_ID = "poker_http"
        private const val NOTIFICATION_ID = 3
    }

    private var server: NanoHTTPD? = null
    private var pokerHelperHtml: String? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loadPokerHelperHtml(): String {
        if (pokerHelperHtml == null) {
            try {
                val is_ = assets.open("poker_helper.html")
                val reader = java.io.InputStreamReader(is_, "UTF-8")
                pokerHelperHtml = reader.readText()
                reader.close()
            } catch (e: Exception) {
                pokerHelperHtml = "<html><body><h2>策略引擎加载失败</h2><p>${e.message}</p></body></html>"
            }
        }
        return pokerHelperHtml ?: ""
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            server?.stop()
            server = null
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (server == null) {
            server = object : NanoHTTPD(8666) {
                override fun serve(session: IHTTPSession): Response {
                    return when {
                        session.uri == "/" || session.uri == "/poker" || session.uri == "/helper" || session.uri == "/index.html" -> {
                            pokerHelperHtml = null
                            val html = loadPokerHelperHtml()
                            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html).apply {
                                addHeader("Access-Control-Allow-Origin", "*")
                                addHeader("Cache-Control", "no-cache, no-store")
                            }
                        }
                        session.uri == "/api/screenshot" -> {
                            val data = ScreenCaptureService.latestScreenshot
                            if (data != null) {
                                newFixedLengthResponse(
                                    Response.Status.OK,
                                    "image/jpeg",
                                    ByteArrayInputStream(data),
                                    data.size.toLong()
                                ).apply {
                                    addHeader("Access-Control-Allow-Origin", "*")
                                    addHeader("Cache-Control", "no-cache, no-store")
                                }
                            } else {
                                newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "no screenshot yet").apply {
                                    addHeader("Access-Control-Allow-Origin", "*")
                                }
                            }
                        }
                        session.uri == "/api/status" -> {
                            val capture = ScreenCaptureService
                            val timeSinceLast = if (capture.lastCaptureTime > 0) 
                                (System.currentTimeMillis() - capture.lastCaptureTime) / 1000 else -1
                            val panelW = FloatingService.currentPanelWidth
                            val json = JSONObject().apply {
                                put("running", FloatingService.isRunning)
                                put("accessibilityRunning", PokerAccessibilityService.isServiceRunning())
                                put("hasScreenshot", capture.latestScreenshot != null)
                                put("captureCount", capture.captureCount)
                                put("timeSinceLast", timeSinceLast)
                                put("error", capture.lastError)
                                put("panelWidth", panelW)
                                put("version", "2.9.12")
                                put("chipStatus", capture.lastChipStatus)
                            }.toString()
                            newFixedLengthResponse(Response.Status.OK, "application/json", json).apply {
                                addHeader("Access-Control-Allow-Origin", "*")
                                addHeader("Cache-Control", "no-cache, no-store")
                            }
                        }
                        // V1.2 新增：筹码识别状态API
                        session.uri == "/api/chips" -> {
                            val json = ChipTracker.getStatusJson()
                            newFixedLengthResponse(Response.Status.OK, "application/json", json).apply {
                                addHeader("Access-Control-Allow-Origin", "*")
                                addHeader("Cache-Control", "no-cache, no-store")
                            }
                        }
                        // V1.2 新增：语音识别结果提交API
                        session.uri == "/api/voice" && session.method == Method.POST -> {
                            try {
                                val files = HashMap<String, String>()
                                session.parseBody(files)
                                val postData = files["postData"] ?: ""
                                val result = VoiceInputManager.parseVoiceText(postData)
                                val json = VoiceInputManager.toJson(result)
                                newFixedLengthResponse(Response.Status.OK, "application/json", json).apply {
                                    addHeader("Access-Control-Allow-Origin", "*")
                                }
                            } catch (e: Exception) {
                                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", 
                                    """{"error":"${e.message}"}""").apply {
                                    addHeader("Access-Control-Allow-Origin", "*")
                                }
                            }
                        }
                        // V1.2 新增：重置筹码追踪
                        session.uri == "/api/chips/reset" -> {
                            ChipTracker.reset()
                            ScreenCaptureService.lastChipStatus = "已重置"
                            newFixedLengthResponse(Response.Status.OK, "application/json", """{"ok":true}""").apply {
                                addHeader("Access-Control-Allow-Origin", "*")
                            }
                        }
                        // V2.1: 按需截屏+API识别（仅无障碍截图，绝不走MediaProjection）
                        session.uri == "/api/capture" -> {
                            try {
                                if (PokerAccessibilityService.isServiceRunning()) {
                                    val latch = java.util.concurrent.CountDownLatch(1)
                                    var captureSuccess = false
                                    PokerAccessibilityService.onScreenshotReady = { success ->
                                        captureSuccess = success
                                        latch.countDown()
                                    }
                                    PokerAccessibilityService.captureScreen()
                                    latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
                                    val json = JSONObject().apply {
                                        put("ok", ScreenCaptureService.latestScreenshot != null)
                                        put("method", if (captureSuccess) "accessibility" else "failed")
                                        put("chipStatus", ScreenCaptureService.lastChipStatus)
                                        put("captureCount", ScreenCaptureService.captureCount)
                                    }.toString()
                                    newFixedLengthResponse(Response.Status.OK, "application/json", json).apply {
                                        addHeader("Access-Control-Allow-Origin", "*")
                                    }
                                } else {
                                    // V2.1: 无障碍服务未开启 → 返回错误，绝不降级MediaProjection
                                    val json = JSONObject().apply {
                                        put("ok", false)
                                        put("error", "accessibility_not_enabled")
                                        put("message", "请先开启无障碍服务")
                                    }.toString()
                                    newFixedLengthResponse(Response.Status.OK, "application/json", json).apply {
                                        addHeader("Access-Control-Allow-Origin", "*")
                                    }
                                }
                            } catch (e: Exception) {
                                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", 
                                    """{"error":"${e.message}"}""").apply {
                                    addHeader("Access-Control-Allow-Origin", "*")
                                }
                            }
                        }
                        // V2.1: API视觉识别（仅无障碍截图）
                        session.uri == "/api/analyze" -> {
                            try {
                                val screenshot = ScreenCaptureService.latestScreenshot
                                if (screenshot == null) {
                                    // V2.1: 无截图 → 返回错误提示，绝不降级MediaProjection
                                    newFixedLengthResponse(Response.Status.OK, "application/json",
                                        """{"error":"no_screenshot","message":"请先点击🎯截屏"}""").apply {
                                        addHeader("Access-Control-Allow-Origin", "*")
                                    }
                                } else if (VisionApiClient.apiKey.isEmpty()) {
                                    newFixedLengthResponse(Response.Status.OK, "application/json",
                                        """{"error":"no_api_key","message":"请在设置中配置API Key"}""").apply {
                                        addHeader("Access-Control-Allow-Origin", "*")
                                    }
                                } else {
                                    val result = VisionApiClient.analyzeScreenshot(screenshot)
                                    if (result != null) {
                                        newFixedLengthResponse(Response.Status.OK, "application/json",
                                            VisionApiClient.toJson(result)).apply {
                                            addHeader("Access-Control-Allow-Origin", "*")
                                        }
                                    } else {
                                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                                            """{"error":"${VisionApiClient.lastError}"}""").apply {
                                            addHeader("Access-Control-Allow-Origin", "*")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                                    """{"error":"${e.message}"}""").apply {
                                    addHeader("Access-Control-Allow-Origin", "*")
                                }
                            }
                        }
                        // V1.3 新增：获取/设置API配置
                        session.uri == "/api/config" -> {
                            when (session.method) {
                                Method.GET -> {
                                    val json = JSONObject().apply {
                                        put("provider", VisionApiClient.apiProvider)
                                        put("apiKey", if (VisionApiClient.apiKey.isNotEmpty()) "***${VisionApiClient.apiKey.takeLast(4)}" else "")
                                        put("apiUrl", VisionApiClient.apiUrl)
                                        put("model", VisionApiClient.modelName)
                                        put("hasKey", VisionApiClient.apiKey.isNotEmpty())
                                    }.toString()
                                    newFixedLengthResponse(Response.Status.OK, "application/json", json).apply {
                                        addHeader("Access-Control-Allow-Origin", "*")
                                    }
                                }
                                Method.POST -> {
                                    try {
                                        val files = HashMap<String, String>()
                                        session.parseBody(files)
                                        val postData = files["postData"] ?: ""
                                        val config = JSONObject(postData)
                                        val provider = config.optString("provider", "")
                                        val key = config.optString("apiKey", "")
                                        if (key.isNotEmpty() && provider.isNotEmpty()) {
                                            VisionApiClient.updateConfig(provider, key)
                                            val json = JSONObject().apply {
                                                put("ok", true)
                                                put("provider", VisionApiClient.apiProvider)
                                                put("model", VisionApiClient.modelName)
                                            }.toString()
                                            newFixedLengthResponse(Response.Status.OK, "application/json", json).apply {
                                                addHeader("Access-Control-Allow-Origin", "*")
                                            }
                                        } else {
                                            newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
                                                """{"error":"need provider and apiKey"}""").apply {
                                                addHeader("Access-Control-Allow-Origin", "*")
                                            }
                                        }
                                    } catch (e: Exception) {
                                        newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json",
                                            """{"error":"${e.message}"}""").apply {
                                            addHeader("Access-Control-Allow-Origin", "*")
                                        }
                                    }
                                }
                                else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "method not allowed")
                            }
                        }
                        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
                    }
                }
            }
            try {
                server?.start()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "智囊HTTP服务", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "HTTP服务运行中" }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("🃏 牌局智囊 v2.9.12")
                .setContentText("HTTP服务运行中")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("🃏 牌局智囊 v2.9.12")
                .setContentText("HTTP服务运行中")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .build()
        }
    }
}
