/**
 * ============================================================================
 * 青云扑克 ESP32-S3 - BLE + USB HID 固件 (v1.0.26)
 * ============================================================================
 *
 * v1.0.26：BLE通信替代WiFi AP
 *   - 移除：WiFi AP + HTTP Server（手机连WiFi后无法上网的问题）
 *   - 新增：BLE GATT Server（Nordic UART Service）
 *   - 保留：USB HID 触摸屏模拟（Digitizer + yield/retry）
 *   - 架构：手机移动数据上网 + BLE发指令 + USB HID注入点击
 *
 * BLE协议（Nordic UART Service）：
 *   Service UUID: 6E400001-B5A3-F393-E0A9-E50E24DAB9E9
 *   RX Char (手机写): 6E400002-B5A3-F393-E0A9-E50E24DAB9E9
 *   TX Char (ESP通知): 6E400003-B5A3-F393-E0A9-E50E24DAB9E9
 *
 * 指令格式：
 *   tap:x,y,duration  → 执行触摸点击 → 回复 ok:tap(x,y,ms) 或 err:xxx
 *   status            → 查询设备状态   → 回复 ok:ver=...,heap=...,...
 *   log               → 获取完整日志   → 回复日志内容
 *
 * 兼容App：Serial Bluetooth Terminal (Kai Morich), Adafruit Bluefruit Connect
 *
 * v1.0.25：修复HID send failure（yield+retry机制）
 * v1.0.24：修复 /tap 端点 JSON+表单双格式
 * v1.0.23：精简版砍Camera
 * v1.0.21~v1.0.16：WiFi AP + USB HID + Camera 迭代
 *
 * 核心实现：
 *   - USBHID 触摸屏模拟（Digitizer HID Report）
 *   - BLE GATT Server（Nordic UART Service）
 *   - platformio.ini: ARDUINO_USB_MODE=0 + ARDUINO_USB_CDC_ON_BOOT=0
 */

#include <Arduino.h>
#include <USB.h>
#include <USBHID.h>
#include <Adafruit_TinyUSB.h>

// BLE 库
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// 禁用 brownout detector
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"

// ============================================================================
// 常量配置
// ============================================================================
#define FW_VERSION "v1.0.28"

// BLE设备名
#define BLE_DEVICE_NAME "QingYun-ESP32"

// Nordic UART Service UUIDs
#define NUS_SERVICE_UUID  "6E400001-B5A3-F393-E0A9-E50E24DAB9E9"
#define RX_CHAR_UUID      "6E400002-B5A3-F393-E0A9-E50E24DAB9E9"  // Write
#define TX_CHAR_UUID      "6E400003-B5A3-F393-E0A9-E50E24DAB9E9"  // Notify

// 屏幕分辨率（一加13T）
#define SCREEN_WIDTH  1080
#define SCREEN_HEIGHT 2344

// HID 坐标范围
#define HID_MAX 32767

// HID Report ID
#define HID_REPORT_ID_TOUCH 0

// ============================================================================
// 日志缓冲区（Serial + BLE log指令可用）
// ============================================================================
static String log_buf = "";
static const size_t LOG_BUF_MAX = 6144;  // 6KB上限
static int log_skip_count = 0;
static bool log_skip_warned = false;

static void qlog(const char* msg) {
    Serial.println(msg);
    if (log_buf.length() < LOG_BUF_MAX) {
        log_buf += msg;
        log_buf += '\n';
    } else if (!log_skip_warned) {
        log_skip_warned = true;
        log_buf += "[LOG BUFFER FULL]\n";
    }
    log_skip_count++;
}

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
        log_buf += "[LOG BUFFER FULL]\n";
    }
    log_skip_count++;
}

