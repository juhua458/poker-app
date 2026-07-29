/**
 * ============================================================================
 * 青云扑克 ESP32-S3-CAM - 主入口
 * ============================================================================
 * 
 * 功能：初始化各模块并运行主循环
 * - USB HID 触摸屏
 * - WiFi AP + HTTP 服务器
 * - OV5640 摄像头
 * - OTA 无线更新
 * - 行为随机化
 * - 看门狗定时器
 * 
 * 系统架构:
 *   [手机] --USB-C--> [ESP32-S3 HID Digitizer]  (触摸输入)
 *   [手机] --WiFi---> [ESP32-S3 HTTP Server]    (控制指令)
 *   [ESP32-S3 摄像头] --WiFi---> [手机]          (画面预览)
 * 
 * ============================================================================
 */

#include <Arduino.h>
#include <esp_task_wdt.h>
#include "usb_hid_touchpad.h"
#include "wifi_ap_server.h"
#include "camera_driver.h"
#include "ota_updater.h"
#include "behavior_randomizer.h"

// ============================================================================
// 全局对象
// ============================================================================
USBHIDTouchpad      g_hidTouchpad;
CameraDriver        g_camera;
OTAUpdater          g_ota;
BehaviorRandomizer  g_randomizer;
WiFiAPServer*       g_server = nullptr;

// ============================================================================
// 看门狗配置
// ============================================================================
constexpr int WDT_TIMEOUT_S = 30;  // 看门狗超时 30 秒

// ============================================================================
// 函数声明
// ============================================================================
void setup();
void loop();
void _initSerial();
void _initWatchdog();
void _feedWatchdog();
void _printBanner();
void _printSystemInfo();
void _handleUSBReconnect();

// ============================================================================
// setup() - 系统初始化
// ============================================================================
void setup()
{
    // 1. 初始化串口 (UART0, 用于调试输出)
    _initSerial();

    // 打印启动横幅
    _printBanner();

    // 2. 初始化看门狗
    _initWatchdog();

    // 3. 初始化行为随机化
    g_randomizer.begin();

    // 4. 初始化 USB HID 触摸屏
    Serial.println("[MAIN] Initializing USB HID Touchpad...");
    g_hidTouchpad.begin();
    // 注意：USB 设备挂载需要时间，在主循环中等待

    // 5. 初始化摄像头
    Serial.println("[MAIN] Initializing OV5640 Camera...");
    if (g_camera.begin(FRAMESIZE_SVGA, 12)) {
        Serial.println("[MAIN] Camera OK");
    } else {
        Serial.println("[MAIN] Camera FAILED - continuing without camera");
    }

    // 6. 初始化 OTA
    g_ota.begin();

    // 7. 初始化 WiFi AP + HTTP 服务器
    Serial.println("[MAIN] Starting WiFi AP + HTTP Server...");
    g_server = new WiFiAPServer(&g_hidTouchpad, &g_camera, &g_ota, &g_randomizer);
    if (g_server && g_server->begin()) {
        Serial.println("[MAIN] WiFi AP + HTTP Server OK");
    } else {
        Serial.println("[MAIN] WiFi AP + HTTP Server FAILED!");
    }

    // 8. 打印系统信息
    _printSystemInfo();

    Serial.println("[MAIN] ========== System Ready ==========");
    Serial.printf("[MAIN] Connect to WiFi: %s (password: %s)\n",
                  DEFAULT_AP_SSID, DEFAULT_AP_PASSWORD);
    Serial.printf("[MAIN] Then open: http://%s/status\n", DEFAULT_AP_IP);
    Serial.println("[MAIN] ===================================");
}

// ============================================================================
// loop() - 主循环
// ============================================================================
void loop()
{
    // 1. 喂狗
    _feedWatchdog();

    // 2. 处理 HTTP 请求
    if (g_server) {
        g_server->handleClient();
    }

    // 3. 检查 USB 连接状态
    _handleUSBReconnect();

    // 4. 短暂延迟 (避免 CPU 100%)
    delay(2);
}

// ============================================================================
// 辅助函数实现
// ============================================================================

void _initSerial()
{
    Serial.begin(115200);
    delay(500);  // 等待串口就绪
    Serial.println();
    Serial.println("========================================================");
    Serial.println("  QingYun ESP32-S3-CAM Firmware v1.0.0");
    Serial.println("  USB HID Touch Screen + WiFi AP + Camera + OTA");
    Serial.println("========================================================");
}

void _initWatchdog()
{
    Serial.printf("[MAIN] Initializing watchdog (timeout=%ds)...\n", WDT_TIMEOUT_S);
    esp_err_t err = esp_task_wdt_init(WDT_TIMEOUT_S, true);  // true = 触发重启
    if (err == ESP_OK) {
        esp_task_wdt_add(NULL);  // 添加当前任务到看门狗
        Serial.println("[MAIN] Watchdog initialized");
    } else {
        Serial.printf("[MAIN] Watchdog init failed: 0x%x\n", err);
    }
}

void _feedWatchdog()
{
    esp_task_wdt_reset();
}

void _printBanner()
{
    Serial.println();
    Serial.println("  ╔═══════════════════════════════════════╗");
    Serial.println("  ║    QingYun Poker - ESP32-S3-CAM      ║");
    Serial.println("  ║    USB HID + WiFi AP + Camera + OTA   ║");
    Serial.println("  ╚═══════════════════════════════════════╝");
    Serial.println();
}

void _printSystemInfo()
{
    Serial.println("---- System Info ----");
    Serial.printf("  Chip: ESP32-S3, Rev %d\n", ESP.getChipRevision());
    Serial.printf("  CPU Freq: %d MHz\n", ESP.getCpuFreqMHz());
    Serial.printf("  Free Heap: %u bytes (%.1f KB)\n",
                  ESP.getFreeHeap(), ESP.getFreeHeap() / 1024.0f);
    Serial.printf("  Free PSRAM: %u bytes (%.1f KB)\n",
                  g_camera.getFreePsram(), g_camera.getFreePsram() / 1024.0f);
    Serial.printf("  Min Free Heap: %u bytes\n", ESP.getMinFreeHeap());
    Serial.printf("  Flash Size: %u bytes (%.1f MB)\n",
                  ESP.getFlashChipSize(), ESP.getFlashChipSize() / (1024.0f * 1024.0f));
    Serial.printf("  SDK Version: %s\n", ESP.getSdkVersion());
    Serial.printf("  USB HID Mounted: %s\n",
                  g_hidTouchpad.isMounted() ? "Yes" : "No");
    Serial.printf("  Camera Initialized: %s\n",
                  g_camera.isInitialized() ? "Yes" : "No");
    if (g_server) {
        Serial.printf("  WiFi AP IP: %s\n", g_server->getAPIP().c_str());
    }
    Serial.println("---------------------");
}

void _handleUSBReconnect()
{
    // 静默监控 USB 连接状态
    // 如果 USB 断开后重新连接，可能需要重新初始化
    // TinyUSB 库通常会自动处理重连
    static bool wasMounted = false;
    bool isMounted = g_hidTouchpad.isMounted();

    if (isMounted && !wasMounted) {
        Serial.println("[MAIN] USB HID device mounted");
    } else if (!isMounted && wasMounted) {
        Serial.println("[MAIN] USB HID device disconnected");
    }
    wasMounted = isMounted;
}
