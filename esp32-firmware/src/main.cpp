/**
 * ============================================================================
 * 青云扑克 ESP32-S3-CAM - USB HID 触控验证固件 (v1.0.12)
 * ============================================================================
 * 
 * v1.0.12 变更（基于 v1.0.11 WiFi AP 验证成功）：
 *   - 加回 TinyUSB HID 触摸屏（仅 HID，不开 USB CDC）
 *   - 内嵌 HID 报告描述符（不引用 usb_hid_touchpad.cpp）
 *   - 保持 WiFi AP + HTTP 服务器（v1.0.11 功能）
 *   - 新增 /tap HTTP 端点：POST {"x":540,"y":1172} 触发屏幕点击
 * 
 * 关键点：
 *   - 不加 ARDUINO_USB_CDC_ON_BOOT（v1.0.8 崩溃根因）
 *   - 不加 ARDUINO_USB_MODE（v1.0.8 崩溃根因）
 *   - TinyUSB 仅用于 HID，不用于 Serial CDC
 *   - 串口仍走 CH343
 */

#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>
#include <Adafruit_TinyUSB.h>

// 禁用 brownout detector
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"

// ============================================================================
// 常量配置
// ============================================================================
#define FW_VERSION "v1.0.12"

// WiFi AP
#define AP_SSID     "QingYun-ESP32"
#define AP_PASSWORD "poker12345"
#define AP_IP       "192.168.4.1"
#define AP_GATEWAY  "192.168.4.1"
#define AP_SUBNET   "255.255.255.0"
#define HTTP_PORT   80

// 屏幕分辨率
#define SCREEN_WIDTH  1080
#define SCREEN_HEIGHT 2344

// HID 坐标范围
#define HID_MAX 32767

// ============================================================================
// HID 报告描述符（触摸屏 Digitizer）
// ============================================================================
static const uint8_t hid_touchpad_descriptor[] = {
    0x05, 0x0D,     // Usage Page (Digitizers)
    0x09, 0x05,     // Usage (Touch Pad)
    0xA1, 0x01,     // Collection (Application)

    0x09, 0x22,     //   Usage (Finger)
    0xA1, 0x00,     //   Collection (Logical)

    // Contact ID (1 byte)
    0x09, 0x51,     //     Usage (Contact Identifier)
    0x15, 0x00,     //     Logical Minimum (0)
    0x25, 0x01,     //     Logical Maximum (1)
    0x75, 0x08,     //     Report Size (8)
    0x95, 0x01,     //     Report Count (1)
    0x81, 0x02,     //     Input (Data, Var, Abs)

    // Tip Switch (1 bit) + Padding (7 bits)
    0x09, 0x42,     //     Usage (Tip Switch)
    0x15, 0x00,     //     Logical Minimum (0)
    0x25, 0x01,     //     Logical Maximum (1)
    0x75, 0x01,     //     Report Size (1)
    0x95, 0x01,     //     Report Count (1)
    0x81, 0x02,     //     Input (Data, Var, Abs)
    0x75, 0x07,     //     Report Size (7) - padding
    0x95, 0x01,     //     Report Count (1)
    0x81, 0x03,     //     Input (Const, Var, Abs)

    // X (16 bits, 0-32767)
    0x05, 0x01,     //     Usage Page (Generic Desktop)
    0x09, 0x30,     //     Usage (X)
    0x15, 0x00,     //     Logical Minimum (0)
    0x26, 0xFF, 0x7F, //   Logical Maximum (32767)
    0x75, 0x10,     //     Report Size (16)
    0x95, 0x01,     //     Report Count (1)
    0x81, 0x02,     //     Input (Data, Var, Abs)

    // Y (16 bits, 0-32767)
    0x09, 0x31,     //     Usage (Y)
    0x15, 0x00,     //     Logical Minimum (0)
    0x26, 0xFF, 0x7F, //   Logical Maximum (32767)
    0x75, 0x10,     //     Report Size (16)
    0x95, 0x01,     //     Report Count (1)
    0x81, 0x02,     //     Input (Data, Var, Abs)

    0xC0,           //   End Collection (Logical)

    // Contact Count (1 byte, in Application Collection)
    0x05, 0x0D,     //   Usage Page (Digitizers)
    0x09, 0x54,     //   Usage (Contact Count)
    0x15, 0x00,     //   Logical Minimum (0)
    0x25, 0x01,     //   Logical Maximum (1)
    0x75, 0x08,     //   Report Size (8)
    0x95, 0x01,     //   Report Count (1)
    0x81, 0x02,     //   Input (Data, Var, Abs)

    0xC0            // End Collection (Application)
};

