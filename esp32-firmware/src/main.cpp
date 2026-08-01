/**
 * ============================================================================
 * 青云扑克 ESP32-S3-CAM - USB HID 触控验证固件 (v1.0.18)
 * ============================================================================
 *
 * v1.0.18：全链路 USB debug 诊断固件
 *   - 添加 USB PHY 寄存器状态读取（USB_WRAP_DATE, RTC_CNTL_USB_CONF 等）
 *   - 添加 USB operator bool() 检测（检查 _started && tinyusb_device_mounted）
 *   - 添加 USB 事件回调（ARDUINO_USB_STARTED/STOPPED/SUSPEND/RESUME）
 *   - USB mount 等待延长至 30 秒，每 5 秒输出状态
 *   - 排查 WiFi+USB 并发干扰
 *
 * v1.0.17：调整USB.begin()与touchpad.begin()调用顺序（未解决NOT MOUNTED）
 * v1.0.16：按Freenove官方文档修正USB-OTG模式配置(MODE=0, CDC=0)
 *
 * 核心实现（依据 arduino-esp32 v2.0.14 官方 USBHID.cpp 模式）：
 *   - USBHID 构造函数 → tinyusb_enable_interface(HID) 注册 HID 接口回调
 *   - addDevice() → tinyusb_enable_hid_device() 注册设备描述符
 *   - USBHID::begin() 只创建 semaphore（不调用 USBDevice.begin()）
 *   - USB.begin() → tinyusb_init() → tinyusb_driver_install() → tusb_init()
 *   - 通过 hid.SendReport() 发送触摸报告
 *   - platformio.ini: ARDUINO_USB_MODE=0 + ARDUINO_USB_CDC_ON_BOOT=0
 */

#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>
#include <USB.h>
#include <USBHID.h>

// 禁用 brownout detector + USB PHY 寄存器
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"
#include "soc/usb_wrap_reg.h"
#include "soc/usb_wrap_struct.h"

// ============================================================================
// 常量配置
// ============================================================================
#define FW_VERSION "v1.0.18"

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

    0x09, 0x51,             //     Usage (Contact Identifier)
    0x15, 0x00,             //     Logical Minimum (0)
    0x25, 0x01,             //     Logical Maximum (1)
    0x75, 0x08,             //     Report Size (8)
    0x95, 0x01,             //     Report Count (1)
    0x81, 0x02,             //     Input (Data, Var, Abs)

    0x09, 0x42,             //     Usage (Tip Switch)
    0x15, 0x00,             //     Logical Minimum (0)
    0x25, 0x01,             //     Logical Maximum (1)
    0x75, 0x01,             //     Report Size (1)
    0x95, 0x01,             //     Report Count (1)
    0x81, 0x02,             //     Input (Data, Var, Abs)
    0x75, 0x07,             //     Report Size (7) - padding
    0x95, 0x01,             //     Report Count (1)
    0x81, 0x03,             //     Input (Const, Var, Abs)

    0x05, 0x01,             //     Usage Page (Generic Desktop)
    0x09, 0x30,             //     Usage (X)
    0x15, 0x00,             //     Logical Minimum (0)
    0x26, 0xFF, 0x7F,      //     Logical Maximum (32767)
    0x75, 0x10,             //     Report Size (16)
    0x95, 0x01,             //     Report Count (1)
    0x81, 0x02,             //     Input (Data, Var, Abs)

    0x09, 0x31,             //     Usage (Y)
    0x15, 0x00,             //     Logical Minimum (0)
    0x26, 0xFF, 0x7F,      //     Logical Maximum (32767)
    0x75, 0x10,             //     Report Size (16)
    0x95, 0x01,             //     Report Count (1)
    0x81, 0x02,             //     Input (Data, Var, Abs)

    0xC0,                   //   End Collection (Logical)

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
    uint8_t  tip_switch;
    uint16_t x;
    uint16_t y;
    uint8_t  contact_count;
};

