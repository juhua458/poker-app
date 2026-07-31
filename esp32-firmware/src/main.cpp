/**
 * ============================================================================
 * 青云扑克 ESP32-S3-CAM - WiFi AP 验证固件 (v1.0.11)
 * ============================================================================
 * 
 * v1.0.11 变更（基于 v1.0.10 平台验证成功）：
 *   - 加回 WiFi AP 热点功能
 *   - 创建 SSID: QingYun-ESP32 的 WiFi 热点
 *   - 启动轻量 HTTP 服务器（仅 /status 端点，无外部依赖）
 *   - 心跳增加 WiFi 状态和已连接客户端数
 * 
 * 预期行为：
 *   - 串口输出 banner + 系统信息
 *   - WiFi AP 启动成功，SSID=QingYun-ESP32, IP=192.168.4.1
 *   - HTTP 服务器监听 80 端口
 *   - 手机连接 WiFi 后访问 http://192.168.4.1/status 可获取设备状态 JSON
 *   - 心跳稳定输出，Heap 无明显泄漏
 */

#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>

// 禁用 brownout detector
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"

// ============================================================================
// 常量配置
// ============================================================================
#define FW_VERSION "v1.0.11"

// WiFi AP 配置
#define AP_SSID     "QingYun-ESP32"
#define AP_PASSWORD "poker12345"
#define AP_IP       "192.168.4.1"
#define AP_GATEWAY  "192.168.4.1"
#define AP_SUBNET   "255.255.255.0"
#define HTTP_PORT   80

// 全局 HTTP 服务器
WebServer server(HTTP_PORT);

// ============================================================================
// HTTP 处理函数
// ============================================================================

// GET /status - 设备状态（轻量版，无外部模块依赖）
void handleStatus()
{
    String json = "{";
    json += "\"device\":\"QingYun-ESP32-CAM\",";
    json += "\"version\":\"" FW_VERSION "\",";
    json += "\"uptime_ms\":" + String(millis()) + ",";
    json += "\"free_heap\":" + String(ESP.getFreeHeap()) + ",";
    json += "\"min_free_heap\":" + String(ESP.getMinFreeHeap()) + ",";
    json += "\"free_psram\":" + String(ESP.getFreePsram()) + ",";
    json += "\"chip_model\":\"ESP32-S3\",";
    json += "\"chip_rev\":" + String(ESP.getChipRevision()) + ",";
    json += "\"cpu_freq_mhz\":" + String(ESP.getCpuFreqMHz()) + ",";
    json += "\"flash_size_mb\":" + String(ESP.getFlashChipSize() / (1024 * 1024)) + ",";
    json += "\"flash_mode\":" + String(ESP.getFlashChipMode()) + ",";
    json += "\"psram_size_mb\":" + String(ESP.getPsramSize() / (1024 * 1024)) + ",";
    json += "\"wifi\":{";
    json += "\"ssid\":\"" + String(AP_SSID) + "\",";
    json += "\"ip\":\"" + WiFi.softAPIP().toString() + "\",";
    json += "\"clients\":" + String(WiFi.softAPgetStationNum());
    json += "}";
    json += "}";

    server.sendHeader("Access-Control-Allow-Origin", "*");
    server.send(200, "application/json", json);
}

// GET / - 根路径，返回简单 HTML 页面
void handleRoot()
{
    String html = "<html><head><meta charset='utf-8'><title>QingYun ESP32</title></head><body>";
    html += "<h2>QingYun ESP32-S3-CAM " FW_VERSION "</h2>";
    html += "<p>WiFi AP: " + String(AP_SSID) + " | IP: " + WiFi.softAPIP().toString() + "</p>";
    html += "<p>Clients: " + String(WiFi.softAPgetStationNum()) + "</p>";
    html += "<p>Heap: " + String(ESP.getFreeHeap()) + " | PSRAM: " + String(ESP.getFreePsram()) + "</p>";
    html += "<p>Uptime: " + String(millis() / 1000) + "s</p>";
    html += "<hr><p><a href='/status'>/status</a> - JSON API</p>";
    html += "</body></html>";
    server.sendHeader("Access-Control-Allow-Origin", "*");
    server.send(200, "text/html", html);
}

// 404 处理
void handleNotFound()
{
    String json = "{\"error\":\"Not found. Available: /status, /\"}";
    server.send(404, "application/json", json);
}

