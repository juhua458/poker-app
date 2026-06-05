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
                pokerHelperHtml = "<html><body><h2>扑克策略引擎加载失败</h2><p>${e.message}</p></body></html>"
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
                                put("running", capture.isRunning)
                                put("hasScreenshot", capture.latestScreenshot != null)
                                put("captureCount", capture.captureCount)
                                put("timeSinceLast", timeSinceLast)
                                put("error", capture.lastError)
                                put("panelWidth", panelW)
                                put("version", "1.2")
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
                CHANNEL_ID, "扑克HTTP服务", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "HTTP服务运行中" }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("🃏 扑克AI助手 v1.2")
                .setContentText("HTTP服务运行中")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("🃏 扑克AI助手 v1.2")
                .setContentText("HTTP服务运行中")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .build()
        }
    }
}
