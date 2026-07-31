/**
 * ============================================================================
 * 青云扑克 ESP32-S3-CAM - USB HID 触控验证固件 (v1.0.16)
 * ============================================================================
 *
 * v1.0.16：按Freenove官方文档修正USB-OTG模式配置。
 * 根因：v1.0.15只禁了CDC(ARDUINO_USB_CDC_ON_BOOT=0)，但未设置USB模式。
 *       esp32-s3-devkitc-1板定义默认ARDUINO_USB_MODE=1(CDC/JTAG)，
 *       TinyUSB HID要求ARDUINO_USB_MODE=0(USB-OTG模式)。
 * 修复：build_unflags移除默认MODE=1，build_flags设MODE=0。
 * 依据：Freenove官方USB教程(Chapter 36)所有HID示例(Mouse/Keyboard/ConsumerControl)
 *       均要求USB CDC On Boot: Disabled + ARDUINO_USB_MODE=0。
 *
 * v1.0.15：禁用 USB CDC (ARDUINO_USB_CDC_ON_BOOT=0)
 * v1.0.14：禁用双核 TWDT，防止 USB 枚举等待期间被复位
 * v1.0.13：修复 USB HID NOT MOUNTED —— 在 touchpad.begin() 前加 USB.begin()
 *
 * 核心实现（依据 arduino-esp32 v2.0.8 官方 USBHIDKeyboard.cpp 模式）：
 *   - 继承 USBHIDDevice，重写 _onGetDescriptor() 提供自定义触摸描述符
 *   - 内部持有 USBHID hid，构造时 hid.addDevice(this, desc_size)
 *   - 通过 hid.SendReport() 发送触摸报告
 *   - 不依赖 Adafruit TinyUSB（编译不兼容）
 *   - platformio.ini: ARDUINO_USB_MODE=0 + ARDUINO_USB_CDC_ON_BOOT=0
 */

#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>
#include <USB.h>
#include <USBHID.h>
#include <esp_task_wdt.h>

// 禁用 brownout detector
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"

// ============================================================================
// 常量配置
// ============================================================================
#define FW_VERSION "v1.0.16"

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

// HID Report ID
#define HID_REPORT_ID_TOUCH 1

// ============================================================================
// HID 报告描述符（触摸屏 Digitizer）
// ============================================================================
static const uint8_t touch_report_descriptor[] = {
    0x05, 0x0D,             // Usage Page (Digitizers)
    0x09, 0x05,             // Usage (Touch Pad)
    0xA1, 0x01,             // Collection (Application)

    0x09, 0x22,             //   Usage (Finger)
    0xA1, 0x02,             //   Collection (Logical)

    // Contact ID (1 byte)
    0x09, 0x51,             //     Usage (Contact Identifier)
    0x15, 0x00,             //     Logical Minimum (0)
    0x25, 0x01,             //     Logical Maximum (1)
    0x75, 0x08,             //     Report Size (8)
    0x95, 0x01,             //     Report Count (1)
    0x81, 0x02,             //     Input (Data, Var, Abs)

    // Tip Switch (1 bit) + Padding (7 bits)
    0x09, 0x42,             //     Usage (Tip Switch)
    0x15, 0x00,             //     Logical Minimum (0)
    0x25, 0x01,             //     Logical Maximum (1)
    0x75, 0x01,             //     Report Size (1)
    0x95, 0x01,             //     Report Count (1)
    0x81, 0x02,             //     Input (Data, Var, Abs)
    0x75, 0x07,             //     Report Size (7) - padding
    0x95, 0x01,             //     Report Count (1)
    0x81, 0x03,             //     Input (Const, Var, Abs)

    // X (16 bits, 0-32767)
    0x05, 0x01,             //     Usage Page (Generic Desktop)
    0x09, 0x30,             //     Usage (X)
    0x15, 0x00,             //     Logical Minimum (0)
    0x26, 0xFF, 0x7F,      //     Logical Maximum (32767)
    0x75, 0x10,             //     Report Size (16)
    0x95, 0x01,             //     Report Count (1)
    0x81, 0x02,             //     Input (Data, Var, Abs)

    // Y (16 bits, 0-32767)
    0x09, 0x31,             //     Usage (Y)
    0x15, 0x00,             //     Logical Minimum (0)
    0x26, 0xFF, 0x7F,      //     Logical Maximum (32767)
    0x75, 0x10,             //     Report Size (16)
    0x95, 0x01,             //     Report Count (1)
    0x81, 0x02,             //     Input (Data, Var, Abs)

    0xC0,                   //   End Collection (Logical)

    // Contact Count (1 byte)
    0x05, 0x0D,             //   Usage Page (Digitizers)
    0x09, 0x54,             //   Usage (Contact Count)
    0x15, 0x00,             //   Logical Minimum (0)
    0x25, 0x01,             //   Logical Maximum (1)
    0x75, 0x08,             //   Report Size (8)
    0x95, 0x01,             //   Report Count (1)
    0x81, 0x02,             //   Input (Data, Var, Abs)

    0xC0                    // End Collection (Application)
};

