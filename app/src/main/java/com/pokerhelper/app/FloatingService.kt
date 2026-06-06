package com.pokerhelper.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Bundle
import android.speech.RecognitionListener

class FloatingService : Service() {

    companion object {
        var isRunning = false
        var currentPanelWidth: Int = 0
        var currentPanelHeight: Int = 0
        private const val CHANNEL_ID = "poker_floating"
        private const val NOTIFICATION_ID = 2
        private const val PREFS_NAME = "poker_floating_prefs"
        private const val KEY_LANDSCAPE_WIDTH = "landscape_width"
        private const val KEY_LANDSCAPE_HEIGHT_RATIO = "landscape_height_ratio"
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var webView: WebView? = null
    private var tvStatus: TextView? = null
    private var tvRecResult: TextView? = null
    private var tvAction: TextView? = null
    private var tvVoice: TextView? = null
    private var resizeHandleLeft: View? = null
    private var resizeHandleBottom: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isExpanded = true
    private var prefs: SharedPreferences? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isRunning = true
        initSpeechRecognizer()
        showFloatingWindow()
    }

    override fun onDestroy() {
        isRunning = false
        currentPanelWidth = 0
        currentPanelHeight = 0
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        try {
            webView?.destroy()
        } catch (_: Exception) {}
        try {
            floatingView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handler.postDelayed({ resizeFloatingWindow() }, 500)
    }

    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    tvVoice?.text = "🎤 听..."
                    tvVoice?.setBackgroundColor(0xFF4CAF50.toInt())
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                    tvVoice?.setBackgroundColor(0xFF37474F.toInt())
                }
                override fun onError(error: Int) {
                    isListening = false
                    tvVoice?.text = "🎤"
                    tvVoice?.setBackgroundColor(0xFF37474F.toInt())
                    val errMsg = when(error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "未识别"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "超时"
                        else -> "错误$error"
                    }
                    tvStatus?.text = "语音: $errMsg"
                }
                override fun onResults(results: Bundle?) {
                    isListening = false
                    tvVoice?.text = "🎤"
                    tvVoice?.setBackgroundColor(0xFF37474F.toInt())
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        val result = VoiceInputManager.parseVoiceText(text)
                        // 传给WebView
                        webView?.evaluateJavascript(
                            "if(typeof onVoiceInput==='function'){onVoiceInput(${VoiceInputManager.toJson(result)})}",
                            null
                        )
                        tvStatus?.text = "语音: ${result.holeCards.joinToString(" ")} ${result.rawText}"
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun startVoiceInput() {
        if (speechRecognizer == null) {
            tvStatus?.text = "语音不可用"
            return
        }
        if (isListening) {
            speechRecognizer?.stopListening()
            isListening = false
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun getScreenSize(): Pair<Int, Int> {
        val realW: Int
        val realH: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager?.currentWindowMetrics?.bounds
            realW = bounds?.width() ?: 1080
            realH = bounds?.height() ?: 2344
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager?.defaultDisplay?.getRealMetrics(dm)
            realW = dm.widthPixels
            realH = dm.heightPixels
        }
        return Pair(realW, realH)
    }

    private fun getSavedLandscapeWidth(): Int {
        return prefs?.getInt(KEY_LANDSCAPE_WIDTH, -1) ?: -1
    }

    private fun saveLandscapeWidth(width: Int) {
        prefs?.edit()?.putInt(KEY_LANDSCAPE_WIDTH, width)?.apply()
    }

    private fun getSavedHeightRatio(): Float {
        return prefs?.getFloat(KEY_LANDSCAPE_HEIGHT_RATIO, 0.70f) ?: 0.70f
    }

    private fun saveHeightRatio(ratio: Float) {
        prefs?.edit()?.putFloat(KEY_LANDSCAPE_HEIGHT_RATIO, ratio)?.apply()
    }

    private fun resizeFloatingWindow() {
        try {
            val params = floatingView?.layoutParams as? WindowManager.LayoutParams ?: return
            val (screenWidth, screenHeight) = getScreenSize()
            val isLandscape = screenWidth > screenHeight
            applyWindowSize(params, screenWidth, screenHeight, isLandscape)
            windowManager?.updateViewLayout(floatingView, params)
        } catch (e: Exception) {}
    }

    private fun applyWindowSize(params: WindowManager.LayoutParams, screenWidth: Int, screenHeight: Int, isLandscape: Boolean) {
        if (isExpanded) {
            if (isLandscape) {
                val savedW = getSavedLandscapeWidth()
                params.width = if (savedW > 0) savedW else (screenWidth * 0.42).toInt().coerceIn(380, 780)
                val heightRatio = getSavedHeightRatio()
                params.height = (screenHeight * heightRatio).toInt().coerceIn(screenHeight / 3, screenHeight - 150)
                params.gravity = Gravity.END or Gravity.TOP
                params.x = 0
                params.y = 0
                currentPanelWidth = params.width
                currentPanelHeight = params.height
                resizeHandleLeft?.visibility = View.VISIBLE
                resizeHandleBottom?.visibility = View.VISIBLE
            } else {
                params.width = screenWidth
                params.height = screenHeight
                params.gravity = Gravity.TOP or Gravity.START
                params.x = 0
                params.y = 0
                currentPanelWidth = 0
                currentPanelHeight = 0
                resizeHandleLeft?.visibility = View.GONE
                resizeHandleBottom?.visibility = View.GONE
            }
        } else {
            params.width = if (isLandscape) 140 else (screenWidth * 0.4).toInt()
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            currentPanelWidth = 0
            currentPanelHeight = 0
            resizeHandleLeft?.visibility = View.GONE
            resizeHandleBottom?.visibility = View.GONE
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun showFloatingWindow() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val (screenWidth, screenHeight) = getScreenSize()
        val isLandscape = screenWidth > screenHeight

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xEE0a1a0a.toInt())
        }

        // Top bar with buttons
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF166534.toInt())
            setPadding(12, 6, 12, 6)
        }

