package com.pokerhelper.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.Switch
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val TAG = "PokerMainActivity"
    private val OVERLAY_REQUEST_CODE = 1001
    private val PREFS_NAME = "poker_api_prefs"
    private val KEY_PROVIDER = "api_provider"
    private val KEY_APIKEY = "api_key"

    private lateinit var tvStatus: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvApiStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnStart: Button
    private lateinit var btnHelper: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnSaveApi: Button
    private lateinit var switchStealth: Switch
    private lateinit var spinnerProvider: Spinner
    private lateinit var etApiKey: EditText
    private var isRunning = false
    private var prefs: SharedPreferences? = null
    private var floatingPrefs: SharedPreferences? = null

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "语音识别需要麦克风权限", Toast.LENGTH_SHORT).show()
        }
    }

    // V2.9.39: 通知权限请求（Android 13+必须，否则通知不显示）
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "需要通知权限才能显示截屏按钮", Toast.LENGTH_LONG).show()
        }
    }

    // V2.9.171: BLE蓝牙权限请求（Android 12+必须，否则无法扫描/连接BLE设备）
    private val blePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (!allGranted) {
            val denied = results.filter { !it.value }.keys.joinToString(", ")
            Log.w(TAG, "BLE permissions denied: $denied")
            Toast.makeText(this, "蓝牙权限未授予，无法连接ESP32", Toast.LENGTH_LONG).show()
        } else {
            Log.i(TAG, "BLE permissions granted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            // V2.9.39: Android 13+必须请求通知权限，否则通知不显示
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            // V2.9.171: Android 12+必须请求BLE权限，否则无法扫描/连接蓝牙设备
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val blePermissions = mutableListOf<String>()
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    blePermissions.add(Manifest.permission.BLUETOOTH_SCAN)
                }
                if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    blePermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
                }
                if (blePermissions.isNotEmpty()) {
                    blePermissionLauncher.launch(blePermissions.toTypedArray())
                }
            }

            prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            floatingPrefs = getSharedPreferences("poker_floating_prefs", MODE_PRIVATE)
            tvStatus = findViewById(R.id.tvStatus)
            tvHint = findViewById(R.id.tvHint)
            tvApiStatus = findViewById(R.id.tvApiStatus)
            tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
            btnStart = findViewById(R.id.btnStart)
            btnHelper = findViewById(R.id.btnHelper)
            btnAccessibility = findViewById(R.id.btnAccessibility)
            btnSaveApi = findViewById(R.id.btnSaveApi)
            switchStealth = findViewById(R.id.switchStealth)
            spinnerProvider = findViewById(R.id.spinnerProvider)
            etApiKey = findViewById(R.id.etApiKey)

            isRunning = FloatingService.isRunning

            // V2.9.38: 隐身模式开关
            switchStealth.isChecked = floatingPrefs?.getBoolean("stealth_mode", false) ?: false
            switchStealth.setOnCheckedChangeListener { _, isChecked ->
                floatingPrefs?.edit()?.putBoolean("stealth_mode", isChecked)?.apply()
                if (isChecked) {
                    Toast.makeText(this, "🥷 隐身模式：无悬浮窗，用通知栏看建议", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "正常模式：显示悬浮窗", Toast.LENGTH_SHORT).show()
                }
                // 如果正在运行，需要重启服务
                if (isRunning) {
                    stopServices()
                    handler.postDelayed({ startDirectly() }, 500)
                }
            }

            updateUI()

            val providers = arrayOf("openai", "dashscope", "deepseek", "siliconflow")
            val providerNames = arrayOf("OpenAI (GPT-4o-mini)", "通义千问VL", "DeepSeek", "硅基流动(Qwen3-VL)")
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providerNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerProvider.adapter = adapter

            val savedProvider = prefs?.getString(KEY_PROVIDER, "dashscope") ?: "dashscope"
            val savedKey = prefs?.getString(KEY_APIKEY, "sk-392a0016c35c4e788670d8b05bd741a6") ?: "sk-392a0016c35c4e788670d8b05bd741a6"
            val providerIndex = providers.indexOf(savedProvider).coerceAtLeast(0)
            spinnerProvider.setSelection(providerIndex)
            if (savedKey.isNotEmpty()) {
                etApiKey.setText(savedKey)
                VisionApiClient.updateConfig(savedProvider, savedKey)
                updateApiStatus()
            }

            spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {}
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            btnSaveApi.setOnClickListener {
                val provider = providers[spinnerProvider.selectedItemPosition]
                val key = etApiKey.text.toString().trim()
                if (key.isEmpty()) {
                    Toast.makeText(this, "请输入API Key", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                VisionApiClient.updateConfig(provider, key)
                prefs?.edit()?.putString(KEY_PROVIDER, provider)?.putString(KEY_APIKEY, key)?.apply()
                updateApiStatus()
                Toast.makeText(this, "✅ API配置已保存: ${VisionApiClient.modelName}", Toast.LENGTH_SHORT).show()
            }

            btnStart.setOnClickListener {
                try {
                    if (isRunning) stopServices() else startDirectly()
                } catch (e: Exception) {
                    Log.e(TAG, "Btn click error", e)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }

            btnHelper.setOnClickListener {
                try {
                    tryLaunchFloatingHelper()
                } catch (e: Exception) {
                    Log.e(TAG, "Helper error", e)
                    Toast.makeText(this, "启动悬浮窗失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            btnAccessibility.setOnClickListener {
                try {
                    openAccessibilitySettings()
                } catch (e: Exception) {
                    Log.e(TAG, "Accessibility settings error", e)
                    Toast.makeText(this, "打开设置失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onCreate error", e)
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun updateApiStatus() {
        if (VisionApiClient.apiKey.isNotEmpty()) {
            tvApiStatus.text = "✅ ${VisionApiClient.modelName}"
            tvApiStatus.setTextColor(getColor(android.R.color.holo_green_dark))
        } else {
            tvApiStatus.text = "❌ 未配置"
            tvApiStatus.setTextColor(getColor(android.R.color.holo_red_dark))
        }
    }

    private fun tryLaunchFloatingHelper() {
        if (!isRunning) {
            Toast.makeText(this, "请先启动截屏", Toast.LENGTH_SHORT).show()
            return
        }
        // V2.9.38: 隐身模式不需要悬浮窗权限
        val stealthMode = floatingPrefs?.getBoolean("stealth_mode", false) ?: false
        if (!stealthMode) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "需要授权「显示在其他应用上层」", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, OVERLAY_REQUEST_CODE)
                return
            }
        }
        launchFloatingHelper()
    }

    private fun launchFloatingHelper() {
        try {
            val intent = Intent(this, FloatingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            val stealthMode = floatingPrefs?.getBoolean("stealth_mode", false) ?: false
            val modeText = if (stealthMode) "🥷 隐身模式！" else "📱 青云已启动！"
            Toast.makeText(this, modeText, Toast.LENGTH_LONG).show()

            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Launch floating error", e)
            Toast.makeText(this, "悬浮窗启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
                launchFloatingHelper()
            } else {
                Toast.makeText(this, "未获得悬浮窗权限，无法使用", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startDirectly() {
        try {
            // V2.9.184: 无障碍未开启不再完全阻塞——允许启动HTTP服务和WebView，截屏时再提示
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "⚠️ 无障碍未开启，截屏功能不可用（已启动HTTP/策略引擎）", Toast.LENGTH_LONG).show()
            }

            val httpIntent = Intent(this, HttpServerService::class.java).apply { action = "START" }
            startForegroundService(httpIntent)

            isRunning = true
            updateUI()
            Toast.makeText(this, "📱 青云启动中...", Toast.LENGTH_SHORT).show()

            tryLaunchFloatingHelper()
        } catch (e: Exception) {
            Log.e(TAG, "Start error", e)
            Toast.makeText(this, "启动失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopServices() {
        try {
            startService(Intent(this, HttpServerService::class.java).apply { action = "STOP" })
            startService(Intent(this, FloatingService::class.java).apply { action = "STOP" })
            isRunning = false
            updateUI()
        } catch (e: Exception) {
            Log.e(TAG, "Stop error", e)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (ScreenOptService.isServiceRunning()) return true

        try {
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains("$packageName/.ScreenOptService") ||
                   enabledServices.contains("$packageName/com.pokerhelper.app.ScreenOptService") ||
                   enabledServices.contains("com.pokerhelper.app/com.pokerhelper.app.ScreenOptService") ||
                   enabledServices.contains("win.opt.view/com.pokerhelper.app.ScreenOptService")
        } catch (e: Exception) {
            return false
        }
    }

    private fun openAccessibilitySettings() {
        Toast.makeText(this, "找到「屏幕显示优化助手」→ 开启", Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun updateUI() {
        try {
            val accessibilityEnabled = isAccessibilityServiceEnabled()
            if (accessibilityEnabled) {
                tvAccessibilityStatus.text = "✅ 无障碍服务已开启（截图不触发黑屏）"
                tvAccessibilityStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                btnAccessibility.text = "♿ 无障碍服务已开启"
                btnAccessibility.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
            } else {
                tvAccessibilityStatus.text = "⚠️ 无障碍服务未开启（点下方开启，截图不黑屏）"
                tvAccessibilityStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
                btnAccessibility.text = "♿ 开启无障碍服务"
                btnAccessibility.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF7c3aed.toInt())
            }

            if (isRunning) {
                tvStatus.text = "✅ 运行中"
                tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                btnStart.text = "⏹ 停止"
                btnHelper.visibility = View.VISIBLE
                val stealthMode = floatingPrefs?.getBoolean("stealth_mode", false) ?: false
                btnHelper.text = if (stealthMode) "🥷 隐身模式运行中" else "📱 打开青云"
                tvHint.text = if (stealthMode) "👇 通知栏点🎯截屏识别" else "👇 切到游戏 → 点🎯截屏识别"
                tvHint.setTextColor(getColor(android.R.color.holo_orange_dark))
            } else {
                tvStatus.text = "⏸ 未启动"
                tvStatus.setTextColor(getColor(android.R.color.darker_gray))
                btnStart.text = "🚀 启动"
                btnHelper.visibility = View.GONE
                tvHint.text = "先开启无障碍服务，再点启动"
                tvHint.setTextColor(getColor(android.R.color.darker_gray))
            }
            updateApiStatus()
        } catch (e: Exception) {
            Log.e(TAG, "updateUI error", e)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            isRunning = FloatingService.isRunning
            updateUI()
        } catch (e: Exception) {
            Log.e(TAG, "onResume error", e)
        }
    }
}