// 触摸报告结构（7 bytes，与描述符严格对应）
struct __attribute__((packed)) TouchReport {
    uint8_t  contact_id;    // 触点ID (固定0)
    uint8_t  tip_switch;    // Bit0: 触摸状态, Bits1-7: 填充
    uint16_t x;             // X坐标 (0-32767)
    uint16_t y;             // Y坐标 (0-32767)
    uint8_t  contact_count; // 活跃触点数 (0或1)
};

// ============================================================================
// 全局对象
// ============================================================================
WebServer server(HTTP_PORT);

// TinyUSB HID 实例
Adafruit_USBD_HID usbHid(
    hid_touchpad_descriptor,
    sizeof(hid_touchpad_descriptor),
    HID_ITF_PROTOCOL_NONE,
    sizeof(TouchReport),
    /*interval_ms=*/10
);

// ============================================================================
// HID 触摸辅助函数
// ============================================================================

// 屏幕坐标转HID坐标
static uint16_t screenToHid(uint16_t screenVal, uint16_t screenMax)
{
    return (uint16_t)((uint32_t)screenVal * HID_MAX / screenMax);
}

// 发送触摸按下
static bool sendTouchDown(uint16_t screenX, uint16_t screenY)
{
    TouchReport report;
    report.contact_id    = 0;
    report.tip_switch    = 0x01;  // 触摸中
    report.x             = screenToHid(screenX, SCREEN_WIDTH);
    report.y             = screenToHid(screenY, SCREEN_HEIGHT);
    report.contact_count = 1;
    return usbHid.sendReport(1, &report, sizeof(report));
}

// 发送触摸抬起
static bool sendTouchUp()
{
    TouchReport report;
    report.contact_id    = 0;
    report.tip_switch    = 0x00;  // 抬起
    report.x             = 0;
    report.y             = 0;
    report.contact_count = 0;
    return usbHid.sendReport(1, &report, sizeof(report));
}

// 执行点击：按下 → 等待 → 抬起
static bool doTap(uint16_t screenX, uint16_t screenY, uint32_t durationMs)
{
    if (!usbHid.ready()) return false;

    if (!sendTouchDown(screenX, screenY)) return false;
    delay(durationMs);
    if (!sendTouchUp()) return false;

    return true;
}

// ============================================================================
// HTTP 处理函数
// ============================================================================

// POST /tap - 触发屏幕点击
void handleTap()
{
    String body = server.arg("plain");
    if (body.length() == 0) {
        server.send(400, "application/json", "{\"error\":\"Empty body\"}");
        return;
    }

    // 简单解析 JSON: {"x":540,"y":1172,"duration":50}
    int x = -1, y = -1;
    int duration = 50;

    // 手动解析（避免引入ArduinoJson库开销）
    // 格式: {"x":540,"y":1172,"duration":50}
    int xi = body.indexOf("\"x\":");
    int yi = body.indexOf("\"y\":");
    int di = body.indexOf("\"duration\":");

    if (xi >= 0) {
        int start = xi + 4;
        int end = body.indexOf(',', start);
        if (end < 0) end = body.indexOf('}', start);
        if (end > start) x = body.substring(start, end).toInt();
    }
    if (yi >= 0) {
        int start = yi + 4;
        int end = body.indexOf(',', start);
        if (end < 0) end = body.indexOf('}', start);
        if (end > start) y = body.substring(start, end).toInt();
    }
    if (di >= 0) {
        int start = di + 11;
        int end = body.indexOf(',', start);
        if (end < 0) end = body.indexOf('}', start);
        if (end > start) duration = body.substring(start, end).toInt();
    }

    if (x < 0 || x >= SCREEN_WIDTH || y < 0 || y >= SCREEN_HEIGHT) {
        char buf[128];
        snprintf(buf, sizeof(buf),
                 "{\"error\":\"Coords out of range. x:0-%d, y:0-%d. Got x=%d,y=%d\"}",
                 SCREEN_WIDTH - 1, SCREEN_HEIGHT - 1, x, y);
        server.send(400, "application/json", buf);
        return;
    }

    if (duration < 10 || duration > 5000) {
        server.send(400, "application/json", "{\"error\":\"Duration 10-5000ms\"}");
        return;
    }

    bool ok = doTap(x, y, duration);
    if (ok) {
        char buf[128];
        snprintf(buf, sizeof(buf),
                 "{\"success\":true,\"message\":\"Tap (%d,%d) dur=%dms sent\"}",
                 x, y, duration);
        server.send(200, "application/json", buf);
    } else {
        server.send(500, "application/json", "{\"error\":\"HID send failed\"}");
    }
}

