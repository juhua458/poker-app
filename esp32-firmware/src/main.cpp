/**
 * ============================================================================
 * 青云扑克 ESP32-S3-CAM - USB HID 触控验证固件 (v1.0.20)
 * ============================================================================
 *
 * v1.0.20：WiFi 日志功能（单线OTG方案）
 *   - 新增日志缓冲区，所有日志同时写入WiFi缓冲区
 *   - 新增 /logs HTTP端点，手机浏览器实时查看完整日志
 *   - 不再需要串口线，只需OTG线连接OTG口即可
 *   - 保留v1.0.19全部USB诊断能力
 *
 * v1.0.19：修复 v1.0.18 编译错误
 *   - 移除 USB.onEvent() lambda（签名与 arduino-esp32 v2.0.8 不兼容）
 *   - 移除 USB_WRAP_DATE_REG 寄存器读取（头文件不可用）
 * v1.0.18：全链路诊断（编译失败：USB.onEvent lambda签名错误）
 * v1.0.17：调整USB.begin()与touchpad.begin()调用顺序
 * v1.0.16：按Freenove官方文档修正USB-OTG模式配置(MODE=0, CDC=0)
 *
 * 核心实现（依据 arduino-esp32 官方 USBHID.cpp 模式）：
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

// 禁用 brownout detector
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"

// ============================================================================
// 常量配置
// ============================================================================
#define FW_VERSION "v1.0.20"

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
// WiFi 日志缓冲区（v1.0.20 新增）
// ============================================================================
static String log_buf = "";
static const size_t LOG_BUF_MAX = 6144;  // 6KB上限
static int log_skip_count = 0;
static bool log_skip_warned = false;

// 写日志到串口 + WiFi缓冲区
static void qlog(const char* msg) {
    Serial.println(msg);
    if (log_buf.length() < LOG_BUF_MAX) {
        log_buf += msg;
        log_buf += '\n';
    } else if (!log_skip_warned) {
        log_skip_warned = true;
        log_buf += "[LOG BUFFER FULL - further lines skipped]\n";
    }
    log_skip_count++;
}

// printf版本
static void qlogf(const char* fmt, ...) {
    char buf[256];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    Serial.println(buf);
    if (log_buf.length() < LOG_BUF_MAX) {
        log_buf += buf;
        log_buf += '\n';
    } else if (!log_skip_warned) {
        log_skip_warned = true;
        log_buf += "[LOG BUFFER FULL - further lines skipped]\n";
    }
    log_skip_count++;
}

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
    0x15, 0x00,             //     Logical Minimum (0)
    0x25, 0x01,             //     Logical Maximum (1)
    0x75, 0x08,             //     Report Size (8)
    0x95, 0x01,             //     Report Count (1)
    0x81, 0x02,             //     Input (Data, Var, Abs)

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

// 日志页面 - 手机浏览器查看完整启动日志（v1.0.20 新增）
void handleLogs() {
    String html = F("<!DOCTYPE html><html><head>"
        "<meta charset='utf-8'>"
        "<meta name='viewport' content='width=device-width,initial-scale=1'>"
        "<title>QingYun Logs</title>"
        "<style>"
        "body{background:#1a1a2e;color:#eee;font-family:monospace;margin:8px;font-size:13px}"
        "h3{color:#e94560;margin:4px 0}"
        ".tag{display:inline-block;padding:2px 8px;border-radius:4px;font-size:12px;margin:2px}"
        ".ok{background:#0a3d0a;color:#4caf50}"
        ".fail{background:#3d0a0a;color:#f44336}"
        ".warn{background:#3d3d0a;color:#ffeb3b}"
        "pre{background:#0f0f23;padding:10px;border-radius:6px;overflow-x:auto;"
        "white-space:pre-wrap;word-break:break-all;font-size:11px;line-height:1.5;"
        "border:1px solid #333;max-height:60vh;overflow-y:auto}"
        "button{background:#e94560;color:#fff;border:none;padding:8px 16px;"
        "border-radius:4px;cursor:pointer;margin:6px 4px;font-size:13px}"
        "button:active{background:#c73e54}"
        ".status{margin:8px 0;padding:8px;background:#16213e;border-radius:4px}"
        "</style></head><body>"
        "<h3>QingYun ESP32-S3-CAM " FW_VERSION "</h3>"
        "<div class='status'>");

    // 实时状态
    bool usbOk = (bool)USB;
    bool hidOk = touchpad.ready();
    html += F("<span class='tag ");
    html += usbOk ? "ok'>USB: MOUNTED" : "fail'>USB: NOT MOUNTED";
    html += F("</span>");
    html += F("<span class='tag ");
    html += hidOk ? "ok'>HID: READY" : "fail'>HID: NOT READY";
    html += F("</span>");
    html += F("<br>Heap: ");
    html += String(ESP.getFreeHeap());
    html += F(" | PSRAM: ");
    html += String((unsigned)ESP.getFreePsram());
    html += F(" | Uptime: ");
    html += String((unsigned long)(millis() / 1000));
    html += F("s | Clients: ");
    html += String(WiFi.softAPgetStationNum());
    html += F("</div>");

    // 操作按钮
    html += F("<button onclick=\"location.reload()\">&#x1f504; 刷新日志</button>"
        "<button onclick=\"location.href='/status'\">JSON API</button>"
        "<button onclick=\"location.href='/'\">首页</button>");

    // 缓冲区溢出提示
    if (log_skip_count > 0) {
        html += F("<div class='tag warn'>Note: ");
        html += String(log_skip_count);
        html += F(" lines logged, buffer limited to 6KB. Early boot may be truncated.</div>");
    }

    // 日志内容
    html += F("<pre>");
    html += log_buf;
    html += F("</pre>");

    // 底部诊断建议
    if (!usbOk) {
        html += F("<div class='status' style='border-left:3px solid #f44336'>"
            "<b style='color:#f44336'>USB NOT MOUNTED - 诊断建议:</b><br>"
            "1. 确认OTG线插在板子的<b>USB-OTG口</b>（直连GPIO19/20），不是UART口<br>"
            "2. 确认手机OTG功能已开启（一加: 设置→其他设置→OTG连接）<br>"
            "3. 换一根OTG线试试<br>"
            "4. 确认OTG线支持数据传输（不是纯充电线）"
            "</div>");
    } else {
        html += F("<div class='status' style='border-left:3px solid #4caf50'>"
            "<b style='color:#4caf50'>USB MOUNTED! HID正常!</b><br>"
            "可以用 /tap 接口测试触控了"
            "</div>");
    }

    html += F("</body></html>");
    server.send(200, "text/html", html);
}

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
        "<html><head><meta charset='utf-8'><title>QingYun ESP32</title>"
        "<meta name='viewport' content='width=device-width,initial-scale=1'>"
        "<style>body{font-family:monospace;background:#1a1a2e;color:#eee;padding:12px}"
        "a{color:#e94560;text-decoration:none;font-size:16px;display:block;margin:8px 0;"
        "padding:12px;background:#16213e;border-radius:6px;text-align:center}</style></head><body>"
        "<h2>QingYun ESP32-S3-CAM %s</h2>"
        "<p>WiFi: %s | IP: %s</p>"
        "<p>Clients: %d | Heap: %u | PSRAM: %u</p>"
        "<p>HID ready: %s | USB mounted: %s</p>"
        "<p>Uptime: %lums</p>"
        "<hr>"
        "<a href='/logs'>📋 查看完整日志 (/logs)</a>"
        "<a href='/status'>📊 状态 JSON (/status)</a>"
        "<h3>Test Tap</h3>"
        "<form method='POST' action='/tap'>"
        "X:<input name='x' value='540' style='width:60px'> "
        "Y:<input name='y' value='1172' style='width:60px'> "
        "Dur:<input name='duration' value='50' style='width:60px'> "
        "<input type='submit' value='Tap'></form>"
        "</body></html>",
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
    server.send(404, "application/json",
        "{\"error\":\"Not found. Try: / | /logs | /status | /tap\"}");
}

// ============================================================================
// setup()
// ============================================================================
void setup() {
    WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);

    Serial.begin(115200);
    delay(2000);

    // 初始化日志
    qlog("");
    qlog("========================================================");
    qlog("  QingYun ESP32-S3-CAM Firmware " FW_VERSION);
    qlog("  WiFi Log Mode - single OTG cable setup");
    qlog("  USB-OTG HID DIAGNOSTIC (MODE=0, CDC=0, debug=3)");
    qlog("========================================================");
    qlog("");

    qlogf("  ESP32-S3 Rev %d | %d MHz | %d cores | SDK %s",
          ESP.getChipRevision(), ESP.getCpuFreqMHz(),
          ESP.getChipCores(), ESP.getSdkVersion());
    qlogf("  Flash: %.1f MB mode=%d speed=%uMHz",
          ESP.getFlashChipSize() / (1024.0f * 1024.0f),
          ESP.getFlashChipMode(), ESP.getFlashChipSpeed());
    qlogf("  PSRAM: %.1f MB (free: %.1f MB)",
          ESP.getPsramSize() / (1024.0f * 1024.0f),
          ESP.getFreePsram() / (1024.0f * 1024.0f));
    qlogf("  Heap: %.1f KB", ESP.getFreeHeap() / 1024.0f);
    qlog("");

    // ---- WiFi AP ----
    qlog("---- WiFi AP Init ----");
    IPAddress apIP, gatewayIP, subnetMask;
    apIP.fromString(AP_IP);
    gatewayIP.fromString(AP_GATEWAY);
    subnetMask.fromString(AP_SUBNET);

    WiFi.mode(WIFI_AP);
    WiFi.softAPConfig(apIP, gatewayIP, subnetMask);

    if (!WiFi.softAP(AP_SSID, AP_PASSWORD)) {
        qlog("[WiFi] ERROR: AP start failed!");
    } else {
        qlogf("[WiFi] AP: SSID=%s IP=%s", AP_SSID, WiFi.softAPIP().toString().c_str());
    }

    // ---- USB HID DIAGNOSTIC ----
    qlog("");
    qlog("---- USB HID Init (v1.0.20 diagnostic) ----");

    // 1. Check USB object state before begin
    qlogf("[USB] USB operator bool (mounted) = %s (before begin)",
          ((bool)USB ? "true(MOUNTED)" : "false"));

    // 2. touchpad.begin() - creates semaphore
    qlog("[USB] Calling touchpad.begin()...");
    touchpad.begin();
    qlog("[USB] touchpad.begin() done");

    // 3. USB.begin() - triggers tinyusb_init → tinyusb_driver_install → tusb_init
    qlog("[USB] Calling USB.begin()...");
    bool usbResult = USB.begin();
    qlogf("[USB] USB.begin() returned: %s", usbResult ? "true" : "false");
    qlogf("[USB] USB operator bool (mounted) = %s",
          ((bool)USB ? "true(MOUNTED)" : "false(not mounted)"));

    // 4. Disable TWDT
    disableCore0WDT();
    disableCore1WDT();
    qlog("[TWDT] Dual-core Task WDT disabled");

    // 5. Extended USB mount wait (30 seconds) with periodic status
    qlog("[USB] Waiting for USB mount (30s max)...");
    int waitCount = 0;
    bool wasMounted = false;
    while (waitCount < 300) {
        delay(100);
        waitCount++;
        bool nowMounted = (bool)USB;
        if (nowMounted && !wasMounted) {
            qlogf("[USB] *** MOUNTED at %d.%ds! HID ready=%s ***",
                  waitCount / 10, waitCount % 10,
                  touchpad.ready() ? "YES" : "NO");
        }
        wasMounted = nowMounted;

        if (waitCount % 50 == 0) {
            qlogf("[USB] t=%d.%ds | USB=%s | HID=%s | Heap=%u",
                  waitCount / 10, waitCount % 10,
                  nowMounted ? "MOUNTED" : "not-mounted",
                  touchpad.ready() ? "READY" : "not-ready",
                  ESP.getFreeHeap());
        }
    }

    if ((bool)USB) {
        qlog("[USB] *** SUCCESS: USB device MOUNTED! ***");
    } else {
        qlog("[USB] *** FAILED: NOT mounted after 30s ***");
        qlog("[USB] Diagnostic checklist:");
        qlog("[USB]   1. OTG cable in correct port? (must be USB-OTG, not UART)");
        qlog("[USB]   2. ARDUINO_USB_MODE=0 effective? (check build flags)");
        qlog("[USB]   3. Phone USB host mode active? (Settings > OTG)");
        qlog("[USB]   4. Power issue? (try powered USB hub)");
        qlog("[USB]   5. Check /logs page for TinyUSB debug output");
    }

    qlogf("[Status] Heap after init: %u (%.1f KB)",
          ESP.getFreeHeap(), ESP.getFreeHeap() / 1024.0f);

    // ---- HTTP Server ----
    qlog("");
    qlog("---- HTTP Server Init ----");
    server.on("/",       HTTP_GET,  handleRoot);
    server.on("/logs",   HTTP_GET,  handleLogs);    // v1.0.20 新增
    server.on("/status", HTTP_GET,  handleStatus);
    server.on("/tap",    HTTP_POST, handleTap);
    server.onNotFound(handleNotFound);
    server.begin();

    qlogf("[HTTP] Server on port %d", HTTP_PORT);
    qlog("[HTTP] Endpoints: GET / | GET /logs | GET /status | POST /tap");
    qlog("");

    qlog("==========================================");
    qlog("  Setup COMPLETE. Entering loop...");
    qlogf("  WiFi: '%s' | http://%s/", AP_SSID, AP_IP);
    qlog("  >>> Open /logs in browser for full log <<<");
    qlogf("  USB mounted: %s | HID ready: %s",
          ((bool)USB) ? "YES" : "NO",
          touchpad.ready() ? "YES" : "NO");
    qlog("==========================================");
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
        qlogf("[USB] State change: %s -> %s (HB #%d)",
              lastUsbState ? "MOUNTED" : "not-mounted",
              curUsbState ? "MOUNTED" : "not-mounted",
              counter);
        lastUsbState = curUsbState;
    }

    qlogf("[%s] HB #%d | Heap: %u | PSRAM: %u | Clients: %d | USB: %s | HID: %s",
          FW_VERSION, counter,
          ESP.getFreeHeap(),
          (unsigned)ESP.getFreePsram(),
          WiFi.softAPgetStationNum(),
          curUsbState ? "OK" : "NO",
          touchpad.ready() ? "OK" : "NO");

    delay(3000);
}