        tvStatus = TextView(this).apply {
            text = "🃏 Poker AI v2.5"
            setTextColor(0xFFe8edf5.toInt())
            textSize = 11f
            setPadding(8, 4, 8, 4)
        }

        // V2.0: 识别结果展示行 - 大字显示识别到的牌面
        tvRecResult = TextView(this).apply {
            text = ""
            setTextColor(0xFF90CAF9.toInt())
            textSize = 11f
            setPadding(8, 2, 8, 2)
            setBackgroundColor(0xFF1A237E.toInt())
            visibility = View.GONE
        }

        // V1.3: "我行动"按钮 - 按需截屏
        tvAction = TextView(this)
        tvAction?.text = "🎯"
        tvAction?.setTextColor(0xFFFFFFFF.toInt())
        tvAction?.textSize = 18f
        tvAction?.setPadding(14, 8, 14, 8)
        tvAction?.setBackgroundColor(0xFFE65100.toInt())
        tvAction?.setOnClickListener {
            // V2.1: 只有无障碍截图一条路径，绝不走MediaProjection
            tvStatus?.text = "🎯 截屏中..."
            webView?.evaluateJavascript("document.body.classList.add('api-processing')", null)
            tvAction?.setBackgroundColor(0xFF1565C0.toInt())
            
            if (PokerAccessibilityService.isServiceRunning()) {
                // ★ 唯一路径：无障碍截图（不触发游戏黑屏检测）★
                PokerAccessibilityService.onScreenshotReady = { success ->
                    handler.post {
                        if (success) {
                            processScreenshotAndAnalyze()
                        } else {
                            // V2.1: 无障碍截图失败 → 提示重试，绝不降级MediaProjection
                            tvStatus?.text = "❌ 截图失败，请重试 (${ScreenCaptureService.lastError.take(20)})"
                            tvAction?.setBackgroundColor(0xFFE65100.toInt())
                            webView?.evaluateJavascript("document.body.classList.remove('api-processing')", null)
                        }
                    }
                }
                PokerAccessibilityService.captureScreen()
            } else {
                // V2.1: 无障碍服务未开启 → 提示开启，绝不降级MediaProjection
                tvStatus?.text = "⚠️ 请先开启无障碍服务！回App开启"
                tvAction?.setBackgroundColor(0xFFE65100.toInt())
                webView?.evaluateJavascript("document.body.classList.remove('api-processing')", null)
            }
        }

        // V1.2: 语音输入按钮
        tvVoice = TextView(this).apply {
            text = "🎤"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(10, 6, 10, 6)
            setBackgroundColor(0xFF37474F.toInt())
            setOnClickListener { startVoiceInput() }
        }

        // V1.2: 筹码重置按钮
        val tvReset = TextView(this).apply {
            text = "🔄"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setPadding(10, 6, 10, 6)
            setOnClickListener {
                ChipTracker.reset()
                ScreenCaptureService.lastChipStatus = "已重置"
                webView?.evaluateJavascript("if(typeof onChipReset==='function'){onChipReset()}", null)
                tvStatus?.text = "筹码已重置"
            }
        }

        val tvCollapse = TextView(this).apply {
            text = "  ▼  "
            setTextColor(0xFF4ade80.toInt())
            textSize = 14f
            setPadding(8, 6, 8, 6)
            setOnClickListener { toggleExpand() }
        }

