package com.pokerhelper.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val TAG = "PokerMainActivity"
    private val OVERLAY_REQUEST_CODE = 1001

    private lateinit var tvStatus: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnStart: Button
    private lateinit var btnHelper: Button
    private var isRunning = false

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

                // Auto-launch floating helper
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

            tvStatus = findViewById(R.id.tvStatus)
            tvHint = findViewById(R.id.tvHint)
            btnStart = findViewById(R.id.btnStart)
            btnHelper = findViewById(R.id.btnHelper)

            isRunning = ScreenCaptureService.isRunning
            updateUI()

            btnStart.setOnClickListener {
                try {
                    if (isRunning) stopServices() else requestScreenCapture()
                } catch (e: Exception) {
                    Log.e(TAG, "Btn click error", e)
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

            // Go home so user can see the game
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
                btnHelper.visibility = android.view.View.VISIBLE
                btnHelper.text = "🃏 打开扑克AI助手"
                tvHint.text = "👇 点「打开扑克AI助手」→ 切到扑克游戏"
                tvHint.setTextColor(getColor(android.R.color.holo_orange_dark))
            } else {
                tvStatus.text = "⏸ 未启动"
                tvStatus.setTextColor(getColor(android.R.color.darker_gray))
                btnStart.text = "🚀 开始截屏"
                btnHelper.visibility = android.view.View.GONE
                tvHint.text = "先点上方按钮启动截屏"
                tvHint.setTextColor(getColor(android.R.color.darker_gray))
            }
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