// ============================================================================
// HID 报告描述符（触摸屏 Digitizer）
// ============================================================================
// V1.0.28: Touch Screen (0x04) 而非 Touch Pad (0x05)，Android更容易识别为触摸屏
// 去掉 Report ID（report_id=0 不发送ID前缀）
static const uint8_t touch_report_descriptor[] = {
    0x05, 0x0D,             // Usage Page (Digitizers)
    0x09, 0x04,             // Usage (Touch Screen) - 关键：用Touch Screen不是Touch Pad
    0xA1, 0x01,             // Collection (Application)

    // Contact Count Maximum (Android必须)
    0x09, 0x55,             //   Usage (Contact Count Maximum)
    0x25, 0x01,             //   Logical Maximum (1)
    0xB1, 0x02,             //   Feature (Data, Var, Abs) - 1个触点

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
    // V2.9.175: HID诊断追踪
    bool _everMounted = false;
    int _failCount = 0;
    const char* _lastFailReason = "none";

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
        bool r = hid.ready();
        if (r) _everMounted = true;
        return r;
    }

    // V2.9.175: 诊断接口
    bool wasEverMounted() const { return _everMounted; }
    int hidFailCount() const { return _failCount; }
    const char* hidLastFailReason() const { return _lastFailReason; }

    uint16_t _onGetDescriptor(uint8_t* buffer) override {
        memcpy(buffer, touch_report_descriptor, sizeof(touch_report_descriptor));
        return sizeof(touch_report_descriptor);
    }

    bool touchDown(uint16_t screenX, uint16_t screenY) {
        yield();  // let TinyUSB task run
        if (!hid.ready()) {
            _failCount++;
            _lastFailReason = "not_ready";
            return false;
        }
        _report.contact_id    = 0;
        _report.tip_switch    = 0x01;
        _report.x             = (uint16_t)((uint32_t)screenX * HID_MAX / SCREEN_WIDTH);
        _report.y             = (uint16_t)((uint32_t)screenY * HID_MAX / SCREEN_HEIGHT);
        _report.contact_count = 1;
        // retry with short delay
        for (int retry = 0; retry < 5; retry++) {
            if (hid.SendReport(HID_REPORT_ID_TOUCH, &_report, sizeof(_report))) return true;
            delay(10);
            yield();
        }
        _failCount++;
        _lastFailReason = "send_failed";
        return false;
    }

    bool touchUp() {
        yield();
        if (!hid.ready()) {
            _failCount++;
            _lastFailReason = "not_ready";
            return false;
        }
        _report.contact_id    = 0;
        _report.tip_switch    = 0x00;
        _report.x             = 0;
        _report.y             = 0;
        _report.contact_count = 0;
        for (int retry = 0; retry < 5; retry++) {
            if (hid.SendReport(HID_REPORT_ID_TOUCH, &_report, sizeof(_report))) return true;
            delay(10);
            yield();
        }
        _failCount++;
        _lastFailReason = "send_failed";
        return false;
    }

    bool tap(uint16_t screenX, uint16_t screenY, uint32_t durationMs) {
        if (!touchDown(screenX, screenY)) return false;
        delay(durationMs);
        return touchUp();
    }
};

static USBHIDTouchpad touchpad;

// ============================================================================
// BLE GATT Server（Nordic UART Service）
// ============================================================================
static BLECharacteristic* g_pTxChar = nullptr;
static bool g_bleConnected = false;

// BLE命令队列（回调中接收，loop中处理，避免在回调中做耗时操作）
static volatile bool g_hasNewCmd = false;
static String g_pendingCmd = "";

// --- BLE Server Callbacks ---
class MyServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) override {
        g_bleConnected = true;
        qlog("[BLE] Client connected!");
    }

    void onDisconnect(BLEServer* pServer) override {
        g_bleConnected = false;
        qlog("[BLE] Client disconnected - restarting advertising");
        // 重新开始广播
        pServer->startAdvertising();
    }
};

// --- BLE RX Callback（手机→ESP32写入指令） ---
class MyRxCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* pChar) override {
        std::string val = pChar->getValue();
        if (val.length() > 0) {
            String cmd = String(val.c_str());
            cmd.trim();
            qlogf("[BLE] RX: %s", cmd.c_str());
            g_pendingCmd = cmd;
            g_hasNewCmd = true;
        }
    }
};

// --- BLE回复（ESP32→手机通知） ---
static void bleReply(const char* msg) {
    if (g_pTxChar && g_bleConnected) {
        g_pTxChar->setValue(msg);
        g_pTxChar->notify();
        qlogf("[BLE] TX: %s", msg);
    } else {
        qlogf("[BLE] TX skipped (not connected): %s", msg);
    }
}

