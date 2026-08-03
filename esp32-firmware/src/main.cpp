/**
 * ============================================================================
 * 青云扑克 ESP32-S3 - BLE + USB HID 固件 (v1.0.34)
 * ============================================================================
 *
 * v1.0.34：点击随机抖动，降低行为检测风险
 *   - tap()坐标±5px随机偏移，时长±20ms随机变化
 *   - 自检走touchDown/touchUp直连，不受抖动影响
 *
 * v1.0.33：增加HID自检功能（selftest命令+USB挂载自动触发）
 *   - USB首次挂载后自动执行tap(540,1172,50)并记录每步结果
 *   - BLE命令selftest手动触发自检
 *   - status回复增加st/st_down/st_up字段，BLE看结果无需盯屏幕
 *   - 自检结果：ok=全部成功 / down_failed=touchDown失败 / up_failed=touchUp失败
 *
 * v1.0.32：修复touchUp contact_id不一致导致触点无法释放
 *   - touchUp时contact_id从0改为1（与touchDown一致），否则Android收到
 *     "触点1按下→触点0释放"，触点1被判为一直按住未松开
 *   - touchUp的X/Y保持上次位置（不再清零），对齐Android释放坐标要求
 *
 * v1.0.31：恢复Contact ID，去除Feature报告（根因修复）
 *   - v1.0.30去掉Contact ID后Android识别为鼠标指针，根因是Contact ID(0x51)
 *     是Android InputReader区分触摸屏vs鼠标的关键Usage
 *   - 恢复v1.0.28的Input Report结构：contact_id + flags + X + Y = 6字节
 *   - 去掉Feature报告(Contact Count Maximum)，避免GET_REPORT STALL
 *   - 保留In Range(0x32)，去掉Touch Valid(0x47)（Android不识别此Usage）
 *   - TouchReport结构体：contact_id→flags→x→y
 *
 * v1.0.30：极简5字节描述符，去掉Feature报告避免GET_REPORT STALL
 *   - 去掉Contact Count Maximum + Contact ID，精简到5字节
 *   - 增加Touch Valid(0x47)位
 *   - 结果：Android识别为鼠标指针而非触摸屏（缺少Contact ID）
 *   - 修正字段顺序：Tip Switch(bit0) + In Range(bit1) + 6bit padding = 1字节（先于Contact ID）
 *   - 增加 In Range (0x32) usage（Android HID多点触控协议必需字段，缺少则丢弃触摸）
 *   - Contact Count Maximum Feature report 显式声明 Report Size=8/Count=1（之前继承错误）
 *   - Contact ID Logical Max 从1改为127（符合HID规范的触点ID范围）
 *   - 增加 Physical Min/Max 和 Unit 声明，提升Android/Windows兼容性
 *   - TouchReport结构体同步调整字段顺序：flags→contact_id→x→y→contact_count
 *   - touchDown时flags=0x03(Tip+InRange)；touchUp时flags=0x00
 *   - touchDown/touchUp增加诊断日志（x/y原始值+HID转换值）
 *
 * v1.0.28：USB HID修复
 *   - HID描述符：Touch Screen(0x04) + Contact Count Maximum(0x55)，Android可识别
 *   - Report ID=0（无前缀），去掉多余ID
 *   - 设置VID(0x303A)/PID(0x8266)/Manufacturer/Product/Serial
 *   - status增加mnt字段：使用(bool)USB检测真正mounted状态（arduino-esp32 operator bool() = _started && mounted）
 *   - 修正API：USB.firmwareVersion() 替代不存在的USB.productVersion()
 *   - BLE MTU协商到512（App端v2.9.179已支持）
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
#define FW_VERSION "v1.0.34"

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

// 点击随机抖动（v1.0.34：降低行为检测风险）
#define JITTER_PX  5     // 坐标±5px随机偏移
#define JITTER_MS  20    // 时长±20ms随机变化

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
// HID 报告描述符（触摸屏 Digitizer - 6字节带Contact ID）v1.0.31
// ============================================================================
// 关键发现：Android InputReader靠Contact ID(0x51)区分触摸屏vs鼠标指针。
// v1.0.30去掉了Contact ID，Android fallback到鼠标模式（屏幕出现指针图标）。
// v1.0.31恢复Contact ID，同时去掉Feature报告避免GET_REPORT STALL。
// 输入报告布局（共6字节，无Report ID前缀）：
//   Byte 0: contact_id  = 0=释放, 1=按下
//   Byte 1: flags       = bit0:Tip Switch | bit1:In Range | bits2-7:0
//   Byte 2-3: X         = 绝对X坐标（0~32767，小端序）
//   Byte 4-5: Y         = 绝对Y坐标（0~32767，小端序）
static const uint8_t touch_report_descriptor[] = {
    0x05, 0x0D,             // Usage Page (Digitizers)
    0x09, 0x04,             // Usage (Touch Screen)
    0xA1, 0x01,             // Collection (Application)

    0x09, 0x22,             //   Usage (Finger)
    0xA1, 0x02,             //   Collection (Logical)

    // Contact Identifier (8 bits) - Android靠此区分触摸屏
    0x09, 0x51,             //     Usage (Contact Identifier)
    0x15, 0x00,             //     Logical Minimum (0)
    0x25, 0x01,             //     Logical Maximum (1)
    0x75, 0x08,             //     Report Size (8)
    0x95, 0x01,             //     Report Count (1)
    0x81, 0x02,             //     Input (Data,Var,Abs)

    // Tip Switch (bit0) + In Range (bit1) + 6 bits padding
    0x09, 0x42,             //     Usage (Tip Switch)
    0x09, 0x32,             //     Usage (In Range)
    0x15, 0x00,             //     Logical Minimum (0)
    0x25, 0x01,             //     Logical Maximum (1)
    0x75, 0x01,             //     Report Size (1 bit)
    0x95, 0x02,             //     Report Count (2) = Tip + InRange
    0x81, 0x02,             //     Input (Data,Var,Abs)
    0x95, 0x06,             //     Report Count (6) padding bits
    0x81, 0x03,             //     Input (Const,Var,Abs)

    // X (16 bits, absolute)
    0x05, 0x01,             //     Usage Page (Generic Desktop)
    0x09, 0x30,             //     Usage (X)
    0x15, 0x00,             //     Logical Minimum (0)
    0x26, 0xFF, 0x7F,      //     Logical Maximum (32767)
    0x75, 0x10,             //     Report Size (16 bits)
    0x95, 0x01,             //     Report Count (1)
    0x81, 0x02,             //     Input (Data,Var,Abs)

    // Y (16 bits, absolute)
    0x09, 0x31,             //     Usage (Y)
    0x26, 0xFF, 0x7F,      //     Logical Maximum (32767)
    0x75, 0x10,             //     Report Size (16 bits)
    0x95, 0x01,             //     Report Count (1)
    0x81, 0x02,             //     Input (Data,Var,Abs)

    0xC0,                   //   End Collection (Logical)
    0xC0                    // End Collection (Application)
};

// 必须与描述符声明的输入报告完全对应（packed保证无padding字节）
// contact_id: 0=释放, 1=按下
// flags: bit0=Tip Switch, bit1=In Range, bits2-7=0
struct __attribute__((packed)) TouchReport {
    uint8_t  contact_id;     // byte 0
    uint8_t  flags;          // byte 1
    uint16_t x;              // byte 2-3
    uint16_t y;              // byte 4-5
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
        yield();
        if (!hid.ready()) {
            _failCount++;
            _lastFailReason = "not_ready";
            return false;
        }
        _report.contact_id = 1;
        _report.flags = 0x03;  // bit0=Tip | bit1=InRange
        _report.x     = (uint16_t)((uint32_t)screenX * HID_MAX / SCREEN_WIDTH);
        _report.y     = (uint16_t)((uint32_t)screenY * HID_MAX / SCREEN_HEIGHT);
        Serial.printf("[HID] down raw(%u,%u) hid(%u,%u) cid=%u flags=0x%02x\n",
                      screenX, screenY, _report.x, _report.y, _report.contact_id, _report.flags);
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
        // contact_id必须和touchDown一致(1)，否则Android认为触点未释放
        _report.contact_id = 1;
        _report.flags = 0x00;
        // X/Y保持上次位置，部分Android版本要求释放坐标与按下一致
        Serial.printf("[HID] up cid=%u\n", _report.contact_id);
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
        // v1.0.34: 坐标±5px + 时长±20ms随机抖动，降低行为检测风险
        int16_t jx = (int16_t)(esp_random() % (JITTER_PX * 2 + 1)) - JITTER_PX;
        int16_t jy = (int16_t)(esp_random() % (JITTER_PX * 2 + 1)) - JITTER_PX;
        int16_t adjX = (int16_t)screenX + jx;
        int16_t adjY = (int16_t)screenY + jy;
        if (adjX < 0) adjX = 0;
        if (adjX >= SCREEN_WIDTH) adjX = SCREEN_WIDTH - 1;
        if (adjY < 0) adjY = 0;
        if (adjY >= SCREEN_HEIGHT) adjY = SCREEN_HEIGHT - 1;

        int32_t jms = (int32_t)(esp_random() % (JITTER_MS * 2 + 1)) - JITTER_MS;
        int32_t adjDur = (int32_t)durationMs + jms;
        if (adjDur < 10) adjDur = 10;

        if (!touchDown((uint16_t)adjX, (uint16_t)adjY)) return false;
        delay(adjDur);
        return touchUp();
    }
};

static USBHIDTouchpad touchpad;

// ============================================================================
// HID 自检状态
// ============================================================================
static bool    g_selftestDone   = false;
static bool    g_selftestDown   = false;
static bool    g_selftestUp     = false;
static int     g_selftestFails  = 0;
static String  g_selftestResult = "waiting";

static void runHidSelfTest() {
    qlog("[SELFTEST] Starting HID self-test...");
    g_selftestDone = false;
    g_selftestDown = false;
    g_selftestUp   = false;
    g_selftestFails = 0;

    if (!touchpad.ready()) {
        g_selftestResult = "HID_not_ready";
        g_selftestDone = true;
        qlog("[SELFTEST] FAIL: HID not ready");
        return;
    }

    // 执行一次完整tap，记录每步结果
    g_selftestDown = touchpad.touchDown(540, 1172);
    if (!g_selftestDown) {
        g_selftestFails = touchpad.hidFailCount();
        g_selftestResult = "down_failed";
        g_selftestDone = true;
        qlogf("[SELFTEST] FAIL: touchDown failed (fails=%d, reason=%s)",
              g_selftestFails, touchpad.hidLastFailReason());
        return;
    }

    delay(50);

    g_selftestUp = touchpad.touchUp();
    g_selftestFails = touchpad.hidFailCount();
    g_selftestDone = true;

    if (g_selftestUp) {
        g_selftestResult = "ok";
        qlog("[SELFTEST] PASS: tap(540,1172,50) sent successfully");
    } else {
        g_selftestResult = "up_failed";
        qlogf("[SELFTEST] FAIL: touchUp failed (fails=%d, reason=%s)",
              g_selftestFails, touchpad.hidLastFailReason());
    }
}

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
        // V1.0.28: (bool)USB 在arduino-esp32中 = _started && tinyusb_device_mounted（真正被主机枚举）
        bool usbMounted = (bool)USB;
        bool hidReady = touchpad.ready();
        char buf[512];
        snprintf(buf, sizeof(buf),
            "ok:ver=%s,heap=%u,psram=%u,usb=%s,hid=%s,ever=%s,fails=%d,reason=%s,ble=connected,uptime=%lus,mnt=%d,st=%s,st_down=%s,st_up=%s",
            FW_VERSION,
            ESP.getFreeHeap(),
            (unsigned)ESP.getFreePsram(),
            usbMounted ? "ok" : "no",
            hidReady ? "ok" : "no",
            touchpad.wasEverMounted() ? "yes" : "no",
            touchpad.hidFailCount(),
            touchpad.hidLastFailReason(),
            (unsigned long)(millis() / 1000),
            usbMounted ? 1 : 0,
            g_selftestDone ? g_selftestResult.c_str() : "waiting",
            g_selftestDown ? "ok" : "no",
            g_selftestUp ? "ok" : "no");
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

    } else if (cmd == "selftest") {
        runHidSelfTest();
        char stBuf[256];
        snprintf(stBuf, sizeof(stBuf),
            "ok:selftest=%s,down=%s,up=%s,fails=%d",
            g_selftestResult.c_str(),
            g_selftestDown ? "ok" : "no",
            g_selftestUp ? "ok" : "no",
            g_selftestFails);
        bleReply(stBuf);

    } else {
        bleReply("err:unknown_cmd. cmds: tap:x,y,ms | status | log | selftest | ping");
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
    USB.firmwareVersion(0x0100);  // v1.0

    qlogf("[USB] USB vendorID=0x%04X productID=0x%04X (Touch Screen)", USB.VID(), USB.PID());

    qlog("[USB] Calling touchpad.begin()...");
    touchpad.begin();
    qlog("[USB] touchpad.begin() done");

    qlog("[USB] Calling USB.begin()...");
    bool usbResult = USB.begin();
    qlogf("[USB] USB.begin() returned: %s", usbResult ? "true" : "false");
    qlogf("[USB] (bool)USB=%s | HID.ready=%s",
          ((bool)USB) ? "true" : "false",
          touchpad.ready() ? "true" : "false");

    disableCore0WDT();
    disableCore1WDT();
    qlog("[TWDT] Dual-core Task WDT disabled");

    // V1.0.28: USB mount wait - (bool)USB = _started && tinyusb_device_mounted（真正被主机枚举）
    qlog("[USB] Waiting for USB mount (30s max)...");
    int waitCount = 0;
    bool wasMounted = false;
    while (waitCount < 300) {
        delay(100);
        waitCount++;
        bool nowMounted = (bool)USB && touchpad.ready();
        if (nowMounted && !wasMounted) {
            qlogf("[USB] *** MOUNTED at %d.%ds! HID ready=YES ***",
                  waitCount / 10, waitCount % 10);
        }
        wasMounted = nowMounted;

        if (waitCount % 50 == 0) {
            qlogf("[USB] t=%d.%ds | mounted=%s | HID.ready=%s | Heap=%u",
                  waitCount / 10, waitCount % 10,
                  ((bool)USB) ? "YES" : "no",
                  touchpad.ready() ? "READY" : "not-ready",
                  ESP.getFreeHeap());
        }
    }

    if ((bool)USB && touchpad.ready()) {
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

        // USB刚挂载时自动触发HID自检
        if (curUsbState && !g_selftestDone) {
            runHidSelfTest();
        }
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
