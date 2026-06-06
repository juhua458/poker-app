package com.pokerhelper.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
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
    private lateinit var btnStart: Button
    private lateinit var btnHelper: Button
    private lateinit var btnSaveApi: Button
    private lateinit var spinnerProvider: Spinner
    private lateinit var etApiKey: EditText
    private var isRunning = false
    private var prefs: SharedPreferences? = null

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "语音识别需要麦克风权限", Toast.LENGTH_SHORT).show()
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    putExtra("RESULT_CODE", result.resultCode)
                    putExtra("RESULT_DATA", result.data)
                    action = "START"
                }
                startForegroundService(serviceIntent)

                val httpIntent = Intent(this, HttpServerService::class.java).apply { action = "START" }
                startForegroundService(httpIntent)

                isRunning = true
                updateUI()
                Toast.makeText(this, "🃏 截屏已启动！", Toast.LENGTH_SHORT).show()

                btnHelper.postDelayed({
                    tryLaunchFloatingHelper()
                }, 800)
            } else {
                Toast.makeText(this, "需要授权才能截屏", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Capture callback error", e)
            Toast.makeText(this, "启动出错: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            tvStatus = findViewById(R.id.tvStatus)
            tvHint = findViewById(R.id.tvHint)
            tvApiStatus = findViewById(R.id.tvApiStatus)
            btnStart = findViewById(R.id.btnStart)
            btnHelper = findViewById(R.id.btnHelper)
            btnSaveApi = findViewById(R.id.btnSaveApi)
            spinnerProvider = findViewById(R.id.spinnerProvider)
            etApiKey = findViewById(R.id.etApiKey)

            isRunning = ScreenCaptureService.isRunning
            updateUI()

            val providers = arrayOf("openai", "dashscope", "deepseek", "siliconflow")
            val providerNames = arrayOf("OpenAI (GPT-4o-mini)", "通义千问VL", "DeepSeek", "硅基流动(Qwen3-VL)")
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providerNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerProvider.adapter = adapter

            val savedProvider = prefs?.getString(KEY_PROVIDER, "siliconflow") ?: "siliconflow"
            val savedKey = prefs?.getString(KEY_APIKEY, "") ?: ""
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
                    if (isRunning) stopServices() else requestScreenCapture()
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
        } catch (e: Exception) {
            Log.e(TAG, "onCreate error", e)
        }
    }

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "需要授权「显示在其他应用上层」", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_REQUEST_CODE)
        } else {
            launchFloatingHelper()
        }
    }

    private fun launchFloatingHelper() {
        try {
            val intent = Intent(this, FloatingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Toast.makeText(this, "🃏 扑克AI助手已启动！", Toast.LENGTH_LONG).show()

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

    private fun requestScreenCapture() {
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
        } catch (e: Exception) {
            Log.e(TAG, "Request capture error", e)
            Toast.makeText(this, "请求截屏失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopServices() {
        try {
            startService(Intent(this, ScreenCaptureService::class.java).apply { action = "STOP" })
            startService(Intent(this, HttpServerService::class.java).apply { action = "STOP" })
            startService(Intent(this, FloatingService::class.java).apply { action = "STOP" })
            isRunning = false
            updateUI()
        } catch (e: Exception) {
            Log.e(TAG, "Stop error", e)
        }
    }

    private fun updateUI() {
        try {
            if (isRunning) {
                tvStatus.text = "✅ 运行中"
                tvStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                btnStart.text = "⏹ 停止截屏"
                btnHelper.visibility = View.VISIBLE
                btnHelper.text = "🃏 打开扑克AI助手"
                tvHint.text = "👇 点「打开扑克AI助手」→ 切到扑克游戏"
                tvHint.setTextColor(getColor(android.R.color.holo_orange_dark))
            } else {
                tvStatus.text = "⏸ 未启动"
                tvStatus.setTextColor(getColor(android.R.color.darker_gray))
                btnStart.text = "🚀 开始截屏"
                btnHelper.visibility = View.GONE
                tvHint.text = "先点上方按钮启动截屏"
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
            isRunning = ScreenCaptureService.isRunning
            updateUI()
        } catch (e: Exception) {
            Log.e(TAG, "onResume error", e)
        }
    }
}