// GET /status - 设备状态
void handleStatus()
{
    char buf[512];
    snprintf(buf, sizeof(buf),
        "{"
        "\"device\":\"QingYun-ESP32-CAM\","
        "\"version\":\"%s\","
        "\"uptime_ms\":%lu,"
        "\"free_heap\":%u,"
        "\"min_free_heap\":%u,"
        "\"free_psram\":%u,"
        "\"psram_size\":%u,"
        "\"chip_rev\":%d,"
        "\"flash_size_mb\":%u,"
        "\"flash_mode\":%d,"
        "\"wifi\":{\"ssid\":\"%s\",\"ip\":\"%s\",\"clients\":%d},"
        "\"hid_mounted\":%s"
        "}",
        FW_VERSION,
        (unsigned long)millis(),
        ESP.getFreeHeap(),
        ESP.getMinFreeHeap(),
        (unsigned)ESP.getFreePsram(),
        (unsigned)ESP.getPsramSize(),
        ESP.getChipRevision(),
        (unsigned)(ESP.getFlashChipSize() / (1024 * 1024)),
        (int)ESP.getFlashChipMode(),
        AP_SSID,
        WiFi.softAPIP().toString().c_str(),
        WiFi.softAPgetStationNum(),
        usbHid.ready() ? "true" : "false"
    );

    server.sendHeader("Access-Control-Allow-Origin", "*");
    server.send(200, "application/json", buf);
}

// GET / - HTML 状态页
void handleRoot()
{
    char buf[1024];
    snprintf(buf, sizeof(buf),
        "<html><head><meta charset='utf-8'><title>QingYun ESP32</title></head><body>"
        "<h2>QingYun ESP32-S3-CAM %s</h2>"
        "<p>WiFi: %s | IP: %s</p>"
        "<p>Clients: %d | Heap: %u | PSRAM: %u</p>"
        "<p>HID: %s</p>"
        "<p>Uptime: %lums</p>"
        "<hr>"
        "<h3>Test Tap</h3>"
        "<form method='POST' action='/tap'>"
        "X: <input name='x' value='540' style='width:60px'> "
        "Y: <input name='y' value='1172' style='width:60px'> "
        "Dur(ms): <input name='duration' value='50' style='width:60px'> "
        "<input type='submit' value='Tap'>"
        "</form>"
        "<p><a href='/status'>JSON API</a></p>"
        "</body></html>",
        FW_VERSION,
        AP_SSID,
        WiFi.softAPIP().toString().c_str(),
        WiFi.softAPgetStationNum(),
        ESP.getFreeHeap(),
        (unsigned)ESP.getFreePsram(),
        usbHid.ready() ? "MOUNTED" : "NOT MOUNTED",
        (unsigned long)millis()
    );
    server.sendHeader("Access-Control-Allow-Origin", "*");
    server.send(200, "text/html", buf);
}

// 404
void handleNotFound()
{
    server.send(404, "application/json", "{\"error\":\"Not found. Try: /, /status, /tap\"}");
}