// --- 处理BLE指令 ---
static void processCommand(const String& cmd) {
    if (cmd.startsWith("tap:")) {
        // 格式: tap:x,y,duration
        String params = cmd.substring(4);
        int c1 = params.indexOf(',');
        int c2 = params.indexOf(',', c1 + 1);

        if (c1 > 0 && c2 > c1) {
            int x = params.substring(0, c1).toInt();
            int y = params.substring(c1 + 1, c2).toInt();
            int dur = params.substring(c2 + 1).toInt();
            if (dur < 10) dur = 50;

            if (x < 0 || x >= SCREEN_WIDTH || y < 0 || y >= SCREEN_HEIGHT) {
                char buf[128];
                snprintf(buf, sizeof(buf),
                         "err:coords_out_of_range(x:0-%d,y:0-%d,got=%d,%d)",
                         SCREEN_WIDTH - 1, SCREEN_HEIGHT - 1, x, y);
                bleReply(buf);
                return;
            }

            bool ok = touchpad.tap(x, y, dur);
            if (ok) {
                char buf[128];
                snprintf(buf, sizeof(buf), "ok:tap(%d,%d,%dms)", x, y, dur);
                bleReply(buf);
            } else {
                bleReply("err:hid_send_failed");
            }
        } else {
            bleReply("err:bad_format,use:tap:x,y,ms");
        }

    } else if (cmd == "status") {
        // V1.0.28: 用TinyUSBDevice.mounted()判断USB是否真正被主机枚举
        bool usbMounted = TinyUSBDevice.mounted();
        bool hidReady = touchpad.ready();
        char buf[480];
        snprintf(buf, sizeof(buf),
            "ok:ver=%s,heap=%u,psram=%u,usb=%s,hid=%s,ever=%s,fails=%d,reason=%s,ble=connected,uptime=%lus,mnt=%d",
            FW_VERSION,
            ESP.getFreeHeap(),
            (unsigned)ESP.getFreePsram(),
            usbMounted ? "ok" : "no",
            hidReady ? "ok" : "no",
            touchpad.wasEverMounted() ? "yes" : "no",
            touchpad.hidFailCount(),
            touchpad.hidLastFailReason(),
            (unsigned long)(millis() / 1000),
            usbMounted ? 1 : 0);
        bleReply(buf);

    } else if (cmd == "log") {
        // 分段发送日志（BLE MTU限制，每段最多128字节）
        if (log_buf.length() == 0) {
            bleReply("ok:log_empty");
        } else {
            // 先发总长度
            char hdr[64];
            snprintf(hdr, sizeof(hdr), "ok:log_len=%d", (int)log_buf.length());
            bleReply(hdr);
            delay(100);

            // 分段发送
            const int CHUNK = 120;
            int totalLen = log_buf.length();
            int sent = 0;
            while (sent < totalLen && g_bleConnected) {
                int end = sent + CHUNK;
                if (end > totalLen) end = totalLen;
                String chunk = log_buf.substring(sent, end);
                bleReply(chunk.c_str());
                sent = end;
                delay(50);  // 给手机端处理时间
            }
            bleReply("[END]");
        }

    } else if (cmd == "ping") {
        bleReply("pong");

    } else {
        bleReply("err:unknown_cmd. cmds: tap:x,y,ms | status | log | ping");
    }
}

// --- BLE初始化 ---
static void initBLE() {
    qlog("---- BLE Init ----");

    BLEDevice::init(BLE_DEVICE_NAME);
    BLEDevice::setMTU(128);  // 协商较大MTU

    BLEServer* pServer = BLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks());

    // Nordic UART Service
    BLEService* pService = pServer->createService(NUS_SERVICE_UUID);

    // RX Characteristic（手机写入→ESP32接收）
    BLECharacteristic* pRxChar = pService->createCharacteristic(
        RX_CHAR_UUID,
        BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR
    );
    pRxChar->setCallbacks(new MyRxCallbacks());

    // TX Characteristic（ESP32通知→手机接收）
    g_pTxChar = pService->createCharacteristic(
        TX_CHAR_UUID,
        BLECharacteristic::PROPERTY_NOTIFY
    );
    g_pTxChar->addDescriptor(new BLE2902());

    pService->start();
    qlog("[BLE] NUS Service started");

    // Advertising
    BLEAdvertising* pAdv = BLEDevice::getAdvertising();
    pAdv->addServiceUUID(NUS_SERVICE_UUID);
    pAdv->setScanResponse(true);
    pAdv->setMinPreferred(0x06);
    pAdv->setMaxPreferred(0x12);
    BLEDevice::startAdvertising();

    qlogf("[BLE] Advertising started as '%s'", BLE_DEVICE_NAME);
    qlog("[BLE] Waiting for phone to connect via BLE...");
}

