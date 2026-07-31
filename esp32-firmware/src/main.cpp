/**
 * ============================================================================
 * 青云扑克 ESP32-S3-CAM - 主入口 (v1.0.6 - ESP-IDF降级到6.5.0)
 * ============================================================================
 * 
 * v1.0.6 变更：
 *   - ESP-IDF 平台降级到 espressif32@6.5.0（修复框架级 StoreProhibited 崩溃）
 *   - flash_mode = DIO（匹配硬件实际工作模式）
 *   - 无看门狗、无摄像头
 *   - 保留 WiFi AP + USB HID + OTA + 行为随机化
 */

#include <Arduino.h>
#include "usb_hid_touchpad.h"
#include "wifi_ap_server.h"
#include "ota_updater.h"
#include "behavior_randomizer.h"

// ============================================================================
// 全局对象
// ============================================================================
USBHIDTouchpad      g_hidTouchpad;
OTAUpdater          g_ota;
BehaviorRandomizer  g_randomizer;
WiFiAPServer*       g_server = nullptr;

// ============================================================================
// 函数声明
// ============================================================================
void setup();
void loop();
void _initSerial();
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

    // 2. 初始化行为随机化
    g_randomizer.begin();

    // 3. 初始化 USB HID 触摸屏
    Serial.println("[MAIN] Initializing USB HID Touchpad...");
    g_hidTouchpad.begin();
    // 注意：USB 设备挂载需要时间，在主循环中等待

    // 4. [v1.0.6] 摄像头模块已禁用（PSRAM 不可用）
    // TODO: 确认 PSRAM 硬件状态后重新启用摄像头

    // 5. 初始化 OTA
    g_ota.begin();

    // 6. 初始化 WiFi AP + HTTP 服务器
    //    注意：WiFiAPServer 构造需要 camera 指针，暂传 nullptr
    Serial.println("[MAIN] Starting WiFi AP + HTTP Server...");
    g_server = new WiFiAPServer(&g_hidTouchpad, nullptr, &g_ota, &g_randomizer);
    if (g_server && g_server->begin()) {
        Serial.println("[MAIN] WiFi AP + HTTP Server OK");
    } else {
        Serial.println("[MAIN] WiFi AP + HTTP Server FAILED!");
    }

    // 7. 打印系统信息
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
    // 1. 处理 HTTP 请求
    if (g_server) {
        g_server->handleClient();
    }

    // 2. 检查 USB 连接状态
    _handleUSBReconnect();

    // 3. 短暂延迟 (避免 CPU 100%)
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
    Serial.println("  QingYun ESP32-S3-CAM Firmware v1.0.6");
    Serial.println("  USB HID + WiFi AP + OTA (DIO, ESP-IDF 6.5.0, No CAM)");
    Serial.println("========================================================");
}

void _printBanner()
{
    Serial.println();
    Serial.println("  +=======================================+");
    Serial.println("  |    QingYun Poker - ESP32-S3-CAM       |");
    Serial.println("  |    v1.0.6 - DIO, ESP-IDF 6.5.0, No CAM  |");
    Serial.println("  +=======================================+");
    Serial.println();
}

void _printSystemInfo()
{
    Serial.println("---- System Info ----");
    Serial.printf("  Chip: ESP32-S3, Rev %d\n", ESP.getChipRevision());
    Serial.printf("  CPU Freq: %d MHz\n", ESP.getCpuFreqMHz());
    Serial.printf("  Free Heap: %u bytes (%.1f KB)\n",
                  ESP.getFreeHeap(), ESP.getFreeHeap() / 1024.0f);
    Serial.printf("  Min Free Heap: %u bytes\n", ESP.getMinFreeHeap());
    Serial.printf("  Flash Size: %u bytes (%.1f MB)\n",
                  ESP.getFlashChipSize(), ESP.getFlashChipSize() / (1024.0f * 1024.0f));
    Serial.printf("  SDK Version: %s\n", ESP.getSdkVersion());
    Serial.printf("  USB HID Mounted: %s\n",
                  g_hidTouchpad.isMounted() ? "Yes" : "No");
    Serial.printf("  Camera: DISABLED (v1.0.6)\n");
    if (g_server) {
        Serial.printf("  WiFi AP IP: %s\n", g_server->getAPIP().c_str());
    }
    Serial.println("---------------------");
}

void _handleUSBReconnect()
{
    // 静默监控 USB 连接状态
    static bool wasMounted = false;
    bool isMounted = g_hidTouchpad.isMounted();

    if (isMounted && !wasMounted) {
        Serial.println("[MAIN] USB HID device mounted");
    } else if (!isMounted && wasMounted) {
        Serial.println("[MAIN] USB HID device disconnected");
    }
    wasMounted = isMounted;
}