// ============================================================================
// setup()
// ============================================================================
void setup()
{
    WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);

    Serial.begin(115200);
    delay(2000);

    Serial.println();
    Serial.println("========================================================");
    Serial.println("  QingYun ESP32-S3-CAM Firmware " FW_VERSION);
    Serial.println("  USB HID + WiFi AP Test - Platform v6.2.0 / Core 2.0.8");
    Serial.println("========================================================");
    Serial.println();

    // ---- 系统信息 ----
    Serial.println("---- Chip Info ----");
    Serial.printf("  ESP32-S3 Rev %d | %d MHz | %d cores | SDK %s\n",
                  ESP.getChipRevision(), ESP.getCpuFreqMHz(),
                  ESP.getChipCores(), ESP.getSdkVersion());

    Serial.println("---- Flash/PSRAM ----");
    Serial.printf("  Flash: %.1f MB mode=%d speed=%uMHz\n",
                  ESP.getFlashChipSize() / (1024.0f * 1024.0f),
                  ESP.getFlashChipMode(), ESP.getFlashChipSpeed());
    Serial.printf("  PSRAM: %.1f MB (free: %.1f MB)\n",
                  ESP.getPsramSize() / (1024.0f * 1024.0f),
                  ESP.getFreePsram() / (1024.0f * 1024.0f));
    Serial.printf("  Heap: %.1f KB\n", ESP.getFreeHeap() / 1024.0f);
    Serial.println();

    // ====================================================================
    // WiFi AP
    // ====================================================================
    Serial.println("---- WiFi AP Init ----");
    IPAddress apIP, gatewayIP, subnetMask;
    apIP.fromString(AP_IP);
    gatewayIP.fromString(AP_GATEWAY);
    subnetMask.fromString(AP_SUBNET);

    WiFi.mode(WIFI_AP);
    WiFi.softAPConfig(apIP, gatewayIP, subnetMask);

    if (!WiFi.softAP(AP_SSID, AP_PASSWORD)) {
        Serial.println("[WiFi] ERROR: AP start failed!");
    } else {
        Serial.printf("[WiFi] AP: SSID=%s IP=%s\n",
                      AP_SSID, WiFi.softAPIP().toString().c_str());
    }

    // ====================================================================
    // USB HID (TinyUSB - 仅HID，不开CDC)
    // ====================================================================
    Serial.println();
    Serial.println("---- USB HID Init ----");

    // 设置 HID 报告描述符
    usbHid.setPollInterval(10);
    usbHid.setReportDescriptor(hid_touchpad_descriptor, sizeof(hid_touchpad_descriptor));

    // 初始化 TinyUSB HID
    if (!usbHid.begin()) {
        Serial.println("[HID] ERROR: TinyUSB HID init failed!");
    } else {
        Serial.println("[HID] TinyUSB HID initialized");
    }

    // 等待 USB 挂载（给一点时间让 host 识别）
    Serial.println("[HID] Waiting for USB mount...");
    int waitCount = 0;
    while (!usbHid.ready() && waitCount < 100) {
        delay(100);
        waitCount++;
    }

    if (usbHid.ready()) {
        Serial.println("[HID] USB HID MOUNTED - touchpad device ready!");
    } else {
        Serial.println("[HID] WARNING: USB not mounted after 10s (may need host connection)");
    }

    // ---- Heap after WiFi + HID ----
    Serial.printf("\n[Status] Heap after init: %u (%.1f KB)\n",
                  ESP.getFreeHeap(), ESP.getFreeHeap() / 1024.0f);

    // ====================================================================
    // HTTP 服务器
    // ====================================================================
    Serial.println();
    Serial.println("---- HTTP Server Init ----");
    server.on("/",       HTTP_GET,  handleRoot);
    server.on("/status", HTTP_GET,  handleStatus);
    server.on("/tap",    HTTP_POST, handleTap);
    server.onNotFound(handleNotFound);
    server.begin();

    Serial.printf("[HTTP] Server on port %d\n", HTTP_PORT);
    Serial.println("[HTTP] Endpoints: GET / | GET /status | POST /tap");
    Serial.println();

    Serial.println("==========================================");
    Serial.println("  Setup COMPLETE. Entering loop...");
    Serial.printf("  WiFi: '%s' | http://%s/\n", AP_SSID, AP_IP);
    Serial.printf("  HID: %s\n", usbHid.ready() ? "MOUNTED" : "NOT MOUNTED");
    Serial.println("  Test: POST /tap with {\"x\":540,\"y\":1172,\"duration\":50}");
    Serial.println("  Heartbeat every 3s");
    Serial.println("==========================================");
}

// ============================================================================
// loop()
// ============================================================================
void loop()
{
    static int counter = 0;
    counter++;

    server.handleClient();

    Serial.printf("[v1.0.12] HB #%d | Heap: %u | PSRAM: %u | Clients: %d | HID: %s\n",
                  counter,
                  ESP.getFreeHeap(),
                  (unsigned)ESP.getFreePsram(),
                  WiFi.softAPgetStationNum(),
                  usbHid.ready() ? "OK" : "NO");

    delay(3000);
}