// ============================================================================
// setup()
// ============================================================================
void setup() {
    WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);

    Serial.begin(115200);
    delay(2000);

    qlog("");
    qlog("========================================================");
    qlog("  QingYun ESP32-S3 BLE+HID Firmware " FW_VERSION);
    qlog("  BLE (Nordic UART) + USB HID Touch (No WiFi, No Camera)");
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

    // ---- USB HID ----
    qlog("---- USB HID Init ----");

    // V1.0.28: 设置USB设备描述符 - VID/PID/Manufacturer/Product/Serial
    // 用Espressif官方VID(0x303A) + 触摸屏设备PID
    USB.VID(0x303A);
    USB.PID(0x8266);
    USB.manufacturerName("QingYun");
    USB.productName("QingYun Touch Screen");
    USB.serialNumber("QY000001");
    USB.productVersion(0x0100);  // v1.0

    qlogf("[USB] USB vendorID=0x303A productID=0x8266 (Touch Screen)");

    qlog("[USB] Calling touchpad.begin()...");
    touchpad.begin();
    qlog("[USB] touchpad.begin() done");

    qlog("[USB] Calling USB.begin()...");
    bool usbResult = USB.begin();
    qlogf("[USB] USB.begin() returned: %s", usbResult ? "true" : "false");
    qlogf("[USB] USB.ready()=%s | TinyUSBDevice.mounted()=%s",
          USB.ready() ? "true" : "false",
          TinyUSBDevice.mounted() ? "true" : "false");

    disableCore0WDT();
    disableCore1WDT();
    qlog("[TWDT] Dual-core Task WDT disabled");

    // V1.0.28: USB mount wait - 使用TinyUSBDevice.mounted()而非(bool)USB，更准确
    qlog("[USB] Waiting for USB mount (30s max)...");
    int waitCount = 0;
    bool wasMounted = false;
    while (waitCount < 300) {
        delay(100);
        waitCount++;
        bool nowMounted = TinyUSBDevice.mounted() && touchpad.ready();
        if (nowMounted && !wasMounted) {
            qlogf("[USB] *** MOUNTED at %d.%ds! HID ready=YES ***",
                  waitCount / 10, waitCount % 10);
        }
        wasMounted = nowMounted;

        if (waitCount % 50 == 0) {
            qlogf("[USB] t=%d.%ds | mounted=%s | USB.ready=%s | HID.ready=%s | Heap=%u",
                  waitCount / 10, waitCount % 10,
                  TinyUSBDevice.mounted() ? "YES" : "no",
                  USB.ready() ? "YES" : "no",
                  touchpad.ready() ? "READY" : "not-ready",
                  ESP.getFreeHeap());
        }
    }

    if (TinyUSBDevice.mounted() && touchpad.ready()) {
        qlog("[USB] *** SUCCESS: USB Touch Screen MOUNTED! ***");
    } else {
        qlog("[USB] *** WARNING: Host not detected after 30s ***");
        qlog("[USB] If USB not connected yet, plug OTG after boot");
    }

    qlogf("[Status] Heap after USB init: %u (%.1f KB)",
          ESP.getFreeHeap(), ESP.getFreeHeap() / 1024.0f);

    // ---- BLE Init ----
    initBLE();

    qlog("");
    qlog("==========================================");
    qlog("  Setup COMPLETE. Entering loop...");
    qlogf("  BLE: '%s' | NUS Service active", BLE_DEVICE_NAME);
    qlog("  >>> Commands: tap:x,y,ms | status | log | ping <<<");
    qlogf("  USB: %s | HID: %s",
          ((bool)USB) ? "MOUNTED" : "NOT MOUNTED",
          touchpad.ready() ? "READY" : "NOT READY");
    qlog("==========================================");
}

// ============================================================================
// loop()
// ============================================================================
void loop() {
    // 处理BLE收到的命令
    if (g_hasNewCmd) {
        g_hasNewCmd = false;
        String cmd = g_pendingCmd;
        g_pendingCmd = "";
        processCommand(cmd);
    }

    // USB状态变化监控
    static int hbCounter = 0;
    hbCounter++;

    static bool lastUsbState = false;
    bool curUsbState = (bool)USB;
    if (curUsbState != lastUsbState) {
        qlogf("[USB] State change: %s -> %s (HB #%d)",
              lastUsbState ? "MOUNTED" : "not-mounted",
              curUsbState ? "MOUNTED" : "not-mounted",
              hbCounter);
        lastUsbState = curUsbState;
    }

    // 心跳日志（每10秒一次，3s × ~3 = ~9s）
    if (hbCounter % 3 == 0) {
        qlogf("[%s] HB #%d | Heap: %u | USB: %s | HID: %s | BLE: %s",
              FW_VERSION, hbCounter,
              ESP.getFreeHeap(),
              curUsbState ? "OK" : "NO",
              touchpad.ready() ? "OK" : "NO",
              g_bleConnected ? "CONN" : "DISC");
    }

    delay(3000);
}
