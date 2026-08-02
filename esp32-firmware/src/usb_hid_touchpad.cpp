/**
 * ============================================================================
 * 青云扑克 ESP32-S3-CAM - USB HID 触摸屏模块 实现
 * ============================================================================
 */

#include "usb_hid_touchpad.h"

// ============================================================================
// HID 报告描述符
// ============================================================================
// 符合 USB HID Digitizer 规范，Android 可识别为触摸屏设备
//
// 报告结构 (7 bytes):
//   [0]     Contact ID     - 触点标识 (0)
//   [1]     Tip Switch     - Bit0: 触摸状态 (1=按下, 0=抬起)
//   [2-3]   X Coordinate  - 16-bit little-endian (0-32767)
//   [4-5]   Y Coordinate  - 16-bit little-endian (0-32767)
//   [6]     Contact Count  - 活跃触点数 (0 或 1)
//
// 描述符解析验证:
//   Contact ID:   1 byte  (Usage 0x51, Report Size 8, Count 1)
//   Tip Switch:   1 bit   (Usage 0x42, Report Size 1, Count 1)
//   Padding:      7 bits  (Report Size 7, Count 1, Const)
//   X:            16 bits (Usage 0x30, Report Size 16, Count 1)
//   Y:            16 bits (Usage 0x31, Report Size 16, Count 1)
//   Contact Count: 8 bits (Usage 0x54, Report Size 8, Count 1)
//   Total:        8+1+7+16+16+8 = 56 bits = 7 bytes ✓
// ============================================================================
static const uint8_t _hid_touchpad_descriptor[] = {
    // ---- 应用集合: Digitizer Touch Pad ----
    0x05, 0x0D,             // Usage Page (Digitizers)
    0x09, 0x05,             // Usage (Touch Pad)
    0xA1, 0x01,             // Collection (Application)

    // ---- 逻辑集合: Finger (触点数据) ----
    0x09, 0x22,             //   Usage (Finger)
    0xA1, 0x00,             //   Collection (Logical)

    // Contact Identifier (1 byte)
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

    // X Coordinate (16 bits, 0-32767)
    0x05, 0x01,             //     Usage Page (Generic Desktop)
    0x09, 0x30,             //     Usage (X)
    0x15, 0x00,             //     Logical Minimum (0)
    0x26, 0xFF, 0x7F,      //     Logical Maximum (32767) [2-byte value: 0x7FFF LE]
    0x75, 0x10,             //     Report Size (16)
    0x95, 0x01,             //     Report Count (1)
    0x81, 0x02,             //     Input (Data, Var, Abs)

    // Y Coordinate (16 bits, 0-32767)
    0x09, 0x31,             //     Usage (Y)
    0x15, 0x00,             //     Logical Minimum (0)
    0x26, 0xFF, 0x7F,      //     Logical Maximum (32767) [2-byte value: 0x7FFF LE]
    0x75, 0x10,             //     Report Size (16)
    0x95, 0x01,             //     Report Count (1)
    0x81, 0x02,             //     Input (Data, Var, Abs)

    0xC0,                   //   End Collection (Logical - Finger)

    // Contact Count (1 byte) - 在应用集合内、Finger 集合外
    0x05, 0x0D,             //   Usage Page (Digitizers)
    0x09, 0x54,             //   Usage (Contact Count)
    0x15, 0x00,             //   Logical Minimum (0)
    0x25, 0x02,             //   Logical Maximum (2)
    0x75, 0x08,             //   Report Size (8)
    0x95, 0x01,             //   Report Count (1)
    0x81, 0x02,             //   Input (Data, Var, Abs)

    0xC0                    // End Collection (Application - Touch Pad)
};

// ============================================================================
// 构造函数
// ============================================================================
USBHIDTouchpad::USBHIDTouchpad()
    : _usbHid(_hid_touchpad_descriptor, sizeof(_hid_touchpad_descriptor),
              HID_ITF_PROTOCOL_NONE, 2, false),
      _isTouching(false),
      _everMounted(false),
      _failCount(0),
      _lastFailReason("none")
{
}

