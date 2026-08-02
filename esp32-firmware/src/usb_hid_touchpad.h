/**
 * ============================================================================
 * 青云扑克 ESP32-S3-CAM - USB HID 触摸屏模块
 * ============================================================================
 * 
 * 功能：使用 TinyUSB 实现 USB HID Digitizer (Touch Pad) 设备
 * - 绝对坐标模式，Logical Maximum = 32767
 * - 单点触摸：Contact ID + Tip Switch + X + Y + Contact Count
 * - 坐标映射：手机屏幕 1080x2344 → HID 坐标 0-32767
 * - 完整触摸序列：DOWN → MOVE → UP
 * 
 * HID 报告格式 (7 bytes):
 *   Byte 0: Contact ID (uint8_t) - 触点标识，固定为 0
 *   Byte 1: Tip Switch (bit 0) + Padding (bits 1-7)
 *   Bytes 2-3: X 坐标 (uint16_t, little-endian, 0-32767)
 *   Bytes 4-5: Y 坐标 (uint16_t, little-endian, 0-32767)
 *   Byte 6: Contact Count (uint8_t) - 活跃触点数 (0 或 1)
 * 
 * 参考：
 * - USB HID Usage Tables for Digitizers (HUTRR40)
 * - Android Input Device Configuration
 * ============================================================================
 */

#ifndef USB_HID_TOUCHPAD_H
#define USB_HID_TOUCHPAD_H

#include <Arduino.h>
#include <Adafruit_TinyUSB.h>

// ============================================================================
// 常量定义
// ============================================================================

// 手机屏幕分辨率
constexpr uint16_t SCREEN_WIDTH  = 1080;
constexpr uint16_t SCREEN_HEIGHT = 2344;

// HID 逻辑最大值 (0x7FFF)
constexpr uint16_t HID_LOGICAL_MAX = 32767;

// 触摸报告大小 (bytes)
constexpr size_t TOUCH_REPORT_SIZE = 7;

// 默认触摸持续时间 (ms)
constexpr uint32_t DEFAULT_TAP_DURATION_MS = 50;

// 默认滑动步进间隔 (ms)
constexpr uint32_t DEFAULT_SWIPE_STEP_MS = 10;

// ============================================================================
// 触摸报告结构体
// ============================================================================
// 注意：必须与 HID 报告描述符严格对应
// 使用 __attribute__((packed)) 确保无内存对齐填充
typedef struct __attribute__((packed)) {
    uint8_t  contact_id;    // 触点标识 (固定 0)
    uint8_t  tip_switch;    // Bit 0: Tip Switch (1=触摸中, 0=抬起)
                             // Bits 1-7: 填充 (必须为 0)
    uint16_t x;             // X 坐标 (0-32767, little-endian)
    uint16_t y;             // Y 坐标 (0-32767, little-endian)
    uint8_t  contact_count; // 活跃触点数 (0 或 1)
} touch_report_t;

// ============================================================================
// USBHIDTouchpad 类
// ============================================================================
class USBHIDTouchpad {
public:
    USBHIDTouchpad();
    
    /**
     * 初始化 USB HID 触摸屏设备
     * @return true 初始化成功
     */
    bool begin();
    
    /**
     * 检查 USB HID 设备是否已挂载
     * @return true 已挂载
     */
    bool isMounted();
    
    /**
     * 等待 USB HID 设备挂载
     * @param timeoutMs 超时时间(ms)，0=无限等待
     * @return true 设备已挂载
     */
    bool waitForMount(uint32_t timeoutMs = 0);
    
    // ---- 坐标转换 ----
    
    /**
     * 屏幕坐标转 HID X 坐标
     * @param screenX 屏幕X坐标 (0-1079)
     * @return HID X坐标 (0-32767)
     */
    static uint16_t screenToHidX(uint16_t screenX);
    
    /**
     * 屏幕坐标转 HID Y 坐标
     * @param screenY 屏幕Y坐标 (0-2343)
     * @return HID Y坐标 (0-32767)
     */
    static uint16_t screenToHidY(uint16_t screenY);
    
    // ---- 高级触摸操作 ----
    
    /**
     * 执行点击操作
     * @param screenX 屏幕X坐标 (0-1079)
     * @param screenY 屏幕Y坐标 (0-2343)
     * @param durationMs 触摸持续时间(ms)
     * @return true 操作成功
     */
    bool tap(uint16_t screenX, uint16_t screenY, uint32_t durationMs = DEFAULT_TAP_DURATION_MS);
    
    /**
     * 执行滑动操作
     * @param screenX1 起始X坐标
     * @param screenY1 起始Y坐标
     * @param screenX2 终止X坐标
     * @param screenY2 终止Y坐标
     * @param durationMs 滑动总时间(ms)
     * @return true 操作成功
     */
    bool swipe(uint16_t screenX1, uint16_t screenY1,
               uint16_t screenX2, uint16_t screenY2,
               uint32_t durationMs = 300);
    
    // ---- 低级报告发送 ----
    
    /**
     * 发送触摸按下报告
     * @param hidX HID X坐标 (0-32767)
     * @param hidY HID Y坐标 (0-32767)
     * @return true 发送成功
     */
    bool sendTouchDown(uint16_t hidX, uint16_t hidY);
    
    /**
     * 发送触摸移动报告
     * @param hidX HID X坐标 (0-32767)
     * @param hidY HID Y坐标 (0-32767)
     * @return true 发送成功
     */
    bool sendTouchMove(uint16_t hidX, uint16_t hidY);
    
    /**
     * 发送触摸抬起报告
     * @return true 发送成功
     */
    bool sendTouchUp();

private:
    Adafruit_USBD_HID _usbHid;   // TinyUSB HID 实例
    bool _isTouching;             // 当前是否处于触摸状态
    bool _everMounted;            // 是否曾经挂载成功过
    int _failCount;               // 发送失败计数
    const char* _lastFailReason;  // 最近一次失败原因
    
    /**
     * 发送原始触摸报告
     * @param report 报告数据指针
     * @return true 发送成功
     */
    bool _sendReport(const touch_report_t* report);
    
    /**
     * 限制 HID 坐标在合法范围内
     * @param value 原始值
     * @return 限制后的值 (0-32767)
     */
    static uint16_t _clampHidCoord(uint32_t value);

public:
    // V2.9.175: 诊断接口——给status命令用
    bool wasEverMounted() const { return _everMounted; }
    int hidFailCount() const { return _failCount; }
    const char* hidLastFailReason() const { return _lastFailReason; }
};

#endif // USB_HID_TOUCHPAD_H