// ============================================================================
// setup()
// ============================================================================
void setup()
{
    // 禁用 brownout detector
    WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);

    Serial.begin(115200);
    delay(2000);

    Serial.println();
    Serial.println("========================================================");
    Serial.println("  QingYun ESP32-S3-CAM Firmware " FW_VERSION);
    Serial.println("  WiFi AP Test - Platform v6.2.0 / Core 2.0.8");
    Serial.println("========================================================");
    Serial.println();

    // ---- 芯片信息 ----
    Serial.println("---- Chip Info ----");
    Serial.printf("  Chip Model: ESP32-S3, Rev %d\n", ESP.getChipRevision());
    Serial.printf("  CPU Freq: %d MHz\n", ESP.getCpuFreqMHz());
    Serial.printf("  Cores: %d\n", ESP.getChipCores());
    Serial.printf("  SDK Version: %s\n", ESP.getSdkVersion());
    Serial.println();

    // ---- Flash 信息 ----
    Serial.println("---- Flash Info ----");
    Serial.printf("  Flash Size: %u bytes (%.1f MB)\n",
                  ESP.getFlashChipSize(),
                  ESP.getFlashChipSize() / (1024.0f * 1024.0f));
    Serial.printf("  Flash Speed: %u MHz\n", ESP.getFlashChipSpeed());
    Serial.printf("  Flash Mode: %d (0=QIO, 1=QOUT, 2=DIO, 3=DOUT)\n",
                  ESP.getFlashChipMode());
    Serial.println();

    // ---- PSRAM 信息 ----
    Serial.println("---- PSRAM Info ----");
    size_t psramSize = ESP.getPsramSize();
    size_t freePsram = ESP.getFreePsram();
    Serial.printf("  PSRAM Size: %u bytes (%.1f MB)\n",
                  psramSize, psramSize / (1024.0f * 1024.0f));
    Serial.printf("  Free PSRAM: %u bytes (%.1f MB)\n",
                  freePsram, freePsram / (1024.0f * 1024.0f));
    if (psramSize > 0) {
        Serial.println("  [OK] PSRAM DETECTED");
    } else {
        Serial.println("  [WARN] PSRAM NOT DETECTED");
    }
    Serial.println();

    // ---- Heap 信息（WiFi前） ----
    Serial.println("---- Heap Info (before WiFi) ----");
    Serial.printf("  Free Heap: %u bytes (%.1f KB)\n",
                  ESP.getFreeHeap(), ESP.getFreeHeap() / 1024.0f);
    Serial.println();

    // ====================================================================
    // WiFi AP 初始化
    // ====================================================================
    Serial.println("---- WiFi AP Init ----");
    Serial.println("[WiFi] Starting AP mode...");

    IPAddress apIP, gatewayIP, subnetMask;
    apIP.fromString(AP_IP);
    gatewayIP.fromString(AP_GATEWAY);
    subnetMask.fromString(AP_SUBNET);

    WiFi.mode(WIFI_AP);
    WiFi.softAPConfig(apIP, gatewayIP, subnetMask);

    if (!WiFi.softAP(AP_SSID, AP_PASSWORD)) {
        Serial.println("[WiFi] ERROR: Failed to start AP!");
        // 不致命，继续运行（心跳还能看）
    } else {
        Serial.printf("[WiFi] AP started: SSID=%s, IP=%s\n",
                      AP_SSID, WiFi.softAPIP().toString().c_str());
    }

    // ---- Heap 信息（WiFi后） ----
    Serial.printf("[WiFi] Heap after AP init: %u bytes (%.1f KB)\n",
                  ESP.getFreeHeap(), ESP.getFreeHeap() / 1024.0f);
    Serial.println();

    // ====================================================================
    // HTTP 服务器初始化
    // ====================================================================
    Serial.println("---- HTTP Server Init ----");
    server.on("/",           HTTP_GET, handleRoot);
    server.on("/status",     HTTP_GET, handleStatus);
    server.onNotFound(handleNotFound);
    server.begin();

    Serial.printf("[HTTP] Server started on port %d\n", HTTP_PORT);
    Serial.println("[HTTP] Endpoints:");
    Serial.println("  GET  /       - Status page (HTML)");
    Serial.println("  GET  /status - Status JSON");
    Serial.println();

    Serial.println("==========================================");
    Serial.println("  Setup COMPLETE. Entering loop...");
    Serial.printf("  Connect to WiFi '%s'\n", AP_SSID);
    Serial.printf("  Visit http://%s/ or http://%s/status\n", AP_IP, AP_IP);
    Serial.println("  Heartbeat every 3s = WiFi AP + HTTP OK");
    Serial.println("==========================================");
}

// ============================================================================
// loop()
// ============================================================================
void loop()
{
    static int counter = 0;
    counter++;

    // 处理 HTTP 请求
    server.handleClient();

    // 打印心跳
    Serial.printf("[v1.0.11] HB #%d | Heap: %u | PSRAM: %u | WiFi clients: %d\n",
                  counter,
                  ESP.getFreeHeap(),
                  ESP.getFreePsram(),
                  WiFi.softAPgetStationNum());

    delay(3000);
}
