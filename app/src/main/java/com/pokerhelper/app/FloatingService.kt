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
import android.os.PowerManager
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import java.io.File
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable

class FloatingService : Service() {

    companion object {
        private const val TAG = "FloatingService"
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
        const val ACTION_EXPORT = "com.pokerhelper.app.EXPORT"
    }

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var webView: WebView? = null
    private var tvStatus: TextView? = null
    private var tvRecResult: TextView? = null
    private var tvRecDetail: TextView? = null  // V2.9.43: 识别详情（底池/跟注/盲注）
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
    private val BALL_SIZE_DP = 64  // V2.9.107: 死代码清理+HTTP复用
    private val KEY_BALL_X = "ball_x"
    private val KEY_BALL_Y = "ball_y"

    // V2.9.68: WakeLock保活，防止CPU休眠导致服务被杀
    private var wakeLock: PowerManager.WakeLock? = null

    // V2.9.165: 本地CV牌面识别——纯Bitmap实现，零外部依赖
    private var cardRecognizer: CardRecognizer? = null
    private var localCVEnabled = true  // 本地CV开关

    // V2.9.153: AutoCapture
    private var autoCaptureEnabled = false
    private var autoCaptureRunnable: Runnable? = null
    private var autoCaptureInterval = 4000L
    // V2.9.183: 根据street自适应截屏间隔——翻前慢翻后快
    private fun updateAutoCaptureInterval(street: String) {
        autoCaptureInterval = when (street.lowercase()) {
            "preflop" -> 4000L
            "flop" -> 2500L
            "turn" -> 2000L
            "river" -> 2000L
            else -> 4000L
        }
    }
    // V2.9.180: 最新按钮坐标（Vision API返回，用于全自动执行）
    private var latestButtonPositions = emptyList<VisionApiClient.ButtonPosition>()
    // V2.9.207: 缓存场景数据——用于本地CV快速通道（跳过VLM）
    private var cachedPotSize: Int = 0
    private var cachedToCall: Int = 0
    private var cachedMinRaise: Int = 0
    private var cachedBlindSB: Int = 0
    private var cachedBlindBB: Int = 0
    private var cachedTotalPlayers: Int = 6
    private var cachedActivePlayers: Int = 3
    private var cachedMyPosition: String = "BTN"
    private var cachedPlayerChips: Int = 0
    private val FAST_PATH_MIN_CONFIDENCE = 0.85f
    private var screenWidth = 1080
    private var screenHeight = 2344
    private var isVisionInProgress = false
    private var autoConsecutiveErrors = 0
    private val AUTO_MAX_ERRORS = 3
    // V2.9.206: Shot Clock保护——记录上次决策时间，超时强制行动
    private var lastDecisionTime: Long = 0
    private val SHOT_CLOCK_TIMEOUT = 18000L // 18秒超时（GG默认30秒，留12秒余量）
    // V2.9.207: 记录当前手牌开始分析时间——修复Shot Clock新牌局永远不触发的bug
    private var handStartTime: Long = 0
    private var manualErrorCount = 0  // V2.9.184: 手动截屏连续失败计数
    private var multiFrameDelay = 1500L  // V2.9.184: 200→1500ms，给API调用留足时间

    // V2.9.4: WebView加载追踪 + JS调用队列
    private var webViewReady = false
    @Volatile private var _strategyReceived = false  // V2.9.113: 策略引擎是否已回调
    private var _strategyTimeoutRunnable: Runnable? = null  // V2.9.125: 策略超时定时器引用
    // V2.9.207: Shot Clock硬超时定时器——16秒强制弃牌（比SHOT_CLOCK_TIMEOUT早2秒，留缓冲）
    private var _shotClockRunnable: Runnable? = null
    private var _lastStrategyAdvice = ""   // V2.9.113: 最后策略结果
    // V2.9.155: 崩溃状态——JS ReferenceError/未捕获异常时悬浮球显示「崩」+红+快闪
    private var _isCrashed = false
    private var _lastCrashReason = ""
    private val pendingJsCalls = mutableListOf<String>()
    // V2.9.167: 诊断日志变量——记录每次识别的完整信息
    private var _diagStartTime = 0L
    private var _diagLocalCVTimeMs = 0L
    private var _diagLocalHandCards = emptyList<VisionApiClient.CardInfo>()
    private var _diagLocalCommunityCards = emptyList<VisionApiClient.CardInfo>()
    private var _diagLocalStreet: String? = null
    // V2.9.114: WebViewAssetLoader——Google官方推荐的本地HTML加载方案
    private lateinit var assetLoader: WebViewAssetLoader
    // V2.9.70: 错误日志——API/截屏失败时记录，豪哥可导出反馈
    private val errorLogs = mutableListOf<String>()
    private val ERROR_LOG_FILE = "error_logs.txt"
    private val MAX_ERROR_LOGS = 50
    private var isBlinkingError = false
    
    // V2.9.183: errorLogs文件持久化——防止重启丢失
    private fun loadErrorLogs() {
        try {
            val file = File(filesDir, ERROR_LOG_FILE)
            if (file.exists()) {
                errorLogs.clear()
                errorLogs.addAll(file.readLines().takeLast(MAX_ERROR_LOGS))
            }
        } catch (_: Exception) {}
    }
    
    private fun addErrorLog(entry: String) {
        errorLogs.add(entry)
        if (errorLogs.size > MAX_ERROR_LOGS) errorLogs.removeAt(0)
        try {
            File(filesDir, ERROR_LOG_FILE).appendText(entry + "\n", Charsets.UTF_8)
            // 滚动保留最近200行
            val file = File(filesDir, ERROR_LOG_FILE)
            if (file.length() > 32768) {
                val lines = file.readLines()
                file.writeText(lines.takeLast(MAX_ERROR_LOGS).joinToString("\n") + "\n")
            }
        } catch (_: Exception) {}
    }

