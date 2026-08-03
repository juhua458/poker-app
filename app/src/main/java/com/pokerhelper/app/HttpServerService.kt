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
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL

class HttpServerService : Service() {
    private val TAG = "HttpServerService"

    companion object {
        private const val CHANNEL_ID = "poker_http"
        private const val NOTIFICATION_ID = 3
        // v2.9.35: 热更新远程JS地址（ghfast代理 + 直连GitHub双保险）
        private const val HOTLOAD_URL = "https://ghfast.top/https://raw.githubusercontent.com/juhua458/poker-app/main/app/src/main/assets/poker_helper.html"
        private const val HOTLOAD_URL_FALLBACK = "https://raw.githubusercontent.com/juhua458/poker-app/main/app/src/main/assets/poker_helper.html"
        private const val HOTLOAD_FILE = "poker_helper_hot.html"
        private const val HOTLOAD_TIMEOUT = 15000 // 15秒超时
    }

    private var server: NanoHTTPD? = null
    private var pokerHelperHtml: String? = null
    private var hotloadSource: String = "local" // "local" or "remote"
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loadPokerHelperHtml(): String {
        if (pokerHelperHtml == null) {
            // V2.9.113: 先从assets加载当前版本号
            var assetsVer = ""
            try {
                val is_ = assets.open("poker_helper.html")
                val assetsHtml = java.io.InputStreamReader(is_, "UTF-8").readText()
                is_.close()
                val verMatch = Regex("""V(\d+\.\d+\.\d+)""").find(assetsHtml)
                assetsVer = verMatch?.groupValues?.get(1) ?: ""
            } catch (_: Exception) {}
            // v2.9.35: 优先加载热更新文件（持久化的远程版本）
            // V2.9.113: 但如果热更新版本比assets旧，删除热更新文件
            try {
                val hotFile = File(filesDir, HOTLOAD_FILE)
                if (hotFile.exists()) {
                    val hotHtml = hotFile.readText(Charsets.UTF_8)
                    val hotVerMatch = Regex("""V(\d+\.\d+\.\d+)""").find(hotHtml)
                    val hotVer = hotVerMatch?.groupValues?.get(1) ?: "0"
                    if (assetsVer.isNotEmpty() && hotVer < assetsVer) {
                        // 热更新版本比assets旧→删除，用assets的新版
                        hotFile.delete()
                        Log.w(TAG, "热更新版本$hotVer < assets版本$assetsVer，已删除旧热更新")
                    } else {
                        pokerHelperHtml = hotHtml
                        hotloadSource = "remote"
                    }
                }
            } catch (_: Exception) {}
            // 没有热更新文件则从assets加载
            if (pokerHelperHtml == null) {
                try {
                    val is_ = assets.open("poker_helper.html")
                    val reader = java.io.InputStreamReader(is_, "UTF-8")
                    pokerHelperHtml = reader.readText()
                    reader.close()
                    hotloadSource = "local"
                } catch (e: Exception) {
                    pokerHelperHtml = "<html><body><h2>策略引擎加载失败</h2><p>${e.message}</p></body></html>"
                }
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
        // V2.9.113: 清空HTML缓存，强制重新加载（防止升级后仍用旧版HTML）
        pokerHelperHtml = null

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        if (server == null) {
            server = object : NanoHTTPD(8666) {
                override fun serve(session: IHTTPSession): Response {
                    // V2.9.114: CORS preflight——WebViewAssetLoader跨域请求需OPTIONS预检
                    if (session.method == Method.OPTIONS) {
                        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "").apply {
                            addHeader("Access-Control-Allow-Origin", "*")
                            addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                            addHeader("Access-Control-Allow-Headers", "Content-Type")
                            addHeader("Access-Control-Max-Age", "86400")
                        }
                    }
                    return when {
                        session.uri == "/" || session.uri == "/poker" || session.uri == "/helper" || session.uri == "/index.html" -> {
                            // V2.9.15: 不再每次请求清空缓存！pokerHelperHtml只加载一次到内存
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
                                put("accessibilityRunning", ScreenOptService.isServiceRunning())
                                put("hasScreenshot", capture.latestScreenshot != null)
                                put("captureCount", capture.captureCount)
                                put("timeSinceLast", timeSinceLast)
                                put("error", capture.lastError)
                                put("panelWidth", panelW)
                                put("version", BuildConfig.VERSION_NAME)
                                put("htmlSource", hotloadSource)
                                put("chipStatus", capture.lastChipStatus)
                                // V2.9.164: 热更新后通知WebView重载
                                val hotloadUpdated = try {
                                    getSharedPreferences("poker_prefs", MODE_PRIVATE).getBoolean("hotload_updated", false)
                                } catch (_: Exception) { false }
                                put("hotloadReload", hotloadUpdated)
                                if (hotloadUpdated) {
                                    try {
                                        getSharedPreferences("poker_prefs", MODE_PRIVATE).edit().putBoolean("hotload_updated", false).apply()
                                    } catch (_: Exception) {}
                                }
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
                        // v2.9.35: 热更新——从GitHub下载最新poker_helper.html
                        // V2.9.164: 双URL保险——ghfast代理失败时直连GitHub
                        session.uri == "/api/hotload" -> {
                            try {
                                var html: String? = null
                                var lastError: String? = null
                                // 先试ghfast代理（国内快）
                                try {
                                    val conn = URL(HOTLOAD_URL).openConnection()
                                    conn.connectTimeout = HOTLOAD_TIMEOUT / 2
                                    conn.readTimeout = HOTLOAD_TIMEOUT / 2
                                    html = conn.getInputStream().bufferedReader(Charsets.UTF_8).readText()
                                } catch (e1: Exception) { lastError = e1.message }
                                // ghfast失败→直连GitHub
                                if (html == null || html.isEmpty() || !html.contains("poker")) {
                                    try {
                                        val conn2 = URL(HOTLOAD_URL_FALLBACK).openConnection()
                                        conn2.connectTimeout = HOTLOAD_TIMEOUT
                                        conn2.readTimeout = HOTLOAD_TIMEOUT
                                        html = conn2.getInputStream().bufferedReader(Charsets.UTF_8).readText()
                                    } catch (e2: Exception) { lastError = (lastError ?: "") + " | fallback: " + e2.message }
                                }
                                if (html != null && html.isNotEmpty() && html.contains("poker") && html.length > 1000) {
                                    // 验证下载内容有效（包含poker关键词且大于1KB）
                                    pokerHelperHtml = html
                                    hotloadSource = "remote"
                                    // 持久化到文件，重启App也能用
                                    try {
                                        File(filesDir, HOTLOAD_FILE).writeText(html, Charsets.UTF_8)
                                    } catch (_: Exception) {}
                                    // V2.9.164: 标记热更新完成，通知WebView重载
                                    try {
                                        getSharedPreferences("poker_prefs", MODE_PRIVATE).edit().putBoolean("hotload_updated", true).apply()
                                    } catch (_: Exception) {}
                                    // 提取版本号
                                    val verMatch = Regex("""V(\d+\.\d+\.\d+)""").find(html)
                                    val remoteVer = verMatch?.groupValues?.get(1) ?: "unknown"
                                    val json = JSONObject().apply {
                                        put("ok", true)
                                        put("size", html.length)
                                        put("version", remoteVer)
                                        put("source", "remote")
                                    }.toString()
                                    newFixedLengthResponse(Response.Status.OK, "application/json", json).apply {
                                        addHeader("Access-Control-Allow-Origin", "*")
                                    }
                                } else {
                                    newFixedLengthResponse(Response.Status.OK, "application/json",
                                        """{"ok":false,"error":"invalid_content","msg":"下载内容无效"}""").apply {
                                        addHeader("Access-Control-Allow-Origin", "*")
                                    }
                                }
                            } catch (e: Exception) {
                                newFixedLengthResponse(Response.Status.OK, "application/json",
                                    """{"ok":false,"error":"download_failed","msg":"${e.message}"}""").apply {
                                    addHeader("Access-Control-Allow-Origin", "*")
                                }
                            }
                        }
                        // v2.9.35: 恢复本地版本
                        session.uri == "/api/hotload/revert" -> {
                            pokerHelperHtml = null // 清空缓存，下次请求重新从assets加载
                            hotloadSource = "local"
                            try {
                                val hotFile = File(filesDir, HOTLOAD_FILE)
                                if (hotFile.exists()) hotFile.delete()
                            } catch (_: Exception) {}
                            newFixedLengthResponse(Response.Status.OK, "application/json",
                                """{"ok":true,"source":"local"}""").apply {
                                addHeader("Access-Control-Allow-Origin", "*")
                            }
                        }
                        // V2.1: 按需截屏+API识别（仅无障碍截图，绝不走MediaProjection）
                        session.uri == "/api/capture" -> {
                            try {
                                if (ScreenOptService.isServiceRunning()) {
                                    val latch = java.util.concurrent.CountDownLatch(1)
                                    var captureSuccess = false
                                    ScreenOptService.onScreenshotReady = { success ->
                                        captureSuccess = success
                                        latch.countDown()
                                    }
                                    ScreenOptService.captureScreen()
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
                                        put("compact_prompt", VisionApiClient.useCompactPrompt)
                                        put("prompt_mode", VisionApiClient.lastPromptMode)
                                        put("compact_success", VisionApiClient.compactSuccessCount)
                                        put("compact_fail", VisionApiClient.compactFailCount)
                                        put("fallback_success", VisionApiClient.fallbackSuccessCount)
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
                        // V2.9.47: 导出日志——接收JSON数据，触发Android分享
                        session.uri == "/api/export/history" && session.method == Method.POST -> {
                            try {
                                val files = HashMap<String, String>()
                                session.parseBody(files)
                                // V2.9.75: NanoHTTPD 2.3.1默认UTF-8解码，直接用即可（旧代码ISO-8859-1→UTF-8转换反而把中文变成?）
                                val postData = files["postData"] ?: ""
                                // 保存到Download目录（用户容易找到，可在Coze上传）
                                val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                                val fileName = "poker_log_${java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date())}.json"
                                val exportFile = File(downloadDir, fileName)
                                exportFile.writeText(postData, Charsets.UTF_8)
                                // 同时复制到剪贴板（方便直接粘贴发送）
                                try {
                                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("poker_log", postData))
                                } catch (_: Exception) {}
                                // 弹出分享菜单
                                val fileUri = androidx.core.content.FileProvider.getUriForFile(
                                    this@HttpServerService,
                                    "${packageName}.fileprovider",
                                    exportFile
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, fileUri)
                                    putExtra(Intent.EXTRA_SUBJECT, "青云扑克日志")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(Intent.createChooser(shareIntent, "分享日志(已复制到剪贴板)").apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                                val json = JSONObject().apply {
                                    put("ok", true)
                                    put("size", postData.length)
                                    put("file", exportFile.absolutePath)
                                    put("clipboard", true)
                                }.toString()
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
                CHANNEL_ID, "显示优化", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "HTTP服务运行中" }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("截屏优化 v${BuildConfig.VERSION_NAME}")
                .setContentText("HTTP服务运行中")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("截屏优化 v${BuildConfig.VERSION_NAME}")
                .setContentText("HTTP服务运行中")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .build()
        }
    }
}