// ============================================================================
// 初始化
// ============================================================================
bool USBHIDTouchpad::begin()
{
    // 设置 USB 设备描述符
    TinyUSBDevice.setManufacturerDescriptor("QingYun");
    TinyUSBDevice.setProductDescriptor("QingYun Touch Screen");
    TinyUSBDevice.setID(0x303A, 0x8266);  // Espressif VID, custom PID

    // 初始化 USB HID
    _usbHid.begin();

    Serial.println("[HID] USB HID Touchpad initialized");
    Serial.printf("[HID] Report descriptor size: %d bytes\n", sizeof(_hid_touchpad_descriptor));
    Serial.printf("[HID] Report data size: %d bytes\n", TOUCH_REPORT_SIZE);
    return true;
}

// ============================================================================
// 设备挂载状态
// ============================================================================
bool USBHIDTouchpad::isMounted()
{
    return TinyUSBDevice.mounted();
}

bool USBHIDTouchpad::waitForMount(uint32_t timeoutMs)
{
    uint32_t startTime = millis();
    while (!TinyUSBDevice.mounted()) {
        if (timeoutMs > 0 && (millis() - startTime) > timeoutMs) {
            Serial.println("[HID] Wait for mount timeout");
            return false;
        }
        delay(10);
    }
    Serial.println("[HID] Device mounted");
    return true;
}

// ============================================================================
// 坐标转换
// ============================================================================
uint16_t USBHIDTouchpad::screenToHidX(uint16_t screenX)
{
    // 屏幕坐标 0-1079 → HID 坐标 0-32767
    // 公式: hid_x = screen_x * 32767 / 1079
    // 使用 uint32_t 防止乘法溢出
    uint32_t result = (uint32_t)screenX * HID_LOGICAL_MAX / (SCREEN_WIDTH - 1);
    return _clampHidCoord(result);
}

uint16_t USBHIDTouchpad::screenToHidY(uint16_t screenY)
{
    // 屏幕坐标 0-2343 → HID 坐标 0-32767
    // 公式: hid_y = screen_y * 32767 / 2343
    uint32_t result = (uint32_t)screenY * HID_LOGICAL_MAX / (SCREEN_HEIGHT - 1);
    return _clampHidCoord(result);
}

uint16_t USBHIDTouchpad::_clampHidCoord(uint32_t value)
{
    if (value > HID_LOGICAL_MAX) {
        return HID_LOGICAL_MAX;
    }
    return (uint16_t)value;
}

// ============================================================================
// 高级触摸操作
// ============================================================================
bool USBHIDTouchpad::tap(uint16_t screenX, uint16_t screenY, uint32_t durationMs)
{
    if (!isMounted()) {
        Serial.println("[HID] Error: device not mounted, cannot tap");
        return false;
    }

    // 如果当前正在触摸中，先抬起
    if (_isTouching) {
        sendTouchUp();
        delay(10);
    }

    uint16_t hidX = screenToHidX(screenX);
    uint16_t hidY = screenToHidY(screenY);

    Serial.printf("[HID] Tap: screen(%d,%d) hid(%d,%d) dur=%lums\n",
                  screenX, screenY, hidX, hidY, durationMs);

    // 触摸按下
    if (!sendTouchDown(hidX, hidY)) {
        return false;
    }

    // 保持触摸
    delay(durationMs);

    // 触摸抬起
    return sendTouchUp();
}