// 触摸报告结构（7 bytes packed）
struct __attribute__((packed)) TouchReport {
    uint8_t  contact_id;
    uint8_t  tip_switch;    // Bit0: 触摸状态
    uint16_t x;             // 0-32767
    uint16_t y;             // 0-32767
    uint8_t  contact_count;
};

// ============================================================================
// USB HID 触控设备类（遵循 USBHIDKeyboard 模式）
// ============================================================================
class USBHIDTouchpad : public USBHIDDevice {
private:
    USBHID hid;
    TouchReport _report;

public:
    USBHIDTouchpad() : hid() {
        static bool initialized = false;
        if (!initialized) {
            initialized = true;
            hid.addDevice(this, sizeof(touch_report_descriptor));
        }
    }

    void begin() {
        hid.begin();
    }

    bool ready() {
        return hid.ready();
    }

    // USBHIDDevice 接口实现：返回 HID 报告描述符
    uint16_t _onGetDescriptor(uint8_t* buffer) override {
        memcpy(buffer, touch_report_descriptor, sizeof(touch_report_descriptor));
        return sizeof(touch_report_descriptor);
    }

    // 发送触摸按下
    bool touchDown(uint16_t screenX, uint16_t screenY) {
        if (!hid.ready()) return false;
        _report.contact_id    = 0;
        _report.tip_switch    = 0x01;
        _report.x             = (uint16_t)((uint32_t)screenX * HID_MAX / SCREEN_WIDTH);
        _report.y             = (uint16_t)((uint32_t)screenY * HID_MAX / SCREEN_HEIGHT);
        _report.contact_count = 1;
        return hid.SendReport(HID_REPORT_ID_TOUCH, &_report, sizeof(_report));
    }

    // 发送触摸抬起
    bool touchUp() {
        if (!hid.ready()) return false;
        _report.contact_id    = 0;
        _report.tip_switch    = 0x00;
        _report.x             = 0;
        _report.y             = 0;
        _report.contact_count = 0;
        return hid.SendReport(HID_REPORT_ID_TOUCH, &_report, sizeof(_report));
    }

    // 执行点击
    bool tap(uint16_t screenX, uint16_t screenY, uint32_t durationMs) {
        if (!touchDown(screenX, screenY)) return false;
        delay(durationMs);
        return touchUp();
    }
};

// 全局实例
static USBHIDTouchpad touchpad;

// ============================================================================
// HTTP 处理函数
// ============================================================================
WebServer server(HTTP_PORT);

