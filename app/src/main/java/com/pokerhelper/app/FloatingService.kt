package com.pokerhelper.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
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
import android.os.Bundle
import android.graphics.drawable.GradientDrawable

class FloatingService : Service() {

    companion object {
        var isRunning = false
        var currentPanelWidth: Int = 0
        var currentPanelHeight: Int = 0
        private const val CHANNEL_ID = "screen_opt_v2"
        private const val NOTIFICATION_ID = 2
        private const val PREFS_NAME = "poker_floating_prefs"
        private const val KEY_LANDSCAPE_WIDTH = "landscape_width"
        private const val KEY_LANDSCAPE_HEIGHT_RATIO = "landscape_height_ratio"
        const val KEY_STEALTH_MODE = "stealth_mode"
        const val ACTION_CAPTURE = "com.pokerhelper.app.CAPTURE"
        const val ACTION_VOICE = "com.pokerhelper.app.VOICE"
        const val ACTION_OPEN = "com.pokerhelper.app.OPEN"
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
    private var isStealthMode = false

    // V2.9.40: 悬浮球 — 一键截屏
    private var floatingBall: TextView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private val BALL_SIZE_DP = 48
    private val KEY_BALL_X = "ball_x"
    private val KEY_BALL_Y = "ball_y"

    // V2.9.4: WebView加载追踪 + JS调用队列
    private var webViewReady = false
    private val pendingJsCalls = mutableListOf<String>()

    // V2.9.38: 隐身模式通知广播接收器
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_CAPTURE -> triggerCapture()
                ACTION_VOICE -> startVoiceInput()
                ACTION_OPEN -> {
                    val openIntent = packageManager.getLaunchIntentForPackage(packageName)
                    openIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(openIntent)
                }
            }
        }
    }

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
        isStealthMode = prefs?.getBoolean(KEY_STEALTH_MODE, false) ?: false

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isRunning = true

        // V2.9.38: 注册通知按钮广播接收器
        val filter = IntentFilter().apply {
            addAction(ACTION_CAPTURE)
            addAction(ACTION_VOICE)
            addAction(ACTION_OPEN)
        }
        // V2.9.38: Android 14+必须指定RECEIVER_EXPORTED/RECEIVER_NOT_EXPORTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(notificationReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(notificationReceiver, filter)
        }

        initSpeechRecognizer()
        showFloatingWindow()
        showFloatingBall()
    }

    override fun onDestroy() {
        isRunning = false
        currentPanelWidth = 0
        currentPanelHeight = 0
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        removeFloatingBall()
        try {
            unregisterReceiver(notificationReceiver)
        } catch (_: Exception) {}
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
                    tvVoice?.alpha = 0.5f
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                    tvVoice?.alpha = 1.0f
                }
                override fun onError(error: Int) {
                    isListening = false
                    tvVoice?.text = "🎤"
                    tvVoice?.alpha = 1.0f
                    val errMsg = when(error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "未识别"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "超时"
                        else -> "错误$error"
                    }
                    tvStatus?.text = "语音: $errMsg"
                    if (isStealthMode) updateAdviceNotification("语音: $errMsg", "")
                }
                override fun onResults(results: Bundle?) {
                    isListening = false
                    tvVoice?.text = "🎤"
                    tvVoice?.alpha = 1.0f
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val text = matches[0]
                        val result = VoiceInputManager.parseVoiceText(text)
                        executeJs("if(typeof onVoiceInput==='function'){onVoiceInput(${VoiceInputManager.toJson(result)})}")
                        tvStatus?.text = "语音: ${result.holeCards.joinToString(" ")} ${result.rawText}"
                        if (isStealthMode) updateAdviceNotification("语音: ${result.holeCards.joinToString(" ")}", result.rawText)
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
            if (isStealthMode) updateAdviceNotification("语音不可用", "")
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
        if (isStealthMode) return // V2.9.38: 隐身模式不调整窗口
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
            params.width = if (isLandscape) 80 else (screenWidth * 0.4).toInt()
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            currentPanelWidth = 0
            currentPanelHeight = 0
            resizeHandleLeft?.visibility = View.GONE
            resizeHandleBottom?.visibility = View.GONE
        }
    }

    // V2.9.4: 统一JS调用入口 — WebView未就绪时自动排队
    private fun executeJs(js: String) {
        if (webViewReady && webView != null) {
            webView?.evaluateJavascript(js, null)
        } else {
            pendingJsCalls.add(js)
        }
    }

    /**
     * V2.9.38: 触发截屏（通知栏按钮调用）
     */
    private fun triggerCapture() {
        executeJs("if(typeof clr==='function'){clr()}")
        tvRecResult?.text = ""
        tvRecResult?.visibility = View.GONE
        
        tvStatus?.text = "🎯 截屏中..."
        executeJs("document.body.classList.add('api-processing')")
        tvAction?.alpha = 0.5f

        if (ScreenOptService.isServiceRunning()) {
            ScreenOptService.onScreenshotReady = { success ->
                handler.post {
                    if (success) {
                        processScreenshotAndAnalyze()
                    } else {
                        tvStatus?.text = "❌ 截图失败，请重试"
                        tvAction?.alpha = 1.0f
                        executeJs("document.body.classList.remove('api-processing')")
                        if (isStealthMode) updateAdviceNotification("截图失败", "请重试或检查无障碍服务")
                    }
                }
            }
            ScreenOptService.captureScreen()
        } else {
            tvStatus?.text = "⚠️ 请先开启无障碍服务！"
            tvAction?.alpha = 1.0f
            executeJs("document.body.classList.remove('api-processing')")
            if (isStealthMode) updateAdviceNotification("无障碍未开启", "请回App开启后重试")
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

        // Top bar with buttons - V2.9.14: 半透明紧凑顶栏
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x990a1a0a.toInt())
            setPadding(4, 1, 4, 1)
        }

        tvStatus = TextView(this).apply {
            text = "青云 v2.9.40"
            setTextColor(0xFFe8edf5.toInt())
            textSize = 9f
            setPadding(2, 0, 2, 0)
        }

        // V2.0: 识别结果展示行 - v2.9.35: 紧凑半透明
        tvRecResult = TextView(this).apply {
            text = ""
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 9f
            setPadding(6, 2, 6, 2)
            setBackgroundColor(0x990a1a0a.toInt())
            visibility = View.GONE
        }

        // V2.9.1: 🎯截图按钮
        tvAction = TextView(this)
        tvAction?.text = "🎯"
        tvAction?.setTextColor(0xFFFFFFFF.toInt())
        tvAction?.textSize = 14f
        tvAction?.gravity = Gravity.CENTER
        tvAction?.setPadding(6, 2, 6, 2)
        tvAction?.setBackgroundColor(0x00000000)
        tvAction?.setOnClickListener {
            triggerCapture()
        }

        // V1.2: 语音输入按钮
        tvVoice = TextView(this).apply {
            text = "🎤"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(6, 2, 6, 2)
            setBackgroundColor(0x00000000)
            setOnClickListener { startVoiceInput() }
        }

        // V1.2: 筹码重置按钮
        val tvReset = TextView(this).apply {
            text = "🔄"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12f
            setPadding(6, 2, 6, 2)
            setBackgroundColor(0x00000000)
            setOnClickListener {
                ChipTracker.reset()
                ScreenCaptureService.lastChipStatus = "已重置"
                executeJs("if(typeof onChipReset==='function'){onChipReset()}")
                tvStatus?.text = "筹码已重置"
            }
        }

        val tvCollapse = TextView(this).apply {
            text = "▼"
            setTextColor(0xFF4ade80.toInt())
            textSize = 10f
            setPadding(4, 2, 4, 2)
            setOnClickListener { toggleExpand() }
        }

        topBar.addView(tvStatus, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(tvAction!!)
        topBar.addView(tvVoice)
        topBar.addView(tvReset)
        topBar.addView(tvCollapse)
        container.addView(topBar)
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
        wv.setBackgroundColor(0x00000000)
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

        // WebView settings
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(false)
            builtInZoomControls = false
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                if (errorCode == -2 || errorCode == -6) {
                    wv.postDelayed({ wv.loadUrl("http://127.0.0.1:8666") }, 2000)
                }
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!webViewReady) {
                    webViewReady = true
                    val calls = ArrayList(pendingJsCalls)
                    pendingJsCalls.clear()
                    for (js in calls) {
                        view?.evaluateJavascript(js, null)
                    }
                }
            }
        }
        wv.webChromeClient = WebChromeClient()

        // ★ 关键：addJavascriptInterface必须在loadUrl之前注册 ★
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
            
            @JavascriptInterface
            fun showAdvice(advice: String) {
                handler.post {
                    val currentText = tvRecResult?.text?.toString() ?: ""
                    if (advice.isNotEmpty()) {
                        tvRecResult?.text = "$advice | $currentText"
                        tvRecResult?.visibility = View.VISIBLE
                        when {
                            advice.contains("弃牌") -> tvRecResult?.setTextColor(0xFFFF5252.toInt())
                            advice.contains("跟注") -> tvRecResult?.setTextColor(0xFFFFAB40.toInt())
                            advice.contains("加注") -> tvRecResult?.setTextColor(0xFF69F0AE.toInt())
                            advice.contains("全下") -> tvRecResult?.setTextColor(0xFFCE93D8.toInt())
                            advice.contains("过牌") -> tvRecResult?.setTextColor(0xFFBDBDBD.toInt())
                        }
                        // V2.9.40: 悬浮球边框也跟着变
                        updateBallAdvice(advice)
                    }
                }
            }
            
            // V2.9.38: 隐身模式通知更新
            @JavascriptInterface
            fun updateNotification(title: String, detail: String) {
                handler.post {
                    if (isStealthMode) {
                        updateAdviceNotification(title, detail)
                    }
                }
            }
        }, "AndroidBridge")

        wv.loadUrl("http://127.0.0.1:8666")

        floatingView = container

        // V2.9.38: ★ 隐身模式 — 1x1像素不可见覆盖层 ★
        if (isStealthMode) {
            // 1x1像素透明覆盖层，FLAG_NOT_TOUCHABLE不接收触摸事件
            // 不用alpha=0和负坐标，某些Android版本会拒绝
            val stealthParams = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            stealthParams.gravity = Gravity.TOP or Gravity.START
            stealthParams.x = 0
            stealthParams.y = 0
            try {
                windowManager?.addView(floatingView, stealthParams)
            } catch (e: Exception) {
                // 添加失败也不崩溃，WebView仍在后台运行
            }
            // 立即更新通知显示隐身模式已开启
            updateAdviceNotification("显示优化运行中", "点击截屏识别")
        } else {
            // 正常模式：标准悬浮窗
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
    }

    /**
     * V2.9.40: 悬浮球 — 一键截屏识别
     * 点击→截屏, 长按→展开/收起面板, 拖动→移动位置
     * 自动吸附到最近的屏幕边缘, 位置记忆
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showFloatingBall() {
        if (floatingBall != null) return

        val density = resources.displayMetrics.density
        val sizePx = (BALL_SIZE_DP * density).toInt()
        val (screenWidth, screenHeight) = getScreenSize()

        val ball = TextView(this).apply {
            text = "🎯"
            textSize = 20f
            gravity = Gravity.CENTER
            val shape = GradientDrawable()
            shape.shape = GradientDrawable.OVAL
            shape.setColor(0xDD1a1a2e.toInt())
            shape.setStroke((2 * density).toInt(), 0xFF4ade80.toInt())
            background = shape
            elevation = 8f
        }
        floatingBall = ball

        val savedX = prefs?.getInt(KEY_BALL_X, -1) ?: -1
        val savedY = prefs?.getInt(KEY_BALL_Y, -1) ?: -1

        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = if (savedX >= 0) savedX else screenWidth - sizePx - 8
        params.y = if (savedY >= 0) savedY else screenHeight / 2 - sizePx / 2
        ballParams = params

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var isLongPressed = false
        var longPressRunnable: Runnable? = null

        ball.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    isLongPressed = false
                    longPressRunnable = Runnable {
                        isLongPressed = true
                        if (!isStealthMode) {
                            toggleExpand()
                        } else {
                            val openIntent = packageManager.getLaunchIntentForPackage(packageName)
                            openIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(openIntent)
                        }
                    }
                    handler.postDelayed(longPressRunnable!!, 500)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                        longPressRunnable?.let { handler.removeCallbacks(it) }
                    }
                    if (isDragging) {
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        try {
                            windowManager?.updateViewLayout(floatingBall, params)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressRunnable?.let { handler.removeCallbacks(it) }
                    if (isDragging) {
                        // 吸附到最近的屏幕边缘
                        val (sw, sh) = getScreenSize()
                        val centerX = params.x + sizePx / 2
                        params.x = if (centerX < sw / 2) 0 else sw - sizePx
                        params.y = params.y.coerceIn(0, sh - sizePx)
                        try {
                            windowManager?.updateViewLayout(floatingBall, params)
                        } catch (_: Exception) {}
                        prefs?.edit()?.putInt(KEY_BALL_X, params.x)?.putInt(KEY_BALL_Y, params.y)?.apply()
                    } else if (!isLongPressed) {
                        // 点击 → 截屏识别
                        triggerCapture()
                        // 视觉反馈：闪绿
                        updateBallColor(0xDD4ade80.toInt())
                        handler.postDelayed({ updateBallColor(0xDD1a1a2e.toInt()) }, 300)
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(ball, params)
        } catch (e: Exception) {
            // 添加失败不影响主功能
        }
    }

    /**
     * V2.9.40: 更新悬浮球背景色
     */
    private fun updateBallColor(bgColor: Int) {
        try {
            val ball = floatingBall ?: return
            val shape = ball.background as? GradientDrawable ?: return
            shape.setColor(bgColor)
        } catch (_: Exception) {}
    }

    /**
     * V2.9.40: 根据建议更新悬浮球边框颜色
     * 绿=加注/全下 橙=跟注 红=弃牌 灰=过牌
     */
    fun updateBallAdvice(advice: String) {
        try {
            val ball = floatingBall ?: return
            val shape = ball.background as? GradientDrawable ?: return
            val density = resources.displayMetrics.density
            val stroke = (2 * density).toInt()
            when {
                advice.contains("全下") || advice.contains("加注") -> shape.setStroke(stroke, 0xFF69F0AE.toInt())
                advice.contains("跟注") -> shape.setStroke(stroke, 0xFFFFAB40.toInt())
                advice.contains("弃牌") -> shape.setStroke(stroke, 0xFFFF5252.toInt())
                advice.contains("过牌") -> shape.setStroke(stroke, 0xFFBDBDBD.toInt())
                else -> shape.setStroke(stroke, 0xFF4ade80.toInt())
            }
        } catch (_: Exception) {}
    }

    private fun removeFloatingBall() {
        try {
            floatingBall?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {}
        floatingBall = null
        ballParams = null
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
     * 数据来自ScreenOptService.takeScreenshot()（唯一截图路径）
     */
    private fun processScreenshotAndAnalyze() {
        val screenshot = ScreenCaptureService.latestScreenshot
        if (screenshot == null) {
            val diag = when {
                !ScreenOptService.isServiceRunning() ->
                    "❌ 截屏失败：无障碍服务未开，请回App开启"
                ScreenCaptureService.lastError.isNotEmpty() ->
                    "❌ 截屏失败: ${ScreenCaptureService.lastError.take(30)}"
                else -> "❌ 截屏失败，请重试"
            }
            tvStatus?.text = diag
            tvAction?.alpha = 1.0f
            executeJs("document.body.classList.remove('api-processing')")
            if (isStealthMode) updateAdviceNotification("截图失败", diag)
            return
        }

        if (VisionApiClient.apiKey.isEmpty()) {
            executeJs("if(typeof onActionCapture==='function'){onActionCapture()};document.body.classList.add('speed-mode');document.body.classList.remove('api-processing')")
            tvAction?.alpha = 1.0f
            tvStatus?.text = ScreenCaptureService.lastChipStatus.ifEmpty { "🎯 已更新(无API)" }
            if (isStealthMode) updateAdviceNotification("已更新(无API)", ScreenCaptureService.lastChipStatus)
            return
        }

        // 有API Key → 调用视觉模型识别牌面
        tvStatus?.text = "🎯 API识别中..."
        tvAction?.alpha = 0.5f
        if (isStealthMode) updateAdviceNotification("识别中...", "正在分析牌面")
        Thread {
            try {
                val result = VisionApiClient.analyzeScreenshot(screenshot)
                if (result != null) {
                    val resultJson = VisionApiClient.toJson(result)
                    handler.post {
                        executeJs("if(typeof onVisionResult==='function'){onVisionResult($resultJson)}")
                        tvAction?.alpha = 1.0f
                        val hole = result.holeCards.map { (if(it.rank=="T") "10" else it.rank) + it.suit }.joinToString(" ")
                        tvStatus?.text = "✅ $hole | ${result.street} | ${result.totalPlayers}人"
                        val suitSym = mapOf("s" to "♠", "h" to "♥", "d" to "♦", "c" to "♣")
                        val streetCN = mapOf("preflop" to "翻前", "flop" to "翻牌", "turn" to "转牌", "river" to "河牌")
                        val holeStr = result.holeCards.map { "${if(it.rank=="T") "10" else it.rank}${suitSym[it.suit] ?: it.suit}" }.joinToString(" ")
                        val commStr = result.communityCards.map { "${if(it.rank=="T") "10" else it.rank}${suitSym[it.suit] ?: it.suit}" }.joinToString(" ")
                        val streetStr = streetCN[result.street] ?: result.street
                        var recText = "🔍 手牌: $holeStr"
                        if (commStr.isNotEmpty()) recText += " | 公共: $commStr"
                        recText += " | $streetStr | ${result.totalPlayers}人"
                        if (result.toCall > 0) recText += " | 跟注${result.toCall}"
                        val apiError = VisionApiClient.lastError
                        if (apiError.isNotEmpty()) {
                            recText += " ⚠️$apiError"
                            tvRecResult?.setBackgroundColor(0xFF8B0000.toInt())
                        } else {
                            tvRecResult?.setBackgroundColor(0xFF1A237E.toInt())
                        }
                        tvRecResult?.text = recText
                        tvRecResult?.visibility = View.VISIBLE
                        // V2.9.38: 隐身模式也更新通知
                        if (isStealthMode) {
                            updateAdviceNotification("✅ $holeStr $streetStr ${result.totalPlayers}人", commStr)
                        }
                    }
                } else {
                    handler.post {
                        tvAction?.alpha = 1.0f
                        tvStatus?.text = "❌ API: ${VisionApiClient.lastError.take(30)}"
                        executeJs("document.body.classList.remove('api-processing')")
                        if (isStealthMode) updateAdviceNotification("API识别失败", VisionApiClient.lastError.take(50))
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    tvAction?.alpha = 1.0f
                    tvStatus?.text = "❌ API错误"
                    executeJs("document.body.classList.remove('api-processing')")
                    if (isStealthMode) updateAdviceNotification("API错误", e.message?.take(50) ?: "")
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
                CHANNEL_ID, "截屏优化", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "点击通知截屏识别"
                setShowBadge(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        // V2.9.39: 点击通知直接截屏 + 快捷按钮
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("点击截屏识别")
                .setContentText("点一下即可截屏分析")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setStyle(Notification.BigTextStyle()
                    .setBigContentTitle("点击即可截屏")
                    .bigText("点击通知直接截屏分析，也可用通知栏顶部「截屏优化」瓷砖"))

            // ★ 点击通知本身 → 触发截屏（最直观的操作方式）
            val captureIntent = Intent(ACTION_CAPTURE)
            captureIntent.setPackage(packageName)
            val capturePending = PendingIntent.getBroadcast(this, 0, captureIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.setContentIntent(capturePending)

            // 额外操作按钮
            val voiceIntent = Intent(ACTION_VOICE)
            val voicePending = PendingIntent.getBroadcast(this, 2, voiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_btn_speak_now, "语音", voicePending)

            val openIntent = Intent(ACTION_OPEN)
            val openPending = PendingIntent.getBroadcast(this, 3, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_menu_view, "打开App", openPending)

            builder.build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("点击截屏识别")
                .setContentText("点一下即可截屏分析")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_HIGH)
                .build()
        }
    }

    /**
     * V2.9.38: 更新通知栏显示建议内容（隐身模式专用）
     */
    fun updateAdviceNotification(title: String, detail: String) {
        try {
            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val builder = Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setOngoing(true)
                    .setCategory(Notification.CATEGORY_SERVICE)
                    .setVisibility(Notification.VISIBILITY_PUBLIC)

                builder.setContentTitle(title)
                    .setContentText(detail.ifEmpty { "点击截屏识别" })

                // ★ 点击通知 → 触发截屏
                val captureIntent = Intent(ACTION_CAPTURE)
                captureIntent.setPackage(packageName)
                val capturePending = PendingIntent.getBroadcast(this, 0, captureIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.setContentIntent(capturePending)

                // 额外操作按钮
                val voiceIntent = Intent(ACTION_VOICE)
                val voicePending = PendingIntent.getBroadcast(this, 2, voiceIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(android.R.drawable.ic_btn_speak_now, "语音", voicePending)

                val openIntent = Intent(ACTION_OPEN)
                val openPending = PendingIntent.getBroadcast(this, 3, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(android.R.drawable.ic_menu_view, "打开App", openPending)

                builder.setStyle(Notification.BigTextStyle()
                    .setBigContentTitle(title)
                    .bigText(detail.ifEmpty { "点击截屏识别牌面" }))

                builder.build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
                    .setContentTitle(title)
                    .setContentText(detail)
                    .setSmallIcon(android.R.drawable.ic_menu_compass)
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .build()
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {}
    }
}