// ============================================================================
// USB HID 触控设备类
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
        return hid.ready();  // tud_hid_n_ready(0)
    }

    uint16_t _onGetDescriptor(uint8_t* buffer) override {
        memcpy(buffer, touch_report_descriptor, sizeof(touch_report_descriptor));
        return sizeof(touch_report_descriptor);
    }

    bool touchDown(uint16_t screenX, uint16_t screenY) {
        if (!hid.ready()) return false;
        _report.contact_id    = 0;
        _report.tip_switch    = 0x01;
        _report.x             = (uint16_t)((uint32_t)screenX * HID_MAX / SCREEN_WIDTH);
        _report.y             = (uint16_t)((uint32_t)screenY * HID_MAX / SCREEN_HEIGHT);
        _report.contact_count = 1;
        return hid.SendReport(HID_REPORT_ID_TOUCH, &_report, sizeof(_report));
    }

    bool touchUp() {
        if (!hid.ready()) return false;
        _report.contact_id    = 0;
        _report.tip_switch    = 0x00;
        _report.x             = 0;
        _report.y             = 0;
        _report.contact_count = 0;
        return hid.SendReport(HID_REPORT_ID_TOUCH, &_report, sizeof(_report));
    }

    bool tap(uint16_t screenX, uint16_t screenY, uint32_t durationMs) {
        if (!touchDown(screenX, screenY)) return false;
        delay(durationMs);
        return touchUp();
    }
};

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
        "\"hid_ready\":%s,\"usb_mounted\":%s}",
        FW_VERSION, (unsigned long)millis(),
        ESP.getFreeHeap(), (unsigned)ESP.getFreePsram(),
        AP_SSID, WiFi.softAPIP().toString().c_str(),
        WiFi.softAPgetStationNum(),
        touchpad.ready() ? "true" : "false",
        ((bool)USB) ? "true" : "false");
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
        "<p>HID ready: %s | USB mounted: %s</p>"
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
        touchpad.ready() ? "YES" : "NO",
        ((bool)USB) ? "YES" : "NO",
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
    Serial.println("  USB-OTG HID DIAGNOSTIC (MODE=0, CDC=0, debug=3)");
    Serial.println("========================================================");
    Serial.println();

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

    // ---- USB HID DIAGNOSTIC ----
    Serial.println();
    Serial.println("---- USB HID Init (v1.0.18 diagnostic) ----");

    // 1. Pre-USB PHY register snapshot
    Serial.println("[USB] Pre-init PHY registers:");
    Serial.printf("[USB]   USB_WRAP_DATE_REG = 0x%08x (expect 0x0200 for S3)\n",
                  REG_READ(USB_WRAP_DATE_REG));

    // 2. Check USB object state before begin
    Serial.printf("[USB] USB operator bool (mounted) = %s (before begin)\n",
                  ((bool)USB ? "true(MOUNTED)" : "false"));

    // 3. touchpad.begin() - creates semaphore (USBHID::begin does NOT call USBDevice.begin)
    Serial.println("[USB] Calling touchpad.begin()...");
    touchpad.begin();
    Serial.println("[USB] touchpad.begin() done");

    // 4. USB.begin() - triggers tinyusb_init() → tinyusb_driver_install() → tusb_init()
    Serial.println("[USB] Calling USB.begin()...");
    bool usbResult = USB.begin();
    Serial.printf("[USB] USB.begin() returned: %s\n", usbResult ? "true" : "false");
    Serial.printf("[USB] USB operator bool (mounted) = %s\n",
                  ((bool)USB ? "true(MOUNTED)" : "false(not mounted)"));

    // 5. Post-USB PHY register snapshot
    Serial.println("[USB] Post-init PHY registers:");
    Serial.printf("[USB]   USB_WRAP_DATE_REG = 0x%08x\n", REG_READ(USB_WRAP_DATE_REG));

    // 6. Register USB event callbacks
    USB.onEvent([](arduino_usb_event_t event, arduino_usb_event_data_t *data) {
        switch (event) {
            case ARDUINO_USB_STARTED_EVENT:
                Serial.println("[USB-EVENT] >>> DEVICE MOUNTED (configured by host) <<<");
                break;
            case ARDUINO_USB_STOPPED_EVENT:
                Serial.println("[USB-EVENT] >>> DEVICE UNMOUNTED <<<");
                break;
            case ARDUINO_USB_SUSPEND_EVENT:
                Serial.println("[USB-EVENT] >>> BUS SUSPENDED <<<");
                break;
            case ARDUINO_USB_RESUME_EVENT:
                Serial.println("[USB-EVENT] >>> BUS RESUMED <<<");
                break;
            default:
                Serial.printf("[USB-EVENT] unknown event=%d\n", (int)event);
                break;
        }
    });
    Serial.println("[USB] Event callbacks registered");

    // 7. Disable TWDT
    disableCore0WDT();
    disableCore1WDT();
    Serial.println("[TWDT] Dual-core Task WDT disabled");

    // 8. Extended USB mount wait (30 seconds) with periodic status
    Serial.println("[USB] Waiting for USB mount (30s max)...");
    int waitCount = 0;
    bool wasMounted = false;
    while (waitCount < 300) {
        delay(100);
        waitCount++;
        bool nowMounted = (bool)USB;
        if (nowMounted && !wasMounted) {
            Serial.printf("[USB] *** MOUNTED at %d.%ds! HID ready=%s ***\n",
                          waitCount / 10, waitCount % 10,
                          touchpad.ready() ? "YES" : "NO");
        }
        wasMounted = nowMounted;

        if (waitCount % 50 == 0) {
            Serial.printf("[USB] t=%d.%ds | USB=%s | HID=%s | Heap=%u\n",
                          waitCount / 10, waitCount % 10,
                          nowMounted ? "MOUNTED" : "not-mounted",
                          touchpad.ready() ? "READY" : "not-ready",
                          ESP.getFreeHeap());
        }
    }

    if ((bool)USB) {
        Serial.println("[USB] *** SUCCESS: USB device MOUNTED! ***");
    } else {
        Serial.println("[USB] *** FAILED: NOT mounted after 30s ***");
        Serial.println("[USB] Diagnostic checklist:");
        Serial.println("[USB]   1. OTG cable/port OK? (try different cable)");
        Serial.println("[USB]   2. ARDUINO_USB_MODE=0 effective? (check build log)");
        Serial.println("[USB]   3. USB PHY init OK? (compare pre/post registers)");
        Serial.println("[USB]   4. Phone USB host mode active?");
        Serial.println("[USB]   5. Power issue? (rst:0x1 in boot 1)");
    }

    Serial.printf("[Status] Heap after init: %u (%.1f KB)\n",
                  ESP.getFreeHeap(), ESP.getFreeHeap() / 1024.0f);

    // ---- HTTP Server ----
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
    Serial.printf("  USB mounted: %s | HID ready: %s\n",
                  ((bool)USB) ? "YES" : "NO",
                  touchpad.ready() ? "YES" : "NO");
    Serial.println("==========================================");
}

// ============================================================================
// loop()
// ============================================================================
void loop() {
    static int counter = 0;
    counter++;

    server.handleClient();

    // Detect USB mount state changes
    static bool lastUsbState = false;
    bool curUsbState = (bool)USB;
    if (curUsbState != lastUsbState) {
        Serial.printf("[USB] State change: %s -> %s (HB #%d)\n",
                      lastUsbState ? "MOUNTED" : "not-mounted",
                      curUsbState ? "MOUNTED" : "not-mounted",
                      counter);
        lastUsbState = curUsbState;
    }

    Serial.printf("[%s] HB #%d | Heap: %u | PSRAM: %u | Clients: %d | USB: %s | HID: %s\n",
                  FW_VERSION, counter,
                  ESP.getFreeHeap(),
                  (unsigned)ESP.getFreePsram(),
                  WiFi.softAPgetStationNum(),
                  curUsbState ? "OK" : "NO",
                  touchpad.ready() ? "OK" : "NO");

    delay(3000);
}