    // V2.9.112: BLE ESP32连接
    private var bleManager: Esp32BleManager? = null
    private var tvBle: TextView? = null
    // V2.9.173: BLE诊断信息独立显示区，不被tap结果覆盖
    private var tvBleStatus: TextView? = null
    private var bleStatusPending = false  // V2.9.184: 用标志位替代字符串比较

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
                ACTION_EXPORT -> exportLogFromNotification()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        // V2.9.125: 处理START action——系统杀后台后重启走这里而非onCreate
        // 如果窗口/悬浮球未创建，必须重新初始化，否则策略引擎链路断裂
        if (floatingView == null || floatingBall == null) {
            Log.i("FloatingService", "onStartCommand: window/ball null, re-initializing")
            try {
                showFloatingWindow()
                showFloatingBall()
                reinitializeComponents()  // V2.9.184: 恢复CardRecognizer/语音/BLE/广播接收器
            } catch (e: Exception) {
                Log.e("FloatingService", "onStartCommand re-init failed", e)
            }
        }
        // 确保前台服务通知存在
        try {
            val notification = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e("FloatingService", "onStartCommand startForeground failed", e)
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        
        // V2.9.182: 崩溃保护——兜底写入日志到文件
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "★ 应用崩溃: ${throwable.message}", throwable)
            DiagnosticLogger.flushCrashLog(throwable)
            // 交给系统默认处理器（显示崩溃对话框）
            defaultHandler?.uncaughtException(thread, throwable)
        }
        createNotificationChannel()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        isStealthMode = prefs?.getBoolean(KEY_STEALTH_MODE, false) ?: false
        loadErrorLogs()  // V2.9.183: 加载持久化错误日志

        // V2.9.200: 初始化游戏模式配置（读取用户上次选择的平台）
        GameModeConfig.init(this)

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        isRunning = true

        // V2.9.68: WakeLock保活——防止一加/小米等杀后台
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pokerhelper::FloatingService")
            wakeLock?.acquire(4 * 60 * 60 * 1000L) // 最长4小时
        } catch (e: Exception) {
            Log.w("FloatingService", "WakeLock acquire failed", e)
        }

        // V2.9.38: 注册通知按钮广播接收器
        val filter = IntentFilter().apply {
            addAction(ACTION_CAPTURE)
            addAction(ACTION_VOICE)
            addAction(ACTION_OPEN)
            addAction(ACTION_EXPORT)
        }
        // V2.9.38: Android 14+必须指定RECEIVER_EXPORTED/RECEIVER_NOT_EXPORTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(notificationReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(notificationReceiver, filter)
        }

        // V2.9.165: 初始化本地CV牌面识别器
        try {
            cardRecognizer = CardRecognizer(this)
            cardRecognizer?.init()
            CardRecognizer.updateScreenSize(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)  // V2.9.184
            Log.i(TAG, "本地CV识别器初始化完成")
        } catch (e: Exception) {
            Log.e(TAG, "本地CV识别器初始化失败", e)
            localCVEnabled = false
        }

        initSpeechRecognizer()
        showFloatingWindow()
        showFloatingBall()

        // V2.9.112: 初始化BLE管理器
        bleManager = Esp32BleManager(this)
        bleManager?.onStatusChanged = { connected, message ->
            handler.post {
                tvBle?.text = if (connected) "🔗" else "📡"
                tvBle?.setTextColor(if (connected) 0xFF4ade80.toInt() else 0xFFBDBDBD.toInt())
                tvStatus?.text = "BLE: $message"
                // V2.9.173: 连接成功后自动发送status查询USB/HID状态
                if (connected) {
                    tvBleStatus?.text = "查询ESP32状态..."
                    tvBleStatus?.visibility = View.VISIBLE
                    bleStatusPending = true  // V2.9.184
                    handler.postDelayed({ bleManager?.sendStatus() }, 500)
                    // V2.9.174: 5秒无响应超时
                    handler.postDelayed({
                        if (bleStatusPending) {
                            tvBleStatus?.text = "ESP32: status无响应"
                            bleStatusPending = false
                        }
                    }, 5500)
                }
            }
        }
        bleManager?.onCommandResult = { result ->
            handler.post {
                bleStatusPending = false  // V2.9.184: 收到响应，取消超时
                // V2.9.176: 放宽匹配——任何ok:开头或err:开头的都可能是status结果
                // status回复格式: ok:ver=...,heap=...,usb=ok/no,hid=ok/no,...
                if (result.startsWith("ok:") || result.startsWith("查询ESP32")) {
                    // V2.9.176: 将逗号分隔的字段格式化为多行显示，便于查看
                    val formattedResult = if (result.startsWith("ok:")) {
                        val fields = result.removePrefix("ok:")
                        "ESP32状态:\n" + fields.split(",").joinToString("\n") { "  $it" }
                    } else {
                        "ESP32: $result"
                    }
                    tvBleStatus?.text = formattedResult
                    tvBleStatus?.visibility = View.VISIBLE
                } else {
                    tvStatus?.text = "ESP32: $result"
                }
            }
        }
    }

    // V2.9.184: 服务重启后恢复关键组件——onStartCommand(START_STICKY)重启时onCreate不会被调用
    private fun reinitializeComponents() {
        // 重新注册通知广播接收器
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_CAPTURE)
                addAction(ACTION_VOICE)
                addAction(ACTION_OPEN)
                addAction(ACTION_EXPORT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                registerReceiver(notificationReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(notificationReceiver, filter)
            }
        } catch (_: Exception) {}
        
        // 重新初始化本地CV
        try {
            cardRecognizer = CardRecognizer(this)
            cardRecognizer?.init()
            CardRecognizer.updateScreenSize(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)  // V2.9.184
            localCVEnabled = true
            Log.i(TAG, "reinit: CardRecognizer OK")
        } catch (e: Exception) {
            localCVEnabled = false
            Log.w(TAG, "reinit: CardRecognizer failed", e)
        }
        
        // 重新初始化语音识别
        initSpeechRecognizer()
        
        // 重新初始化BLE
        bleManager = Esp32BleManager(this)
        bleManager?.onStatusChanged = { connected, message ->
            handler.post {
                tvBle?.text = if (connected) "🔗" else "📡"
                tvBle?.setTextColor(if (connected) 0xFF4ade80.toInt() else 0xFFBDBDBD.toInt())
                tvStatus?.text = "BLE: $message"
                if (connected) {
                    tvBleStatus?.text = "查询ESP32状态..."
                    tvBleStatus?.visibility = View.VISIBLE
                    bleStatusPending = true
                    handler.postDelayed({ bleManager?.sendStatus() }, 500)
                    handler.postDelayed({
                        if (bleStatusPending) {
                            tvBleStatus?.text = "ESP32: status无响应"
                            bleStatusPending = false
                        }
                    }, 5500)
                }
            }
        }
        bleManager?.onCommandResult = { result ->
            handler.post {
                bleStatusPending = false  // V2.9.184: 收到响应，取消超时
                if (result.startsWith("ok:") || result.startsWith("查询ESP32")) {
                    val formattedResult = if (result.startsWith("ok:")) {
                        val fields = result.removePrefix("ok:")
                        "ESP32状态:\n" + fields.split(",").joinToString("\n") { "  $it" }
                    } else "ESP32: $result"
                    tvBleStatus?.text = formattedResult
                    tvBleStatus?.visibility = View.VISIBLE
                } else {
                    tvStatus?.text = "ESP32: $result"
                }
            }
        }
        Log.i(TAG, "reinit: all components restored")
    }

    override fun onDestroy() {
        isRunning = false
        // V2.9.68: 释放WakeLock
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        currentPanelWidth = 0
        currentPanelHeight = 0
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        // V2.9.165: 释放本地CV识别器资源
        try { cardRecognizer?.release() } catch (_: Exception) {}
        cardRecognizer = null

        // V2.9.112: 断开BLE连接
        bleManager?.disconnect()
        bleManager = null
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
        } else {
            // V2.9.184: 启动时提示语音不可用
            handler.postDelayed({
                tvStatus?.text = "⚠️ 语音不可用"
            }, 3000)
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

    
    // V2.9.153: AutoCapture
    fun toggleAutoCapture() {
        if (autoCaptureEnabled) {
            stopAutoCapture()
            updateBallAdvice("COLOR:CHECK|SIGNAL:NONE|REASON:自动关闭")
            updateAdviceNotification("⏸ 全自动已停止", "手动模式")
            floatingBall?.let { b ->
                (b.background as? GradientDrawable)?.let { shape ->
                    shape.setColor(0xDD333333.toInt())
                    shape.setStroke(0, 0)
                }
                b.text = "♠"; b.textSize = 18f
            }
        } else {
            startAutoCapture()
            updateAdviceNotification("🟢 全自动运行中", "截屏→分析→自动执行")
            floatingBall?.let { b ->
                (b.background as? GradientDrawable)?.let { shape ->
                    shape.setColor(0xDD00C853.toInt())
                    val density = resources.displayMetrics.density
                    shape.setStroke((3 * density).toInt(), 0xFF00E676.toInt())
                }
                b.text = "▶"; b.textSize = 16f
            }
        }
    }
    private fun startAutoCapture() { autoCaptureEnabled=true; autoConsecutiveErrors=0; lastDecisionTime=0; handStartTime=0; isVisionInProgress=false; autoCaptureInterval=4000L; executeJs("if(typeof enableAutoExec==='function')enableAutoExec()"); scheduleNextAutoCapture() }
    private fun stopAutoCapture() { autoCaptureEnabled=false; autoCaptureRunnable?.let{handler.removeCallbacks(it)}; autoCaptureRunnable=null; _shotClockRunnable?.let{handler.removeCallbacks(it)}; _shotClockRunnable=null; handStartTime=0; isVisionInProgress=false; executeJs("if(typeof disableAutoExec==='function')disableAutoExec()") }
    private fun scheduleNextAutoCapture() {
        if(!autoCaptureEnabled)return; autoCaptureRunnable?.let{handler.removeCallbacks(it)}
        val r=Runnable{if(!autoCaptureEnabled)return@Runnable;if(isVisionInProgress){scheduleNextAutoCapture();return@Runnable};val pm=getSystemService(Context.POWER_SERVICE)as PowerManager;if(!pm.isScreenOn){scheduleNextAutoCapture();return@Runnable};autoCaptureTrigger()}
        autoCaptureRunnable=r; handler.postDelayed(r,autoCaptureInterval)
    }
    private fun autoCaptureTrigger() {
        if(!ScreenOptService.isServiceRunning()){autoConsecutiveErrors++;checkAutoErrors();scheduleNextAutoCapture();return}
        isVisionInProgress=true
        hideOverlay()  // V2.9.190: 截屏前隐藏悬浮层
        ScreenOptService.onScreenshotReady={s->handler.post{
            showOverlay()  // V2.9.190: 截屏后恢复悬浮层
            if(s)processScreenshotAndAnalyze(isAutoCapture=true)else{isVisionInProgress=false;autoConsecutiveErrors++;checkAutoErrors();scheduleNextAutoCapture()}
        }}
        handler.postDelayed({ScreenOptService.captureScreen()}, 100)  // V2.9.192: 延迟100ms等View渲染
    }
// V2.9.190: 截屏前隐藏悬浮层，避免日志面板遮挡扑克桌面
    private fun hideOverlay() {
        try {
            floatingView?.visibility = android.view.View.GONE
            floatingBall?.visibility = android.view.View.GONE
        } catch (_: Exception) {}
    }
    
    private fun showOverlay() {
        try {
            floatingView?.visibility = android.view.View.VISIBLE
            floatingBall?.visibility = android.view.View.VISIBLE
        } catch (_: Exception) {}
    }
    fun onAutoCaptureVisionDone(success:Boolean){isVisionInProgress=false;if(success)autoConsecutiveErrors=0 else{autoConsecutiveErrors++;checkAutoErrors()};if(success)executeJs("if(typeof FrameDiffEngine!=='undefined')FrameDiffEngine.onAutoFrameDone()");scheduleNextAutoCapture()}
    // V2.9.180: 全自动执行tap——根据action匹配按钮坐标并发送到ESP32
    private fun executeAutoTap(action: String, decisionData: org.json.JSONObject) {
        try {
            // V2.9.207: Shot Clock保护——检查从手牌开始分析是否超时
            val now = System.currentTimeMillis()
            if (handStartTime > 0 && (now - handStartTime) > SHOT_CLOCK_TIMEOUT) {
                Log.w(TAG, "★ Shot Clock timeout! ${(now - handStartTime)}ms since hand start, forcing emergency fold")
                executeAutoTapFallback("fold")
                handStartTime = 0; _shotClockRunnable?.let { handler.removeCallbacks(it) }
                lastDecisionTime = now
                updateAdviceNotification("⏰ Shot Clock", "超时强制弃牌")
                updateBallAdvice("COLOR:FOLD|SIGNAL:TIMEOUT|REASON:Shot Clock超时")
                return
            }
            
            // V2.9.206: GG扑克下注金额自动输入——先点预设%按钮，再点加注确认
            if (action == "raise" || action == "raise_big") {
                val sizing = decisionData.optInt("sizing", 0)
                val pot = decisionData.optInt("pot", 0)
                if (sizing > 0 && pot > 0 && GameModeConfig.currentPlatform == GamePlatform.GGPOKER) {
                    val betBtnAction = GameModeConfig.getBetButtonAction(sizing, pot)
                    Log.d(TAG, "★ GG bet sizing: action=$action sizing=$sizing pot=$pot → $betBtnAction")
                    // 先点击下注预设按钮
                    executeAutoTapFallback(betBtnAction)
                    // 延迟200ms后点击加注按钮确认
                    handler.postDelayed({
                        try {
                            executeAutoTapFallback("raise")
                            handStartTime = 0; _shotClockRunnable?.let { handler.removeCallbacks(it) }; lastDecisionTime = System.currentTimeMillis()
                            Log.d(TAG, "★ GG bet confirm: raise button tapped")
                        } catch (e: Exception) {
                            Log.e(TAG, "GG bet confirm error", e)
                        }
                    }, 200)
                    return
                }
            }
            
            val btns = latestButtonPositions
            if (btns.isEmpty()) {
                Log.w(TAG, "autoTap: 无按钮坐标，回退固定位置")
                executeAutoTapFallback(action)
                return
            }
            
            // 根据action匹配按钮
            // V2.9.205: 增强按钮文字匹配——GG扑克按钮文字会变(如加注→All In)，需识别内容再匹配
            val targetBtn = when (action) {
                "fold" -> btns.find { it.text.contains("弃牌") || it.text.contains("fold", true) }
                "check" -> btns.find { it.text.contains("让牌") || it.text.contains("过牌") || it.text.contains("check", true) }
                    ?: btns.find { it.text.contains("跟注") || it.text.contains("call", true) } // check→call fallback
                "call", "weak_call" -> btns.find { it.text.contains("跟注") || it.text.contains("call", true) }
                    ?: btns.find { it.text.contains("让牌") || it.text.contains("过牌") || it.text.contains("check", true) } // call→check fallback
                "raise", "raise_big" -> btns.find { it.text.contains("加注") || it.text.contains("下注") || it.text.contains("raise", true) || it.text.contains("bet", true) }
                    ?: btns.find { it.text.contains("全押") || it.text.contains("全下") || it.text.contains("all", true) } // raise→allin fallback (短码时)
                "allin" -> btns.find { it.text.contains("全押") || it.text.contains("全下") || it.text.contains("all", true) }
                    ?: btns.find { it.text.contains("加注") || it.text.contains("下注") || it.text.contains("raise", true) || it.text.contains("bet", true) } // allin→raise fallback
                else -> btns.find { it.text.contains(action, true) }
            }
            
            if (targetBtn != null) {
                val x = (targetBtn.xPct * screenWidth).toInt().coerceIn(0, screenWidth - 1)
                val y = (targetBtn.yPct * screenHeight).toInt().coerceIn(0, screenHeight - 1)
                Log.d(TAG, "★ autoTap: $action → ($x, $y) btn=${targetBtn.text}")
                bleManager?.sendTap(x, y, 50)
                handStartTime = 0; _shotClockRunnable?.let { handler.removeCallbacks(it) }; lastDecisionTime = System.currentTimeMillis()
            } else {
                Log.w(TAG, "autoTap: 未匹配按钮 $action, 回退固定位置")
                executeAutoTapFallback(action)
                handStartTime = 0; _shotClockRunnable?.let { handler.removeCallbacks(it) }; lastDecisionTime = System.currentTimeMillis()
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeAutoTap error", e)
            executeAutoTapFallback(action)
        }
    }
    // V2.9.200: 回退动态坐标——使用GameModeConfig根据当前平台自动适配
    private fun executeAutoTapFallback(action: String) {
        val (sw, sh) = getScreenSize()
        val (x, y) = GameModeConfig.getAutoTapFallback(action, sw, sh)
        Log.d(TAG, "★ autoTapFallback: $action → ($x, $y) [screen=${sw}x${sh} platform=${GameModeConfig.currentPlatform}]")
        bleManager?.sendTap(x, y, 50)
        handStartTime = 0; _shotClockRunnable?.let { handler.removeCallbacks(it) }; lastDecisionTime = System.currentTimeMillis()
    }
    // V2.9.210: D按钮座位号→Hero位置名称
    private fun seatIndexToPosition(dealerSeat: Int, totalPlayers: Int): String {
        // Hero固定在座位6，计算Hero相对于D按钮的位置偏移
        val offset = (6 - dealerSeat + 6) % 6
        return when (offset) {
            0 -> "BTN"
            1 -> "SB"
            2 -> "BB"
            3 -> "UTG"
            4 -> if (totalPlayers <= 4) "CO" else "MP"
            5 -> "CO"
            else -> "BTN"
        }
    }

    // V2.9.208: 根据toCall推断按钮坐标（当latestButtonPositions为空时使用GG固定坐标）
    private fun inferButtonPositions(toCall: Int): List<VisionApiClient.ButtonPosition> {
        val isGG = GameModeConfig.currentPlatform == GamePlatform.GGPOKER
        return if (isGG) {
            if (toCall > 0) {
                listOf(
                    VisionApiClient.ButtonPosition("Fold", 0.181, 0.960),
                    VisionApiClient.ButtonPosition("Call", 0.500, 0.960),
                    VisionApiClient.ButtonPosition("Raise", 0.819, 0.960)
                )
            } else {
                listOf(
                    VisionApiClient.ButtonPosition("Check", 0.500, 0.960),
                    VisionApiClient.ButtonPosition("Bet", 0.819, 0.960)
                )
            }
        } else {
            if (toCall > 0) {
                listOf(
                    VisionApiClient.ButtonPosition("弃牌", 0.17, 0.88),
                    VisionApiClient.ButtonPosition("跟注", 0.50, 0.88),
                    VisionApiClient.ButtonPosition("加注", 0.83, 0.88)
                )
            } else {
                listOf(
                    VisionApiClient.ButtonPosition("过牌", 0.50, 0.88),
                    VisionApiClient.ButtonPosition("下注", 0.50, 0.88)
                )
            }
        }
    }

    private fun checkAutoErrors(){if(autoConsecutiveErrors>=AUTO_MAX_ERRORS){stopAutoCapture();updateBallAdvice("COLOR:FOLD|SIGNAL:COUNTER|REASON:自动暂停");updateAdviceNotification("⚠️ 自动模式暂停","连续${AUTO_MAX_ERRORS}次错误")}}
    fun triggerMultiFrameCapture(){
        if(!ScreenOptService.isServiceRunning())return;if(isVisionInProgress)return
        isVisionInProgress=true
        hideOverlay()  // V2.9.190: 截屏前隐藏悬浮层
        ScreenOptService.onScreenshotReady={s->handler.post{if(s){processScreenshotAndAnalyze(isMultiFrame1=true);handler.postDelayed({if(ScreenOptService.isServiceRunning()){ScreenOptService.onScreenshotReady={s2->handler.post{showOverlay()  // V2.9.190: 第二帧截屏后恢复悬浮层
if(s2){isVisionInProgress=false;processScreenshotAndAnalyze(isMultiFrame2=true)}else isVisionInProgress=false}};ScreenOptService.captureScreen()}},multiFrameDelay)}else{showOverlay();isVisionInProgress=false}}}
        handler.postDelayed({ScreenOptService.captureScreen()}, 100)  // V2.9.192: 延迟100ms等View渲染
    }
    fun setAutoCaptureSpeed(ms:Long){autoCaptureInterval=ms.coerceIn(2000L,10000L);if(autoCaptureEnabled)scheduleNextAutoCapture()}

/**
     * V2.9.38: 触发截屏（通知栏按钮调用）
     */
    private fun triggerCapture() {
        Log.d(TAG, "★ triggerCapture开始: webViewReady=$webViewReady, stealth=$isStealthMode, screenOpt=${ScreenOptService.isServiceRunning()}, apiKey=${VisionApiClient.apiKey.takeLast(4)}")
        executeJs("if(typeof clr==='function'){clr()}")
        tvRecResult?.text = ""
        tvRecResult?.visibility = View.GONE
        tvRecDetail?.text = ""
        tvRecDetail?.visibility = View.GONE
        tvStatus?.text = "🎯 截屏中..."
        executeJs("document.body.classList.add('api-processing')")
        tvAction?.alpha = 0.5f
        // V2.9.109: 诊断通知（不分stealth，始终更新通知方便排查）
        updateAdviceNotification("1/4 截屏中", "无障碍=${ScreenOptService.isServiceRunning()}")

        if (ScreenOptService.isServiceRunning()) {
            hideOverlay()  // V2.9.190: 截屏前隐藏悬浮层
            ScreenOptService.onScreenshotReady = { success ->
                handler.post {
                    showOverlay()  // V2.9.190: 截屏后恢复悬浮层
                    if (success) {
                        Log.d(TAG, "★ 截屏成功，进入processScreenshotAndAnalyze")
                        manualErrorCount = 0  // V2.9.184: 重置手动截屏错误计数
                        updateAdviceNotification("2/4 截屏成功", "正在调用API识别...")
                        processScreenshotAndAnalyze()
                    } else {
                        Log.e(TAG, "★ 截屏失败: ${ScreenCaptureService.lastError}")
                        manualErrorCount++  // V2.9.184: 手动截屏失败计数
                        val errMsg = if (manualErrorCount >= 5) "⚠️ 连续${manualErrorCount}次失败，请检查无障碍" else "❌ 截图失败，请重试"
                        tvStatus?.text = errMsg
                        tvAction?.alpha = 1.0f
                        executeJs("document.body.classList.remove('api-processing')")
                        updateAdviceNotification("❌ 截屏失败", ScreenCaptureService.lastError.take(30))
                        // V2.9.109: 失败也变红
                        updateBallAdvice("COLOR:FOLD|SIGNAL:ERROR")

                    }
                }
            }
            handler.postDelayed({ScreenOptService.captureScreen()}, 100)  // V2.9.192: 延迟100ms等View渲染
        } else {
            Log.e(TAG, "★ 无障碍服务未运行！")
            tvStatus?.text = "⚠️ 请先开启无障碍服务！"
            tvAction?.alpha = 1.0f
            executeJs("document.body.classList.remove('api-processing')")
            updateAdviceNotification("❌ 无障碍未开启", "请回App开启后重试")
            // V2.9.109: 无障碍没开也变红
            updateBallAdvice("COLOR:FOLD|SIGNAL:NO_TABLE")
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

        // Top bar with buttons - V2.9.177: 增大内边距
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x990a1a0a.toInt())
            setPadding(6, 4, 6, 4)
        }

        tvStatus = TextView(this).apply {
            text = "青云 v2.9.179"
            setTextColor(0xFFe8edf5.toInt())
            textSize = 12f
            setPadding(4, 2, 4, 2)
        }

        // V2.0: 识别结果展示行 - v2.9.35: 紧凑半透明
        // V2.9.43: 第一行显示手牌/桌型/阶段，第二行显示底池/跟注/盲注详情
        tvRecResult = TextView(this).apply {
            text = ""
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 11f  // V2.9.43: 9f→11f 更醒目
            setPadding(6, 2, 6, 2)
            setBackgroundColor(0x990a1a0a.toInt())
            visibility = View.GONE
        }

        // V2.9.43: 识别详情行（底池/跟注/盲注）
        tvRecDetail = TextView(this).apply {
            text = ""
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 8f
            setPadding(6, 0, 6, 2)
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

        // V2.9.112: BLE连接按钮
        tvBle = TextView(this).apply {
            text = "📡"
            setTextColor(0xFFBDBDBD.toInt())
            textSize = 14f
            setPadding(6, 2, 6, 2)
            setBackgroundColor(0x00000000)
            setOnClickListener {
                if (bleManager?.isConnected == true) {
                    // 已连接，点击发送tap测试
                    bleManager?.sendTap(540, 1172, 50)
                    tvStatus?.text = "发送tap测试..."
                } else {
                    // 未连接，开始扫描连接
                    bleManager?.startScan()
                    tvStatus?.text = "扫描ESP32..."
                }
            }
            setOnLongClickListener {
                // 长按断开连接
                bleManager?.disconnect()
                tvStatus?.text = "BLE已断开"
                true
            }
        }

        // V2.9.200: 平台切换按钮（标准/GG/短牌）
        val tvPlatform = TextView(this).apply {
            text = "🎮"
            setTextColor(0xFF4ade80.toInt())
            textSize = 14f
            setPadding(6, 2, 6, 2)
            setBackgroundColor(0x00000000)
            setOnClickListener {
                // 循环切换：STANDARD → GGPOKER → SHORT_DECK → STANDARD
                val nextPlatform = when (GameModeConfig.currentPlatform) {
                    GamePlatform.STANDARD -> GamePlatform.GGPOKER
                    GamePlatform.GGPOKER -> GamePlatform.SHORT_DECK
                    GamePlatform.SHORT_DECK -> GamePlatform.STANDARD
                }
                GameModeConfig.setPlatform(nextPlatform)
                // 更新CardRecognizer坐标
                CardRecognizer.applyGameMode()
                // 更新按钮文字颜色提示当前平台
                when (nextPlatform) {
                    GamePlatform.STANDARD -> setTextColor(0xFF4ade80.toInt())
                    GamePlatform.GGPOKER -> setTextColor(0xFFfbbf24.toInt())  // GG=金色
                    GamePlatform.SHORT_DECK -> setTextColor(0xFFf472b6.toInt())  // 短牌=粉色
                }
                tvStatus?.text = "已切换到${nextPlatform.displayName}"
                Log.i(TAG, "平台切换: ${nextPlatform.displayName}")
                // 重置手牌锁定，避免跨平台锁定污染
                VisionApiClient.holeCardsLocked = null
                VisionApiClient.holeCardsRankLocked = null
                // dButtonLocked 有 private set，通过下次截图的内部逻辑自动重置
                VisionApiClient.streetLocked = null
                latestButtonPositions = emptyList()
            }
            setOnLongClickListener {
                // 长按显示当前平台全名
                tvStatus?.text = "当前平台: ${GameModeConfig.currentPlatform.displayName}"
                true
            }
        }

        // V2.9.177: BLE诊断信息独立显示行，增大字体+多行显示，区域加大
        tvBleStatus = TextView(this).apply {
            text = ""
            setTextColor(0xFF90caf9.toInt())
            textSize = 14f
            setPadding(8, 4, 8, 4)
            maxLines = 10
            setSingleLine(false)
            setBackgroundColor(0xCC001a33.toInt())
            visibility = View.GONE
        }

        topBar.addView(tvStatus, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topBar.addView(tvAction!!)
        topBar.addView(tvVoice)
        topBar.addView(tvReset)
        topBar.addView(tvBle!!)
        topBar.addView(tvPlatform)  // V2.9.200: 平台切换按钮
        topBar.addView(tvCollapse)
        container.addView(topBar)
        container.addView(tvBleStatus!!, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(tvRecResult!!, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(tvRecDetail!!, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))  // V2.9.43: 详情行

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

        // V2.9.114: WebViewAssetLoader——Google官方推荐方案，无竞态、JS完整保留
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .setDomain("appassets.androidplatform.net")
            .build()

        // V2.9.109: 清除WebView缓存，防止加载旧版HTML
        wv.clearCache(true)
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                // V2.9.114: 用WebViewAssetLoader拦截本地资源请求
                val url = request?.url
                if (url != null) {
                    val response = assetLoader.shouldInterceptRequest(url)
                    if (response != null) return response
                }
                return super.shouldInterceptRequest(view, request)
            }
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                Log.e(TAG, "WebView加载失败: code=$errorCode desc=$description url=$failingUrl")
                addErrorLog("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} WebView错误: $errorCode $description")
                // V2.9.114: 用AssetLoader URL重载（不依赖HTTP服务器）
                wv.postDelayed({ wv.loadUrl("https://appassets.androidplatform.net/assets/poker_helper.html") }, 1000)
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "★ WebView加载完成: url=$url")
                if (!webViewReady) {
                    webViewReady = true
                    val calls = ArrayList(pendingJsCalls)
                    pendingJsCalls.clear()
                    for (js in calls) {
                        view?.evaluateJavascript(js, null)
                    }
                }
                // V2.9.114: 验证策略引擎是否真的加载了
                view?.evaluateJavascript("if(typeof onVisionResult==='function'){console.log('[V2.9.125] ✅策略引擎就绪')}else{console.log('[V2.9.125] ❌策略引擎未加载！')}", null)
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            // V2.9.182: 捕获JS端console.log，写入文件日志
            override fun onConsoleMessage(consoleMessage: android.webkit.ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    val msg = "[${it.sourceId() ?: "JS"}:${it.lineNumber()}] ${it.message()}"
                    Log.d("WebViewConsole", msg)
                    DiagnosticLogger.logJsConsole(msg)
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }

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
            
            // V2.9.155: JS策略引擎崩溃回调——立即把悬浮球变「崩」+FOLD红+4Hz快闪+通知栏警告
            // 用户原话:「崩了能不能显示在悬浮球, 这样我就不会在崩溃的情况下, 还在游戏中」
            @JavascriptInterface
            fun notifyCrash(reason: String) {
                Log.e(TAG, "★ 策略引擎崩溃: " + reason)
                handler.post {
                    _isCrashed = true
                    _lastCrashReason = reason
                    isBlinkingError = false  // 让位给崩溃信号
                    // 1. 通知栏
                    try {
                        updateAdviceNotification("⚠️ 策略引擎崩溃", reason.take(60))
                    } catch (_: Exception) {}
                    // 2. 悬浮球立刻变「崩」——红+4Hz快闪
                    renderCrashBall()
                }
            }
            // V2.9.155: 清除崩溃态（用户重启WebView/重载HTML后调用）
            @JavascriptInterface
            fun clearCrash() {
                Log.d(TAG, "★ 清除崩溃状态")
                handler.post {
                    _isCrashed = false
                    _lastCrashReason = ""
                }
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
                Log.d(TAG, "showAdvice调用: advice=" + advice)
                handler.post {
                    // V2.9.155: 崩溃态最高优先级——崩溃时只认显式 RELOAD_AFTER_CRASH 才清, 否则忽略
                    if (_isCrashed && !advice.contains("CLEAR_CRASH")) {
                        Log.w(TAG, "★ 策略崩溃态生效, 忽略 advice=" + advice.take(50))
                        return@post
                    }
                    if (advice.contains("CLEAR_CRASH")) {
                        _isCrashed = false
                        _lastCrashReason = ""
                        Log.d(TAG, "★ 收到 CLEAR_CRASH, 崩溃态已清")
                    }
                    // V2.9.70: 收到正常建议→停止错误闪烁
                    isBlinkingError = false
                    // V2.9.113: 标记策略已回调
                    _lastStrategyAdvice = advice
                    _strategyReceived = true
                    // V2.9.125: 策略正常返回→取消超时定时器，防止正常建议被覆盖
                    _strategyTimeoutRunnable?.let { handler.removeCallbacks(it) }
                    _strategyTimeoutRunnable = null
                    if (advice.isNotEmpty()) {
                        tvRecResult?.text = advice  // V2.9.64: 只显示最新建议,不累积
                        tvRecResult?.visibility = View.VISIBLE
                        when {
                            advice.contains("COLOR:FOLD") -> tvRecResult?.setTextColor(0xFFFF5252.toInt())
                            advice.contains("COLOR:WEAK_CALL") -> tvRecResult?.setTextColor(0xFFFF8C00.toInt())
                            advice.contains("COLOR:CALL") -> tvRecResult?.setTextColor(0xFFFFAB40.toInt())
                            advice.contains("COLOR:RAISE_BIG") -> tvRecResult?.setTextColor(0xFF00E676.toInt())
                            advice.contains("COLOR:RAISE") -> tvRecResult?.setTextColor(0xFF69F0AE.toInt())
                            advice.contains("COLOR:ALL_IN") -> tvRecResult?.setTextColor(0xFFCE93D8.toInt())
                            advice.contains("COLOR:CHECK") -> tvRecResult?.setTextColor(0xFFBDBDBD.toInt())
                            // fallback: 旧5色兼容
                            advice.contains("弃牌") -> tvRecResult?.setTextColor(0xFFFF5252.toInt())
                            advice.contains("跟注") -> tvRecResult?.setTextColor(0xFFFFAB40.toInt())
                            advice.contains("全押") -> tvRecResult?.setTextColor(0xFFCE93D8.toInt())
                            advice.contains("加注") -> tvRecResult?.setTextColor(0xFF69F0AE.toInt())
                            advice.contains("让牌") || advice.contains("过牌") -> tvRecResult?.setTextColor(0xFFBDBDBD.toInt())
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
                    // V2.9.116: 所有模式都更新通知栏——让用户看到策略动作
                    updateAdviceNotification(title, detail)
                }
            }
            // V2.9.113: onVisionResult执行成功回调
            @JavascriptInterface
            fun confirmVisionReceived() { Log.d(TAG, "✅ onVisionResult已执行") }
            @JavascriptInterface fun autoCaptureVisionComplete(s:Boolean){handler.post{onAutoCaptureVisionDone(s)}}
            @JavascriptInterface fun triggerMultiFrame(){handler.post{triggerMultiFrameCapture()}}
            @JavascriptInterface fun setAutoSpeed(ms:Long){setAutoCaptureSpeed(ms)}
            @JavascriptInterface fun isAutoCaptureOn():Boolean=autoCaptureEnabled
            // V2.9.180: 全自动决策执行——JS计算置信度后回调
            @JavascriptInterface
            fun autoDecision(jsonData: String) {
                handler.post {
                    try {
                        val data = org.json.JSONObject(jsonData)
                        val action = data.optString("action", "fold")
                        val auto = data.optBoolean("auto", false)
                        val confidence = data.optString("confidence", "medium")
                        val reason = data.optString("reason", "")
                        val eq = data.optInt("eq", 0)
                        Log.d(TAG, "★ autoDecision: action=$action auto=$auto conf=$confidence reason=$reason eq=$eq")
                        
                        if (!auto) {
                            // 需要人工确认（如中置信+全押）
                            updateAdviceNotification("⚠️ 需确认: $action", "$reason (eq=$eq%)")
                            updateBallAdvice("COLOR:CHECK|SIGNAL:ALLIN_NEED|EQ:$eq|REASON:全押需确认")
                            return@post
                        }
                        
                        if (confidence == "low") {
                            // 低置信→强制弃牌
                            updateAdviceNotification("🛑 低置信→弃牌", "$reason (eq=$eq%)")
                            updateBallAdvice("COLOR:FOLD|SIGNAL:LOW_CONF|EQ:$eq|REASON:低置信弃牌")
                            // 执行弃牌tap
                            executeAutoTap("fold", data)
                        } else {
                            // 高/中置信自动执行
                            updateAdviceNotification("🤖 自动执行: $action", "$reason (eq=$eq%)")
                            executeAutoTap(action, data)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "autoDecision error", e)
                    }
                }
            }
            // V2.9.70: JS可获取Kotlin端错误日志，导出时一并带走
            @JavascriptInterface
            fun getErrorLogs(): String {
                return errorLogs.joinToString("\n")
            }
        }, "AndroidBridge")

        // V2.9.114: WebViewAssetLoader加载——Google官方推荐，零竞态风险
        // HttpServerService已在MainActivity中启动，不重复启动
        wv.loadUrl("https://appassets.androidplatform.net/assets/poker_helper.html")
        Log.d(TAG, "★ WebView通过AssetLoader加载poker_helper.html")

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
            // V2.9.132: 加粗+允许两行(EQ小字+动作大字), 字为王色为辅
            setTypeface(Typeface.DEFAULT_BOLD)
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setSingleLine(false)
            setLineSpacing(0f, 0.85f)
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
        var lastClickTime = 0L

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
                        val clickTime = System.currentTimeMillis()
                        if (clickTime - lastClickTime < 350) {
                            Log.d(TAG, "★ 悬浮球双击: 手动截屏"); triggerCapture(); lastClickTime = 0L
                        } else {
                            lastClickTime = clickTime
                            handler.postDelayed({
                                if (lastClickTime == clickTime) {
                                    Log.d(TAG, "★ 悬浮球单击: 切换全自动模式") // 缩放反馈
                        floatingBall?.let { b ->
                            b.animate().scaleX(0.85f).scaleY(0.85f).setDuration(80).withEndAction {
                                b.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                            }.start()
                        }
                        toggleAutoCapture()
                                }
                            }, 350)
                        }
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
     * V2.9.63: 7色信号系统 — 悬浮球颜色+闪烁信号
     * 🔴红=弃牌 🟠深橙=勉强跟 🟡黄=跟注 🟢绿=加注 💚青绿=重锤 🟣紫=全押 ⚪灰=过牌
     * 🔥慢闪=Tilt对手 ⚔️快闪=反剥削 ⚠️双闪=底池不确定
     *
     * V2.9.132: 7色保留+字为王 — 动作字18f加粗居中, EQ小字(0.5x)在上,
     *           颜色作为情绪背景, 文字作为决策识别(用户原话:「悬浮球里有字, 就什么都清楚」)
     */
    // V2.9.132: 动作字+EQ两行排版(EQ小字+动作大字), 集中管理
    private fun setBallText(ball: TextView, action: String, eqText: String) {
        if (eqText.isNotEmpty()) {
            val text = eqText + "\n" + action
            val sb = SpannableString(text)
            sb.setSpan(RelativeSizeSpan(0.5f), 0, eqText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ball.text = sb
        } else {
            ball.text = action
        }
        ball.textSize = 18f
    }
    // V2.9.155: 渲染崩溃悬浮球——「崩」字+红+4Hz快闪
    private fun renderCrashBall() {
        try {
            val ball = floatingBall ?: return
            val shape = ball.background as? GradientDrawable ?: return
            val density = resources.displayMetrics.density
            val stroke = (3 * density).toInt()
            shape.setColor(0xBBFF5252.toInt()); shape.setStroke(stroke, 0xFFFF5252.toInt())
            ball.text = "崩"
            ball.textSize = 22f  // 比正常18f略大(单字无EQ)
            ball.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            startBallSignal(4)  // 4Hz快闪(V2.9.131换桌闪烁档)
        } catch (e: Exception) {
            Log.e(TAG, "renderCrashBall失败: " + e.message)
        }
    }
        fun updateBallAdvice(advice: String) {
        Log.d(TAG, "updateBallAdvice: advice=$advice, ball=${if(floatingBall!=null)"存在" else "null"}")
        // V2.9.155: 崩溃态最高优先级——忽略任何普通 advice, 强制显示「崩」
        if (_isCrashed) {
            renderCrashBall()
            return
        }
        try {
            val ball = floatingBall ?: return
            val shape = ball.background as? GradientDrawable ?: return
            val density = resources.displayMetrics.density
            val stroke = (3 * density).toInt()
            // V2.9.107: 解析equity数值显示在悬浮球
            var eqText = ""
            val eqMatch = Regex("\\|EQ:(\\d+)").find(advice)
            if (eqMatch != null) {
                eqText = eqMatch.groupValues[1] + "%"
            }
            // V2.9.63: 7色+3信号(V2.9.132: 颜色保留, 文字加大加粗)
            when {
                advice.contains("COLOR:ALL_IN") -> {
                    shape.setColor(0xBBCE93D8.toInt()); shape.setStroke(stroke, 0xFFCE93D8.toInt())
                    setBallText(ball, "全押", eqText)
                    startBallSignal(0) // 无闪烁
                }
                advice.contains("COLOR:RAISE_BIG") -> {
                    shape.setColor(0xBB00E676.toInt()); shape.setStroke(stroke, 0xFF00E676.toInt())
                    setBallText(ball, "重锤", eqText)
                    startBallSignal(0)
                }
                advice.contains("COLOR:RAISE") -> {
                    shape.setColor(0xBB69F0AE.toInt()); shape.setStroke(stroke, 0xFF69F0AE.toInt())
                    setBallText(ball, "加", eqText)
                    startBallSignal(0)
                }
                advice.contains("COLOR:CALL") -> {
                    shape.setColor(0xBBFFAB40.toInt()); shape.setStroke(stroke, 0xFFFFAB40.toInt())
                    setBallText(ball, "跟", eqText)
                    startBallSignal(0)
                }
                advice.contains("COLOR:WEAK_CALL") -> {
                    shape.setColor(0xBBFF8C00.toInt()); shape.setStroke(stroke, 0xFFFF8C00.toInt())
                    setBallText(ball, "弱跟", eqText)
                    startBallSignal(0)
                }
                advice.contains("COLOR:FOLD") -> {
                    shape.setColor(0xBBFF5252.toInt()); shape.setStroke(stroke, 0xFFFF5252.toInt())
                    // V2.9.70: 悬浮球显示建议文字(V2.9.132: 加大加粗, NO_TABLE特殊)
                    if(advice.contains("NO_TABLE")){
                        ball.text = if(eqText.isNotEmpty()) eqText else "❓"
                        ball.textSize = 20f
                    } else {
                        setBallText(ball, "弃", eqText)
                    }
                    startBallSignal(0)
                }
                advice.contains("COLOR:CHECK") -> {
                    shape.setColor(0xBBBDBDBD.toInt()); shape.setStroke(stroke, 0xFFBDBDBD.toInt())
                    setBallText(ball, "过", eqText)
                    startBallSignal(0)
                }
                // fallback: 旧5色兼容(V2.9.132: 同样加大加粗)
                advice.contains("全押") -> {
                    shape.setColor(0xBBCE93D8.toInt()); shape.setStroke(stroke, 0xFFCE93D8.toInt())
                    setBallText(ball, "全押", eqText)
                    startBallSignal(0)
                }
                advice.contains("加注") -> {
                    shape.setColor(0xBB69F0AE.toInt()); shape.setStroke(stroke, 0xFF69F0AE.toInt())
                    setBallText(ball, "加", eqText)
                    startBallSignal(0)
                }
                advice.contains("跟注") -> {
                    shape.setColor(0xBBFFAB40.toInt()); shape.setStroke(stroke, 0xFFFFAB40.toInt())
                    setBallText(ball, "跟", eqText)
                    startBallSignal(0)
                }
                advice.contains("弃牌") -> {
                    shape.setColor(0xBBFF5252.toInt()); shape.setStroke(stroke, 0xFFFF5252.toInt())
                    setBallText(ball, "弃", eqText)
                    startBallSignal(0)
                }
                advice.contains("让牌") || advice.contains("过牌") -> {
                    shape.setColor(0xBBBDBDBD.toInt()); shape.setStroke(stroke, 0xFFBDBDBD.toInt())
                    setBallText(ball, "过", eqText)
                    startBallSignal(0)
                }
                else -> {
                    shape.setColor(0xBB4ade80.toInt()); shape.setStroke(stroke, 0xFF4ade80.toInt())
                    ball.text="🎯";ball.textSize=18f
                    startBallSignal(0)
                }
            }
            // V2.9.63: 信号闪烁
            when {
                advice.contains("SIGNAL:COUNTER") -> startBallSignal(3)   // 快闪: 反剥削
                advice.contains("SIGNAL:TILT") -> startBallSignal(1)     // 慢闪: Tilt对手
                advice.contains("SIGNAL:UNCERTAIN") -> startBallSignal(-1) // 双闪: 底池不确定
                // V2.9.131: 换桌建议信号
                advice.contains("SIGNAL:CHANGE_TABLE_L1") -> startBallSignal(2) // 2s慢闪: 建议换桌
                advice.contains("SIGNAL:CHANGE_TABLE_L2") -> startBallSignal(4) // 0.8s快闪: 强建议换桌
            }
        } catch (_: Exception) {}
    }

    // V2.9.131: 公开 setBlinkFreq 供 JS 端直接调用
    @JavascriptInterface
    fun setBlinkFreq(freq: Int) {
        handler.post {
            try { startBallSignal(freq) } catch (_: Exception) {}
        }
    }

    // V2.9.63: 悬浮球信号闪烁
    private var ballSignalRunnable: Runnable? = null
    private var ballSignalHandler: android.os.Handler? = null
    private var ballSignalCount = 0

    private fun startBallSignal(freqHz: Int) {
        // 停止之前的信号
        ballSignalRunnable?.let { ballSignalHandler?.removeCallbacks(it) }
        ballSignalRunnable = null
        if (freqHz == 0) {
            // 无信号,恢复正常透明度
            floatingBall?.alpha = 1.0f
            return
        }
        if (ballSignalHandler == null) ballSignalHandler = android.os.Handler(android.os.Looper.getMainLooper())
        ballSignalCount = 0
        val intervalMs = when {
            freqHz == -1 -> 150L  // 双闪
            freqHz == 1 -> 1000L  // 慢闪1Hz
            freqHz == 2 -> 2000L  // V2.9.131: 2s慢闪(L1建议换桌)
            freqHz == 3 -> 167L   // 快闪3Hz
            freqHz == 4 -> 800L   // V2.9.131: 0.8s快闪(L2强建议换桌)
            else -> return
        }
        val runnable = object : Runnable {
            override fun run() {
                val ball = floatingBall ?: return
                if (freqHz == -1) {
                    // 双闪: 闪2下停
                    ballSignalCount++
                    ball.alpha = if (ballSignalCount % 2 == 1) 0.2f else 1.0f
                    if (ballSignalCount >= 4) {
                        // 闪完2次,暂停1.2秒
                        ballSignalCount = 0
                        ballSignalHandler?.postDelayed(this, 1200)
                        return
                    }
                } else {
                    // 正常闪烁
                    ball.alpha = if (ball.alpha < 0.5f) 1.0f else 0.2f
                }
                ballSignalHandler?.postDelayed(this, intervalMs)
            }
        }
        ballSignalRunnable = runnable
        ballSignalHandler?.post(runnable)
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
    private fun processScreenshotAndAnalyze(isAutoCapture:Boolean=false,isMultiFrame1:Boolean=false,isMultiFrame2:Boolean=false) {
        _diagStartTime = System.currentTimeMillis()
        val screenshot = ScreenCaptureService.latestScreenshot
        val ssInfo = if (screenshot != null) "${screenshot.size/1024}KB" else "null"
        Log.d(TAG, "★ processScreenshotAndAnalyze: screenshot=$ssInfo, apiKey=${VisionApiClient.apiKey.takeLast(4)}, webViewReady=$webViewReady")
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
            updateAdviceNotification("❌ 2/4 截图为空", diag)
            // V2.9.70: 截图失败→悬浮球闪烁红 + 记录错误日志
            updateBallAdvice("COLOR:FOLD|SIGNAL:COUNTER")
            isBlinkingError = true
            floatingBall?.text="⚠️";floatingBall?.textSize=14f
            addErrorLog("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} 截屏失败: $diag")
            return
        }
        // V2.9.109: 诊断——截图成功，始终更新通知
        Log.d(TAG, "截图成功: ${screenshot.size / 1024}KB, apiKey=${if(VisionApiClient.apiKey.isNotEmpty()) "已配置" else "空"}")
        updateAdviceNotification("2/4 截图OK", "${screenshot.size / 1024}KB, API识别中...")

        if (VisionApiClient.apiKey.isEmpty()) {
            executeJs("if(typeof onActionCapture==='function'){onActionCapture()};document.body.classList.add('speed-mode');document.body.classList.remove('api-processing')")
            tvAction?.alpha = 1.0f
            tvStatus?.text = ScreenCaptureService.lastChipStatus.ifEmpty { "🎯 已更新(无API)" }
            updateAdviceNotification("已更新(无API)", ScreenCaptureService.lastChipStatus)
            return
        }

        // V2.9.165: 本地CV先行识别牌面——锁定手牌供VisionAPI使用
        if (localCVEnabled && cardRecognizer != null && VisionApiClient.apiKey.isNotEmpty()) {
            try {
                val tLocalStart = System.currentTimeMillis()
                val bmp = android.graphics.BitmapFactory.decodeByteArray(screenshot, 0, screenshot.size)
                if (bmp != null) {
                    val localResult = cardRecognizer!!.recognizeAll(bmp)
                    bmp.recycle()
                    val tLocalEnd = System.currentTimeMillis()
                    Log.d(TAG, "⏱ 本地CV: ${tLocalEnd - tLocalStart}ms, hand=${localResult.handCards.size}, board=${localResult.communityCards.size}")
                    
                    // V2.9.167: 保存本地CV诊断信息
                    _diagLocalCVTimeMs = tLocalEnd - tLocalStart
                    _diagLocalHandCards = localResult.handCards.map { VisionApiClient.CardInfo(it.rank, it.suit) }
                    _diagLocalCommunityCards = localResult.communityCards.map { VisionApiClient.CardInfo(it.rank, it.suit) }
                    _diagLocalStreet = when (localResult.communityCards.size) {
                        0 -> "preflop"; 3 -> "flop"; 4 -> "turn"; 5 -> "river"; else -> null
                    }

                    // V2.9.197: 混合方案 — 根据置信度决定锁定策略
                    val minConf = localResult.minConfidence
                    val handRanks = localResult.handCards.map { it.rank }
                    val streetFromLocal = localResult.inferStreet()
                    
                    if (localResult.handCards.size == 2 && minConf >= 0.85f) {
                        // 高置信度: 锁定rank（rank-only），suit由API补充
                        VisionApiClient.holeCardsRankLocked = handRanks
                        VisionApiClient.holeCardsLocked = null  // 不锁定完整牌，让API提供suit
                        VisionApiClient.streetLocked = streetFromLocal
                        VisionApiClient.lockReason = "混合锁定 rank=${handRanks} conf=${"%.2f".format(minConf)} (${tLocalEnd - tLocalStart}ms)"
                        Log.i(TAG, "★ 混合锁定(高置信度${"%.2f".format(minConf)}): ranks=$handRanks | street=$streetFromLocal")
                        tvStatus?.text = "🎯 混合:${handRanks.joinToString(" ")}(conf=${"%.0f".format(minConf*100)}%) | API补suit..."
                        updateAdviceNotification("本地CV高置信", "rank已锁定,API补suit...")
                    } else if (localResult.handCards.size == 2) {
                        // 中等置信度: 不锁定rank，让API完整识别，但仍锁定street
                        VisionApiClient.holeCardsRankLocked = null
                        VisionApiClient.holeCardsLocked = null
                        VisionApiClient.streetLocked = streetFromLocal
                        VisionApiClient.lockReason = "CV低置信(${"%.2f".format(minConf)}), API全识别"
                        Log.i(TAG, "混合模式(中置信度${"%.2f".format(minConf)}): ranks=$handRanks 不锁定, API完整识别 | street=$streetFromLocal")
                        tvStatus?.text = "🎯 CV:${handRanks.joinToString(" ")}(低conf) | API全识别..."
                        updateAdviceNotification("本地CV中置信", "API完整识别中...")
                    } else {
                        // 手牌识别不完整
                        Log.w(TAG, "本地CV手牌不完整(${localResult.handCards.size}/2), 纯API模式")
                        VisionApiClient.holeCardsRankLocked = null
                        VisionApiClient.holeCardsLocked = null; VisionApiClient.streetLocked = null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "本地CV异常, 回退VisionAPI", e)
                VisionApiClient.holeCardsRankLocked = null; VisionApiClient.holeCardsLocked = null; VisionApiClient.streetLocked = null
            }
        }

        // V2.9.208: 本地CV快速通道——完全脱离VLM，花色+底池OCR+按钮推断
        if (autoCaptureEnabled && localCVEnabled && cardRecognizer != null) {
            var fastBmp: android.graphics.Bitmap? = null
            val fastLocalResult = try {
                fastBmp = android.graphics.BitmapFactory.decodeByteArray(screenshot, 0, screenshot.size)
                if (fastBmp != null) cardRecognizer!!.recognizeAll(fastBmp) else null
            } catch (e: Exception) { null }

            if (fastLocalResult != null && fastLocalResult.handCards.size == 2
                && fastLocalResult.minConfidence >= FAST_PATH_MIN_CONFIDENCE
            ) {
                // V2.9.208 Phase 2: 本地OCR读取底池（优先），失败用缓存
                val localPot = if (fastBmp != null) {
                    try {
                        val potRegion = GameModeConfig.getPotRegionPixels(screenWidth, screenHeight)
                        val px1 = potRegion.first.first
                        val py1 = potRegion.first.second
                        val px2 = potRegion.second.first
                        val py2 = potRegion.second.second
                        cardRecognizer!!.readPotSize(fastBmp, px1, py1, px2, py2)
                    } catch (e: Exception) { -1 }
                } else -1
                if (localPot > 0) cachedPotSize = localPot  // OCR成功→更新缓存

                // V2.9.210: ChipTracker 定点OCR —— 筹码/D按钮/行动者/SB/BB
                if (fastBmp != null) {
                    try {
                        val chipFrame = ChipTracker.analyzeWithFixedCoords(fastBmp)
                        if (chipFrame != null) {
                            // 用定点OCR底池覆盖（更准确）
                            if (chipFrame.potAmount > 0) cachedPotSize = chipFrame.potAmount.toInt()
                            cachedTotalPlayers = chipFrame.tablePlayerCount
                            cachedActivePlayers = chipFrame.activePlayerCount
                            // D按钮→推算SB/BB→更新myPosition
                            if (chipFrame.dealerSeatIndex > 0) {
                                cachedMyPosition = seatIndexToPosition(chipFrame.dealerSeatIndex, chipFrame.tablePlayerCount)
                                cachedBlindSB = chipFrame.sbSeatIndex
                                cachedBlindBB = chipFrame.bbSeatIndex
                            }
                            // 更新玩家筹码缓存（Hero=座位6）
                            val heroSeat = chipFrame.players.find { it.id == 6 }
                            if (heroSeat != null && heroSeat.currentChips > 0) {
                                cachedPlayerChips = heroSeat.currentChips.toInt()
                            }
                            Log.i(TAG, "★ ChipTracker: pot=${chipFrame.potAmount} players=${chipFrame.tablePlayerCount} active=${chipFrame.activePlayerCount} dealer=${chipFrame.dealerSeatIndex} active_seat=${chipFrame.activeSeatIndex} sb=${chipFrame.sbSeatIndex} bb=${chipFrame.bbSeatIndex} hero_pos=$cachedMyPosition")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "ChipTracker定点分析异常: ${e.message}")
                    }
                }
                fastBmp?.recycle(); fastBmp = null

                if (cachedPotSize <= 0) {
                    // 既没有OCR结果也没有缓存，无法快速通道
                } else {
                    // V2.9.208 Phase 1: 使用真实花色（不再传"?"）
                    val handCards = fastLocalResult.handCards.map {
                        VisionApiClient.CardInfo(it.rank, if (it.suit != "?") it.suit else "x")
                    }
                    val communityCards = fastLocalResult.communityCards.map {
                        VisionApiClient.CardInfo(it.rank, if (it.suit != "?") it.suit else "x")
                    }
                    val anySuitUnknown = handCards.any { it.suit == "x" } || communityCards.any { it.suit == "x" }
                    val street = when (communityCards.size) {
                        0 -> "preflop"; 3 -> "flop"; 4 -> "turn"; 5 -> "river"; else -> "preflop"
                    }

                    // V2.9.208 Phase 3: 按钮状态推断 + 缓存按钮坐标
                    val fastButtons = if (latestButtonPositions.isNotEmpty()) {
                        latestButtonPositions.map { it.text }
                    } else {
                        cardRecognizer!!.inferButtons(cachedToCall, GameModeConfig.currentPlatform == GamePlatform.GGPOKER)
                    }
                    val fastButtonPositions = if (latestButtonPositions.isNotEmpty()) {
                        latestButtonPositions
                    } else {
                        // 使用GG固定坐标构建按钮位置
                        inferButtonPositions(cachedToCall)
                    }

                    val fastResult = VisionApiClient.VisionResult(
                        isPokerTable = true,
                        holeCards = handCards,
                        communityCards = communityCards,
                        potSize = cachedPotSize,
                        playerChips = cachedPlayerChips,
                        totalPlayers = cachedTotalPlayers,
                        activePlayers = cachedActivePlayers,
                        myPosition = cachedMyPosition,
                        street = street,
                        toCall = cachedToCall,
                        minRaise = cachedMinRaise,
                        buttons = fastButtons,
                        blindSB = cachedBlindSB,
                        blindBB = cachedBlindBB,
                        ante = 0,
                        players = emptyList(),
                        dButtonPosition = cachedMyPosition,
                        rawResponse = "fast_path_local_cv_v2",
                        showdownCards = emptyList(),
                        oppHud = emptyList(),
                        buttonPositions = fastButtonPositions,
                        suitUncertain = anySuitUnknown,
                        isInsurance = false
                    )
                    Log.i(TAG, "★ 本地CV快速通道v2: hand=${handCards.map{"${it.rank}${it.suit}"}} board=${communityCards.map{"${it.rank}${it.suit}"}} street=$street pot=$cachedPotSize toCall=$cachedToCall suitOK=${!anySuitUnknown} (跳过VLM)")
                    tvStatus?.text = "⚡ 纯本地CV (${fastLocalResult.elapsedMs}ms)"
                    updateAdviceNotification("⚡ 纯本地模式", "零API延迟")
                    val resultJson = VisionApiClient.toJson(fastResult)
                    val taggedJson = resultJson.dropLast(1) + ",\"_frameTag\":\"auto\"}"
                    handler.post {
                        if (webViewReady) {
                            executeJs("if(typeof onVisionResult==='function'){onVisionResult($taggedJson)}")
                        }
                        handStartTime = 0; _shotClockRunnable?.let { handler.removeCallbacks(it) }
                        onAutoCaptureVisionDone(true)
                    }
                    return  // 跳过VLM
                }
            }
        }

        // 有API Key → 调用视觉模型识别牌面（本地CV已锁牌时只补充场景信息）
        tvStatus?.text = "🎯 API识别中..."
        tvAction?.alpha = 0.5f
        updateAdviceNotification("识别中...", "正在分析牌面")
        val tAnalyzeStart = System.currentTimeMillis()
        // V2.9.207: 记录当前手牌分析开始时间（Shot Clock基准）
        if (autoCaptureEnabled && handStartTime == 0L) {
            handStartTime = tAnalyzeStart
            Log.d(TAG, "★ handStartTime set: $handStartTime")
            // V2.9.207: 调度Shot Clock硬超时——16秒后强制弃牌（不等VLM返回）
            _shotClockRunnable?.let { handler.removeCallbacks(it) }
            _shotClockRunnable = Runnable {
                if (handStartTime > 0 && autoCaptureEnabled) {
                    Log.w(TAG, "★ Shot Clock HARD TIMEOUT! ${(System.currentTimeMillis() - handStartTime)}ms, forcing fold")
                    handStartTime = 0
                    lastDecisionTime = System.currentTimeMillis()
                    isVisionInProgress = false
                    executeAutoTapFallback("fold")
                    updateAdviceNotification("⏰ Shot Clock", "超时强制弃牌(硬超时)")
                    updateBallAdvice("COLOR:FOLD|SIGNAL:TIMEOUT|REASON:Shot Clock超时")
                    scheduleNextAutoCapture()
                }
            }
            handler.postDelayed(_shotClockRunnable!!, 16000)
        }
        // V2.9.207: 预检——如果handStartTime已超18秒，跳过VLM直接弃牌
        if (autoCaptureEnabled && handStartTime > 0 && (tAnalyzeStart - handStartTime) > SHOT_CLOCK_TIMEOUT) {
            Log.w(TAG, "★ Shot Clock pre-check: ${(tAnalyzeStart - handStartTime)}ms, skip VLM and force fold")
            handStartTime = 0
            lastDecisionTime = tAnalyzeStart
            executeAutoTapFallback("fold")
            updateAdviceNotification("⏰ Shot Clock", "超时强制弃牌(预检)")
            updateBallAdvice("COLOR:FOLD|SIGNAL:TIMEOUT|REASON:Shot Clock超时")
            isVisionInProgress = false
            scheduleNextAutoCapture()
            return
        }
        Thread {
            try {
                val result = VisionApiClient.analyzeScreenshot(screenshot)
                val tAnalyzeEnd = System.currentTimeMillis()
                Log.d(TAG, "⏱ analyzeSnapshot: ${tAnalyzeEnd-tAnalyzeStart}ms")
                Log.d(TAG, "★ VisionAPI result=${if(result!=null)"成功" else "null"}, lastError=${VisionApiClient.lastError}")
                
                // V2.9.167: 记录诊断日志
                DiagnosticLogger.logRecognition(
                    localCVEnabled = localCVEnabled,
                    localCVTimeMs = _diagLocalCVTimeMs,
                    localHandCards = _diagLocalHandCards,
                    localCommunityCards = _diagLocalCommunityCards,
                    localStreet = _diagLocalStreet,
                    streetLocked = VisionApiClient.streetLocked,
                    holeCardsLocked = VisionApiClient.holeCardsLocked != null,
                    vlmTimeMs = tAnalyzeEnd - tAnalyzeStart,
                    vlmResult = result,
                    totalTimeMs = System.currentTimeMillis() - _diagStartTime,
                    hasError = result == null,
                    errorMessage = if (result == null) VisionApiClient.lastError else null,
                    strategySent = result != null && result.isPokerTable,
                    rawResponse = if (result == null) VisionApiClient.lastRawResponse else null  // V2.9.193
                )
                // 重置诊断变量
                _diagLocalCVTimeMs = 0L
                _diagLocalHandCards = emptyList()
                _diagLocalCommunityCards = emptyList()
                _diagLocalStreet = null
                
                if (result != null) {
                    // V2.9.111: NO_TABLE检测——优先看isPokerTable，其次3信号联合判断
                    val modelSaysNoTable = !result.isPokerTable
                    val noHoleCards = result.holeCards.size < 2
                    val noDButton = result.dButtonPosition.isEmpty() || result.dButtonPosition == "not_found"
                    val noPokerButtons = result.buttons.isEmpty() || result.buttons.none { b ->
                        b.contains("弃牌") || b.contains("跟注") || b.contains("加注") || b.contains("过牌") || b.contains("让牌") || b.contains("下注") || b.contains("全下") || b.contains("全押")
                    }
                    val noTableSignals = (if(noHoleCards) 1 else 0) + (if(noDButton) 1 else 0) + (if(noPokerButtons) 1 else 0)
                    val isNoTable = modelSaysNoTable || noTableSignals >= 2
                    Log.d(TAG, "★ NO_TABLE检测: isPokerTable=${result.isPokerTable} noHole=$noHoleCards noD=$noDButton noBtn=$noPokerButtons signals=$noTableSignals result=$isNoTable")
                    if (isNoTable) {
                        Log.w(TAG, "★ NO_TABLE判定: 不在牌桌(signals=$noTableSignals), dButton=${result.dButtonPosition}, buttons=${result.buttons}")
                        // V2.9.207: NO_TABLE时重置handStartTime和场景缓存，避免累积超时和使用过期数据
                        handStartTime = 0
                        _shotClockRunnable?.let { handler.removeCallbacks(it) }
                        latestButtonPositions = emptyList()
                        cachedPotSize = 0; cachedToCall = 0; cachedMinRaise = 0
                        handler.post {
                            updateBallAdvice("COLOR:FOLD|SIGNAL:NO_TABLE|EQ:0|REASON:未检测到牌桌")
                            tvStatus?.text = "❓ 未检测到牌桌"
                            tvRecResult?.text = "🔍 不在游戏桌面"
                            tvRecResult?.visibility = View.VISIBLE
                            tvRecResult?.setBackgroundColor(0xFF8B0000.toInt())
                            tvRecDetail?.text = "D按钮=${result.dButtonPosition} 按钮=${result.buttons}"
                            tvRecDetail?.visibility = View.VISIBLE
                            updateAdviceNotification("❓ 不在牌桌", "D=${result.dButtonPosition} 按钮=${result.buttons.joinToString(",")} WV:$webViewReady")
                        }
                    } else {
                    val resultJson = VisionApiClient.toJson(result)
                    Log.d(TAG, "★ resultJson长度=${resultJson.length}, webViewReady=$webViewReady")
                    // V2.9.180: 存储按钮坐标供全自动执行使用
                    if (result.buttonPositions.isNotEmpty()) {
                        latestButtonPositions = result.buttonPositions
                        Log.d(TAG, "★ 按钮坐标已存储: ${result.buttonPositions.map { "${it.text}(${it.xPct},${it.yPct})" }}")
                    }
                    // V2.9.207: 缓存场景数据——供后续本地CV快速通道使用
                    cachedPotSize = result.potSize
                    cachedToCall = result.toCall
                    cachedMinRaise = result.minRaise.toInt()
                    cachedBlindSB = result.blindSB
                    cachedBlindBB = result.blindBB
                    cachedTotalPlayers = result.totalPlayers
                    cachedActivePlayers = result.activePlayers
                    cachedMyPosition = result.myPosition
                    cachedPlayerChips = result.playerChips
                    Log.d(TAG, "★ 场景数据已缓存: pot=$cachedPotSize toCall=$cachedToCall")
                    // V2.9.207: 移除旧的手牌重置逻辑——handStartTime在executeAutoTap决策后重置，不再这里重置
                    // V2.9.206: 特殊状态处理——Insurance自动拒绝 / 搓牌等待
                    val skipStrategyCalc: Boolean = when {
                        result.isInsurance && autoCaptureEnabled -> {
                            Log.d(TAG, "★ Insurance detected, auto-declining")
                            handler.post {
                                try {
                                    val (ix, iy) = GameModeConfig.getInsuranceDeclinePosition(screenWidth, screenHeight)
                                    bleManager?.sendTap(ix, iy, 50)
                                    updateAdviceNotification("Insurance", "已自动拒绝")
                                    updateBallAdvice("COLOR:CHECK|SIGNAL:INSURANCE|REASON:自动拒绝")
                                    Log.d(TAG, "★ Insurance declined at ($ix, $iy)")
                                } catch (e: Exception) {
                                    Log.e(TAG, "Insurance decline error", e)
                                }
                            }
                            true
                        }
                        result.suitUncertain && autoCaptureEnabled -> {
                            Log.d(TAG, "★ Squeeze detected (suit_uncertain), waiting 3s")
                            updateAdviceNotification("搓牌中", "等待牌面完全揭示")
                            updateBallAdvice("COLOR:CHECK|SIGNAL:SQUEEZE|REASON:搓牌动画中")
                            handler.postDelayed({
                                if (autoCaptureEnabled && ScreenOptService.isServiceRunning()) {
                                    Log.d(TAG, "★ Squeeze wait done, re-capturing")
                                    processScreenshotAndAnalyze(isAutoCapture=true)
                                }
                            }, 3000)
                            true
                        }
                        else -> false
                    }
                        if (!skipStrategyCalc) {
                    val frameTag=when{isMultiFrame2->",_frameTag:'verify'";isMultiFrame1->",_frameTag:'primary'";isAutoCapture->",_frameTag:'auto'";else->""}
                    // V2.9.125: 策略超时保险——8秒超时+灰色等待（非红色FOLD）
                    // 7000+行JS首次加载+MC模拟2-3秒，5秒根本不够
                    _strategyReceived = false
                    // 先取消之前的超时定时器（防止重复截图时叠加）
                    _strategyTimeoutRunnable?.let { handler.removeCallbacks(it) }
                    val timeoutRunnable = Runnable {
                        if (!_strategyReceived) {
                            Log.w(TAG, "★ 策略引擎超时8s，标记等待状态（非FOLD）")
                            updateBallAdvice("COLOR:CHECK|SIGNAL:TIMEOUT|EQ:0|REASON:策略计算中")
                            updateAdviceNotification("⏳ 策略计算中", "8s未回调→等待而非FOLD")
                        }
                    }
                    _strategyTimeoutRunnable = timeoutRunnable
                    handler.postDelayed(timeoutRunnable, 8000)
                    handler.post {
                        val taggedJson=if(frameTag.isNotEmpty()) resultJson.dropLast(1)+frameTag+"}" else resultJson
                        // V2.9.113: 先检测WebView是否就绪，再调onVisionResult
                        executeJs("(function(){try{if(typeof onVisionResult==='function'){onVisionResult($taggedJson);if(typeof AndroidBridge!=='undefined'&&AndroidBridge.confirmVisionReceived){AndroidBridge.confirmVisionReceived()}}else{console.log('[V2.9.125] onVisionResult不存在,尝试重载HTML');if(typeof AndroidBridge!=='undefined'&&AndroidBridge.showAdvice){AndroidBridge.showAdvice('COLOR:FOLD|SIGNAL:ERROR|REASON:策略引擎未加载');}setTimeout(function(){location.reload();},1000);}}catch(e){console.log('[V2.9.125] onVisionResult异常:'+e.message);if(typeof AndroidBridge!=='undefined'&&AndroidBridge.showAdvice){AndroidBridge.showAdvice('COLOR:FOLD|SIGNAL:ERROR|REASON:JS异常:'+e.message.substring(0,30));}}})()")
                        tvAction?.alpha = 1.0f
                        Log.d(TAG, "★ onVisionResult已调用")
                        // V2.9.70: 正常识别→停止闪烁
                        isBlinkingError = false
                        updateAutoCaptureInterval(result.street)  // V2.9.183: 自适应截屏间隔
                        updateAdviceNotification("3/4 API识别OK", "策略计算中... WV:$webViewReady")
                        val hole = result.holeCards.map { (if(it.rank=="T") "10" else it.rank) + it.suit }.joinToString(" ")
                        tvStatus?.text = "✅ $hole | ${result.street} | ${result.totalPlayers}人"
                        val suitSym = mapOf("s" to "♠", "h" to "♥", "d" to "♦", "c" to "♣")
                        val streetCN = mapOf("preflop" to "翻前", "flop" to "翻牌", "turn" to "转牌", "river" to "河牌")
                        val holeStr = result.holeCards.map { "${if(it.rank=="T") "10" else it.rank}${suitSym[it.suit] ?: it.suit}" }.joinToString(" ")
                        val commStr = result.communityCards.map { "${if(it.rank=="T") "10" else it.rank}${suitSym[it.suit] ?: it.suit}" }.joinToString(" ")
                        val streetStr = streetCN[result.street] ?: result.street
                        var recText = "🔍 $holeStr | $streetStr | ${result.totalPlayers}人桌"
                        if (result.ante > 0) recText += " | Ante:${result.ante}"
                        var detailText = "BB=${result.blindBB}"
                        if (result.blindSB > 0) detailText += " SB=${result.blindSB}"
                        if (result.potSize > 0) detailText += " | 底池${result.potSize}"
                        if (result.toCall > 0) detailText += " | 跟注${result.toCall}"
                        val apiError = VisionApiClient.lastError
                        if (apiError.isNotEmpty()) {
                            recText += " ⚠️$apiError"
                            tvRecResult?.setBackgroundColor(0xFF8B0000.toInt())
                        } else {
                            tvRecResult?.setBackgroundColor(0xFF1A237E.toInt())
                        }
                        tvRecResult?.text = recText
                        tvRecResult?.visibility = View.VISIBLE
                        tvRecDetail?.text = detailText
                        tvRecDetail?.visibility = View.VISIBLE
                        // V2.9.114: 通知栏增加诊断信息——webViewReady+手牌数+策略回调状态
                        val notifyDetail = buildString {
                            append("手牌:$holeStr")
                            if (commStr.isNotEmpty()) append(" 公牌:$commStr")
                            append(" | WV:$webViewReady")
                            append(" | 牌数:${result.holeCards.size}")
                            append(" | isPoker:${result.isPokerTable}")
                        }
                        updateAdviceNotification("✅ $holeStr $streetStr ${result.totalPlayers}人", notifyDetail)
                    }
                        } // end of skipStrategyCalc check
                    } // V2.9.111: end of NO_TABLE else (正常牌桌才执行策略)
                } else {
                    handler.post {
                        tvAction?.alpha = 1.0f
                        tvStatus?.text = "❌ API: ${VisionApiClient.lastError.take(30)}"
                        executeJs("document.body.classList.remove('api-processing')")
                        updateBallAdvice("COLOR:FOLD|SIGNAL:COUNTER")
                        isBlinkingError = true
                        floatingBall?.text="⚠️";floatingBall?.textSize=14f
                        // V2.9.193: 错误日志包含API原始响应前200字符——直接定位根因
                        val rawResp = VisionApiClient.lastRawResponse.take(200)
                        addErrorLog("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} API失败: ${VisionApiClient.lastError.take(100)} | raw: $rawResp")
                        Log.e(TAG, "★ API失败, error=${VisionApiClient.lastError}, raw=${VisionApiClient.lastRawResponse.take(300)}")
                        updateAdviceNotification("❌ 3/4 API失败", VisionApiClient.lastError.take(40))
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    tvAction?.alpha = 1.0f
                    tvStatus?.text = "❌ API错误"
                    executeJs("document.body.classList.remove('api-processing')")
                    updateBallAdvice("COLOR:FOLD|SIGNAL:COUNTER")
                    isBlinkingError = true
                    floatingBall?.text="⚠️";floatingBall?.textSize=14f
                    addErrorLog("${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} API异常: ${e.message?.take(100) ?: "未知"}")
                    updateAdviceNotification("API错误", e.message?.take(50) ?: "")
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

            val exportIntent = Intent(ACTION_EXPORT)
            exportIntent.setPackage(packageName)
            val exportPending = PendingIntent.getBroadcast(this, 4, exportIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_menu_save, "导出", exportPending)

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

    // V2.9.113: 纯Kotlin端导出日志，不依赖WebView
    // V2.9.167: 增强版诊断日志导出
    private fun exportLogFromNotification() {
        try {
            // 使用 DiagnosticLogger 导出完整的诊断日志
            val logData = DiagnosticLogger.exportAsJson()
            val downloadDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            val fileName = "poker_log_${java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US).format(java.util.Date())}.json"
            val exportFile = File(downloadDir, fileName)
            exportFile.writeText(logData, Charsets.UTF_8)

            // 复制到剪贴板
            try {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("poker_log", logData))
            } catch (_: Exception) {}

            // 弹出分享
            val fileUri = androidx.core.content.FileProvider.getUriForFile(
                this@FloatingService,
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
            updateAdviceNotification("✅ 日志已导出", "保存到Download/${fileName}")
        } catch (e: Exception) {
            updateAdviceNotification("❌ 导出失败", e.message?.take(30) ?: "")
        }
    }

    /**
     * V2.9.38: 更新通知栏显示建议内容
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

            val exportIntent = Intent(ACTION_EXPORT)
            exportIntent.setPackage(packageName)
            val exportPending = PendingIntent.getBroadcast(this, 4, exportIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(android.R.drawable.ic_menu_save, "导出", exportPending)

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