void handleTap() {
    String body = server.arg("plain");
    if (body.length() == 0) {
        server.send(400, "application/json", "{\"error\":\"Empty body\"}");
        return;
    }

    int x = -1, y = -1, duration = 50;
    int xi = body.indexOf("\"x\":");
    int yi = body.indexOf("\"y\":");
    int di = body.indexOf("\"duration\":");

    if (xi >= 0) { int s = xi+4; int e = body.indexOf(',',s); if(e<0)e=body.indexOf('}',s); if(e>s)x=body.substring(s,e).toInt(); }
    if (yi >= 0) { int s = yi+4; int e = body.indexOf(',',s); if(e<0)e=body.indexOf('}',s); if(e>s)y=body.substring(s,e).toInt(); }
    if (di >= 0) { int s = di+11; int e = body.indexOf(',',s); if(e<0)e=body.indexOf('}',s); if(e>s)duration=body.substring(s,e).toInt(); }

    if (x < 0 || x >= SCREEN_WIDTH || y < 0 || y >= SCREEN_HEIGHT) {
        char buf[128];
        snprintf(buf, sizeof(buf),
                 "{\"error\":\"Coords out of range. x:0-%d, y:0-%d. Got x=%d,y=%d\"}",
                 SCREEN_WIDTH-1, SCREEN_HEIGHT-1, x, y);
        server.send(400, "application/json", buf);
        return;
    }
    if (duration < 10 || duration > 5000) {
        server.send(400, "application/json", "{\"error\":\"Duration 10-5000ms\"}");
        return;
    }

    bool ok = touchpad.tap(x, y, duration);
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

void handleStatus() {
    char buf[512];
    snprintf(buf, sizeof(buf),
        "{\"device\":\"QingYun-ESP32-S3-CAM\",\"version\":\"%s\","
        "\"uptime_ms\":%lu,\"free_heap\":%u,\"free_psram\":%u,"
        "\"wifi\":{\"ssid\":\"%s\",\"ip\":\"%s\",\"clients\":%d},"
        "\"hid_ready\":%s}",
        FW_VERSION, (unsigned long)millis(),
        ESP.getFreeHeap(), (unsigned)ESP.getFreePsram(),
        AP_SSID, WiFi.softAPIP().toString().c_str(),
        WiFi.softAPgetStationNum(),
        touchpad.ready() ? "true" : "false");
    server.sendHeader("Access-Control-Allow-Origin", "*");
    server.send(200, "application/json", buf);
}

void handleRoot() {
    char buf[1024];
    snprintf(buf, sizeof(buf),
        "<html><head><meta charset='utf-8'><title>QingYun ESP32</title></head><body>"
        "<h2>QingYun ESP32-S3-CAM %s</h2>"
        "<p>WiFi: %s | IP: %s</p>"
        "<p>Clients: %d | Heap: %u | PSRAM: %u</p>"
        "<p>HID: %s</p>"
        "<p>Uptime: %lums</p>"
        "<hr><h3>Test Tap</h3>"
        "<form method='POST' action='/tap'>"
        "X:<input name='x' value='540' style='width:60px'> "
        "Y:<input name='y' value='1172' style='width:60px'> "
        "Dur:<input name='duration' value='50' style='width:60px'> "
        "<input type='submit' value='Tap'></form>"
        "<p><a href='/status'>JSON API</a></p></body></html>",
        FW_VERSION, AP_SSID, WiFi.softAPIP().toString().c_str(),
        WiFi.softAPgetStationNum(), ESP.getFreeHeap(),
        (unsigned)ESP.getFreePsram(),
        touchpad.ready() ? "MOUNTED" : "NOT MOUNTED",
        (unsigned long)millis());
    server.sendHeader("Access-Control-Allow-Origin", "*");
    server.send(200, "text/html", buf);
}

void handleNotFound() {
    server.send(404, "application/json", "{\"error\":\"Not found. Try: /, /status, /tap\"}");
}

// ============================================================================
// setup()
// ============================================================================
void setup() {
    WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);

    Serial.begin(115200);
    delay(2000);

    Serial.println();
    Serial.println("========================================================");
    Serial.println("  QingYun ESP32-S3-CAM Firmware " FW_VERSION);
    Serial.println("  USB-OTG HID (MODE=0, CDC=0) + WiFi AP");
    Serial.println("========================================================");
    Serial.println();

    // 系统信息
    Serial.printf("  ESP32-S3 Rev %d | %d MHz | %d cores | SDK %s\n",
                  ESP.getChipRevision(), ESP.getCpuFreqMHz(),
                  ESP.getChipCores(), ESP.getSdkVersion());
    Serial.printf("  Flash: %.1f MB mode=%d speed=%uMHz\n",
                  ESP.getFlashChipSize() / (1024.0f * 1024.0f),
                  ESP.getFlashChipMode(), ESP.getFlashChipSpeed());
    Serial.printf("  PSRAM: %.1f MB (free: %.1f MB)\n",
                  ESP.getPsramSize() / (1024.0f * 1024.0f),
                  ESP.getFreePsram() / (1024.0f * 1024.0f));
    Serial.printf("  Heap: %.1f KB\n", ESP.getFreeHeap() / 1024.0f);
    Serial.println();

    // ---- WiFi AP ----
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

    // ---- USB HID ----
    Serial.println();
    Serial.println("---- USB HID Init ----");

    // v1.0.13 修复：USBHID::begin() 只创建信号量，不调用 USB.begin() 启动 TinyUSB
    // 必须先调用 USB.begin() 启动 TinyUSB 栈，否则 HID 设备永远不会 MOUNTED
    if (!USB.begin()) {
        Serial.println("[USB] ERROR: USB.begin() failed!");
    } else {
        Serial.println("[USB] USB.begin() OK - TinyUSB started");
    }

    // 等待 TinyUSB 栈稳定
    delay(500);

    touchpad.begin();
    Serial.println("[HID] USBHIDTouchpad initialized (USBHIDDevice pattern)");

    // v1.0.14 修复：禁用双核 Task Watchdog Timer，防止 USB 枚举等待期间被复位
    // ESP32-S3 TWDT 默认超时 ~5s，USB mount 等待可能超过此阈值
    // 使用 Arduino-ESP32 原生 API 禁用 TWDT
    disableCore0WDT();
    disableCore1WDT();
    Serial.println("[TWDT] Dual-core Task WDT disabled for USB mount wait");

    Serial.println("[HID] Waiting for USB mount...");
    int waitCount = 0;
    while (!touchpad.ready() && waitCount < 100) {
        delay(100);
        waitCount++;
    }
    if (touchpad.ready()) {
        Serial.println("[HID] USB HID MOUNTED - touchpad ready!");
    } else {
        Serial.println("[HID] WARNING: USB not mounted after 10s");
    }

    Serial.printf("[Status] Heap after init: %u (%.1f KB)\n",
                  ESP.getFreeHeap(), ESP.getFreeHeap() / 1024.0f);

    // ---- HTTP 服务器 ----
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
    Serial.printf("  HID: %s\n", touchpad.ready() ? "MOUNTED" : "NOT MOUNTED");
    Serial.println("  Test: POST /tap {\"x\":540,\"y\":1172,\"duration\":50}");
    Serial.println("==========================================");
}

// ============================================================================
// loop()
// ============================================================================
void loop() {
    static int counter = 0;
    counter++;

    server.handleClient();

    Serial.printf("[%s] HB #%d | Heap: %u | PSRAM: %u | Clients: %d | HID: %s\n",
                  FW_VERSION, counter,
                  ESP.getFreeHeap(),
                  (unsigned)ESP.getFreePsram(),
                  WiFi.softAPgetStationNum(),
                  touchpad.ready() ? "OK" : "NO");

    delay(3000);
}