        topBar.addView(tvStatus, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(tvAction!!)
        topBar.addView(tvVoice)
        topBar.addView(tvReset)
        topBar.addView(tvCollapse)
        container.addView(topBar)
        // V2.0: 识别结果展示行
        container.addView(tvRecResult!!, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Content row
        val contentRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        resizeHandleLeft = View(this).apply {
            setBackgroundColor(0x40FFFFFF.toInt())
            setOnTouchListener(ResizeWidthTouchListener())
        }
        val leftHandleParams = LinearLayout.LayoutParams(16, LinearLayout.LayoutParams.MATCH_PARENT)
        contentRow.addView(resizeHandleLeft, leftHandleParams)

        val wv = WebView(this)
        webView = wv
        val wvParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.MATCH_PARENT,
            1f
        )
        contentRow.addView(wv, wvParams)

        val contentRowParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        container.addView(contentRow, contentRowParams)

        resizeHandleBottom = View(this).apply {
            setBackgroundColor(0x40FFFFFF.toInt())
            setOnTouchListener(ResizeHeightTouchListener())
        }
        val bottomHandleParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 16)
        container.addView(resizeHandleBottom, bottomHandleParams)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportZoom(false)
            builtInZoomControls = false
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                if (errorCode == -2 || errorCode == -6) {
                    wv.postDelayed({ wv.loadUrl("http://127.0.0.1:8666") }, 2000)
                }
            }
        }
        wv.webChromeClient = WebChromeClient()

        wv.addJavascriptInterface(object : Any() {
            @JavascriptInterface
            fun updateStatus(text: String) {
                handler.post { tvStatus?.text = text }
            }
            
            @JavascriptInterface
            fun getChipStatus(): String {
                return ChipTracker.getStatusJson()
            }
            
            @JavascriptInterface
            fun resetChips() {
                handler.post {
                    ChipTracker.reset()
                    ScreenCaptureService.lastChipStatus = "已重置"
                }
            }
            
            @JavascriptInterface
            fun startVoice() {
                handler.post { startVoiceInput() }
            }
            
            @JavascriptInterface
            fun parseVoice(text: String): String {
                val result = VoiceInputManager.parseVoiceText(text)
                return VoiceInputManager.toJson(result)
            }
        }, "AndroidBridge")

        wv.loadUrl("http://127.0.0.1:8666")

        floatingView = container

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        applyWindowSize(params, screenWidth, screenHeight, isLandscape)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        topBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) isDragging = true
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager?.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> { !isDragging }
                else -> false
            }
        }

        windowManager?.addView(floatingView, params)
    }

    private inner class ResizeWidthTouchListener : View.OnTouchListener {
        private var startWidth = 0
        private var startTouchX = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            val params = floatingView?.layoutParams as? WindowManager.LayoutParams ?: return false
            val (screenWidth, screenHeight) = getScreenSize()
            if (screenWidth <= screenHeight) return false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startWidth = params.width
                    startTouchX = event.rawX
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = startTouchX - event.rawX
                    val newWidth = (startWidth + dx.toInt()).coerceIn(280, screenWidth - 200)
                    params.width = newWidth
                    currentPanelWidth = newWidth
                    try {
                        windowManager?.updateViewLayout(floatingView, params)
                    } catch (_: Exception) {}
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    saveLandscapeWidth(params.width)
                    currentPanelWidth = params.width
                    return true
                }
            }
            return false
        }
    }

    private inner class ResizeHeightTouchListener : View.OnTouchListener {
        private var startHeight = 0
        private var startTouchY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            val params = floatingView?.layoutParams as? WindowManager.LayoutParams ?: return false
            val (screenWidth, screenHeight) = getScreenSize()
            if (screenWidth <= screenHeight) return false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startHeight = params.height
                    startTouchY = event.rawY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - startTouchY
                    val newHeight = (startHeight + dy.toInt()).coerceIn(screenHeight / 3, screenHeight - 150)
                    params.height = newHeight
                    currentPanelHeight = newHeight
                    try {
                        windowManager?.updateViewLayout(floatingView, params)
                    } catch (_: Exception) {}
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val ratio = params.height.toFloat() / screenHeight.toFloat()
                    saveHeightRatio(ratio)
                    currentPanelHeight = params.height
                    return true
                }
            }
            return false
        }
    }

    /**
     * V1.9: 处理截图并调用API分析
     * 从 ScreenCaptureService.latestScreenshot 读取截图数据
     * 数据来自AccessibilityService.takeScreenshot()（唯一截图路径）
     */
    private fun processScreenshotAndAnalyze() {
        val screenshot = ScreenCaptureService.latestScreenshot
        if (screenshot == null) {
            val diag = when {
                !PokerAccessibilityService.isServiceRunning() ->
                    "❌ 截屏失败：无障碍服务未开，请回App开启"
                ScreenCaptureService.lastError.isNotEmpty() ->
                    "❌ 截屏失败: ${ScreenCaptureService.lastError.take(30)}"
                else -> "❌ 截屏失败，请重试"
            }
            tvStatus?.text = diag
            tvAction?.setBackgroundColor(0xFFE65100.toInt())
            webView?.evaluateJavascript("document.body.classList.remove('api-processing')", null)
            return
        }

        if (VisionApiClient.apiKey.isEmpty()) {
            // 没有API Key，只做本地刷新
            webView?.evaluateJavascript(
                "if(typeof onActionCapture==='function'){onActionCapture()};document.body.classList.add('speed-mode');document.body.classList.remove('api-processing')",
                null
            )
            tvAction?.setBackgroundColor(0xFF4CAF50.toInt())
            tvStatus?.text = ScreenCaptureService.lastChipStatus.ifEmpty { "🎯 已更新(无API)" }
            return
        }

        // 有API Key → 调用视觉模型识别牌面
        tvStatus?.text = "🎯 API识别中..."
        tvAction?.setBackgroundColor(0xFF1565C0.toInt())
        Thread {
            try {
                val result = VisionApiClient.analyzeScreenshot(screenshot)
                if (result != null) {
                    val resultJson = VisionApiClient.toJson(result)
                    handler.post {
                        webView?.evaluateJavascript(
                            "if(typeof onVisionResult==='function'){onVisionResult($resultJson)}",
                            null
                        )
                        tvAction?.setBackgroundColor(0xFF4CAF50.toInt())
                        val hole = result.holeCards.map { it.rank + it.suit }.joinToString(" ")
                        tvStatus?.text = "✅ $hole | ${result.street} | ${result.totalPlayers}人"
                        // V2.0: 显示识别结果到原生行
                        val suitSym = mapOf("s" to "♠", "h" to "♥", "d" to "♦", "c" to "♣")
                        val streetCN = mapOf("preflop" to "翻前", "flop" to "翻牌", "turn" to "转牌", "river" to "河牌")
                        val holeStr = result.holeCards.map { "${it.rank}${suitSym[it.suit] ?: it.suit}" }.joinToString(" ")
                        val commStr = result.communityCards.map { "${it.rank}${suitSym[it.suit] ?: it.suit}" }.joinToString(" ")
                        val streetStr = streetCN[result.street] ?: result.street
                        var recText = "🔍 手牌: $holeStr"
                        if (commStr.isNotEmpty()) recText += " | 公共: $commStr"
                        recText += " | $streetStr | ${result.totalPlayers}人"
                        if (result.toCall > 0) recText += " | 跟注${result.toCall}"
                        // V2.0: 显示识别校验警告
                        val apiError = VisionApiClient.lastError
                        if (apiError.isNotEmpty()) {
                            recText += " ⚠️$apiError"
                            tvRecResult?.setBackgroundColor(0xFF8B0000.toInt()) // 深红底色=有疑问
                        } else {
                            tvRecResult?.setBackgroundColor(0xFF1A237E.toInt()) // 蓝底=正常
                        }
                        tvRecResult?.text = recText
                        tvRecResult?.visibility = View.VISIBLE
                    }
                } else {
                    handler.post {
                        tvAction?.setBackgroundColor(0xFFE65100.toInt())
                        tvStatus?.text = "❌ API: ${VisionApiClient.lastError.take(30)}"
                        webView?.evaluateJavascript("document.body.classList.remove('api-processing')", null)
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    tvAction?.setBackgroundColor(0xFFE65100.toInt())
                    tvStatus?.text = "❌ API错误"
                    webView?.evaluateJavascript("document.body.classList.remove('api-processing')", null)
                }
            }
        }.start()
    }

    private fun toggleExpand() {
        isExpanded = !isExpanded
        webView?.visibility = if (isExpanded) View.VISIBLE else View.GONE
        resizeHandleLeft?.visibility = if (isExpanded && getScreenSize().first > getScreenSize().second) View.VISIBLE else View.GONE
        resizeHandleBottom?.visibility = if (isExpanded && getScreenSize().first > getScreenSize().second) View.VISIBLE else View.GONE

        val (screenWidth, screenHeight) = getScreenSize()
        val isLandscape = screenWidth > screenHeight

        val params = floatingView?.layoutParams as? WindowManager.LayoutParams ?: return
        applyWindowSize(params, screenWidth, screenHeight, isLandscape)
        windowManager?.updateViewLayout(floatingView, params)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "扑克悬浮窗", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "扑克AI助手运行中" }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("🃏 扑克AI助手 v2.5")
                .setContentText("悬浮窗+语音+OCR运行中")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("🃏 扑克AI助手 v2.5")
                .setContentText("悬浮窗运行中")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .build()
        }
    }
}