bool USBHIDTouchpad::swipe(uint16_t screenX1, uint16_t screenY1,
                            uint16_t screenX2, uint16_t screenY2,
                            uint32_t durationMs)
{
    if (!isMounted()) {
        Serial.println("[HID] Error: device not mounted, cannot swipe");
        return false;
    }

    // 如果当前正在触摸中，先抬起
    if (_isTouching) {
        sendTouchUp();
        delay(10);
    }

    uint16_t hidX1 = screenToHidX(screenX1);
    uint16_t hidY1 = screenToHidY(screenY1);
    uint16_t hidX2 = screenToHidX(screenX2);
    uint16_t hidY2 = screenToHidY(screenY2);

    Serial.printf("[HID] Swipe: (%d,%d)->(%d,%d) hid(%d,%d)->(%d,%d) dur=%lums\n",
                  screenX1, screenY1, screenX2, screenY2,
                  hidX1, hidY1, hidX2, hidY2, durationMs);

    // 计算步数 (每步约10ms)
    uint16_t steps = (durationMs / DEFAULT_SWIPE_STEP_MS);
    if (steps < 2) steps = 2;
    if (steps > 200) steps = 200;  // 上限防止过度分割

    // 触摸按下
    if (!sendTouchDown(hidX1, hidY1)) {
        return false;
    }

    // 滑动过程
    for (uint16_t i = 1; i <= steps; i++) {
        // 线性插值计算中间坐标
        uint32_t hidX = hidX1 + ((uint32_t)(hidX2 - hidX1) * i / steps);
        uint32_t hidY = hidY1 + ((uint32_t)(hidY2 - hidY1) * i / steps);

        if (!sendTouchMove(_clampHidCoord(hidX), _clampHidCoord(hidY))) {
            // 移动失败，尝试抬起
            sendTouchUp();
            return false;
        }

        delay(DEFAULT_SWIPE_STEP_MS);
    }

    // 触摸抬起
    return sendTouchUp();
}

// ============================================================================
// 低级报告发送
// ============================================================================
bool USBHIDTouchpad::sendTouchDown(uint16_t hidX, uint16_t hidY)
{
    touch_report_t report = {0};
    report.contact_id    = 0;
    report.tip_switch    = 0x01;  // Bit 0 = 1 (touching)
    report.x             = hidX;
    report.y             = hidY;
    report.contact_count = 1;

    bool ok = _sendReport(&report);
    if (ok) {
        _isTouching = true;
    }
    return ok;
}

bool USBHIDTouchpad::sendTouchMove(uint16_t hidX, uint16_t hidY)
{
    if (!_isTouching) {
        Serial.println("[HID] Warning: sendTouchMove called without prior touchDown");
        return false;
    }

    touch_report_t report = {0};
    report.contact_id    = 0;
    report.tip_switch    = 0x01;  // Bit 0 = 1 (still touching)
    report.x             = hidX;
    report.y             = hidY;
    report.contact_count = 1;

    return _sendReport(&report);
}

bool USBHIDTouchpad::sendTouchUp()
{
    touch_report_t report = {0};
    report.contact_id    = 0;
    report.tip_switch    = 0x00;  // Bit 0 = 0 (released)
    report.x             = 0;
    report.y             = 0;
    report.contact_count = 0;

    bool ok = _sendReport(&report);
    if (ok) {
        _isTouching = false;
    }
    return ok;
}

bool USBHIDTouchpad::_sendReport(const touch_report_t* report)
{
    if (!isMounted()) {
        _failCount++;
        _lastFailReason = "not_mounted";
        return false;
    }
    _everMounted = true;

    // 检查 HID 端点是否就绪，等待最长 300ms
    if (!_usbHid.ready()) {
        uint32_t start = millis();
        while (!_usbHid.ready()) {
            if (millis() - start > 300) {
                Serial.println("[HID] Error: endpoint not ready after 300ms");
                _failCount++;
                _lastFailReason = "hid_not_ready";
                return false;
            }
            delay(1);
        }
    }

    // 发送报告 (report_id = 0，因为描述符中未定义 REPORT_ID)
    bool result = _usbHid.sendReport(0, report, TOUCH_REPORT_SIZE);
    if (!result) {
        Serial.println("[HID] Error: sendReport failed");
        _failCount++;
        _lastFailReason = "send_failed";
    }
    return result;
}
