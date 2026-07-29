/**
 * ============================================================================
 * 青云扑克 ESP32-S3-CAM - WiFi AP + HTTP 服务模块
 * ============================================================================
 * 
 * 功能：
 * - 创建 WiFi 热点 (AP 模式)
 *   - SSID: QingYun-ESP32
 *   - 密码: poker12345
 *   - 静态 IP: 192.168.4.1
 * 
 * - HTTP 端点:
 *   - GET  /capture  - 返回 JPEG 摄像头画面
 *   - GET  /status   - 返回设备状态 JSON
 *   - POST /tap      - 执行点击操作
 *   - POST /swipe    - 执行滑动操作
 *   - POST /ota      - OTA 固件更新
 *   - GET  /config   - 获取/设置行为随机化参数
 * 
 * - WiFi 断线自动重连
 * - 看门狗保护
 * ============================================================================
 */

#ifndef WIFI_AP_SERVER_H
#define WIFI_AP_SERVER_H

#include <Arduino.h>
#include <WiFi.h>
#include <WebServer.h>
#include <ArduinoJson.h>
#include "usb_hid_touchpad.h"
#include "camera_driver.h"
#include "ota_updater.h"
#include "behavior_randomizer.h"

// ============================================================================
// WiFi AP 默认配置
// ============================================================================
constexpr const char* DEFAULT_AP_SSID     = "QingYun-ESP32";
constexpr const char* DEFAULT_AP_PASSWORD = "poker12345";
constexpr const char* DEFAULT_AP_IP       = "192.168.4.1";
constexpr const char* DEFAULT_AP_GATEWAY  = "192.168.4.1";
constexpr const char* DEFAULT_AP_SUBNET   = "255.255.255.0";
constexpr int         HTTP_PORT           = 80;

// ============================================================================
// WiFiAPServer 类
// ============================================================================
class WiFiAPServer {
public:
    /**
     * 构造函数
     * @param hid 指向 USB HID 触摸屏实例
     * @param camera 指向摄像头实例
     * @param ota 指向 OTA 更新实例
     * @param randomizer 指向行为随机化实例
     */
    WiFiAPServer(USBHIDTouchpad* hid, CameraDriver* camera,
                 OTAUpdater* ota, BehaviorRandomizer* randomizer);

    /**
     * 初始化 WiFi AP 和 HTTP 服务器
     * @param ssid WiFi 热点名称 (默认 QingYun-ESP32)
     * @param password WiFi 热点密码 (默认 poker12345)
     * @return true 初始化成功
     */
    bool begin(const char* ssid = DEFAULT_AP_SSID,
               const char* password = DEFAULT_AP_PASSWORD);

    /**
     * 处理 HTTP 请求 (在主循环中调用)
     */
    void handleClient();

    /**
     * 关闭服务器
     */
    void end();

    /**
     * 获取 AP IP 地址
     */
    String getAPIP();

    /**
     * 获取连接客户端数量
     */
    int getConnectedClients();

    /**
     * 服务器是否运行中
     */
    bool isRunning() const;

private:
    USBHIDTouchpad*     _hid;
    CameraDriver*       _camera;
    OTAUpdater*         _ota;
    BehaviorRandomizer* _randomizer;
    WebServer           _server;
    bool                _running;
    String              _apSsid;

    // ---- HTTP 处理函数 ----

    /** GET /capture - 摄像头画面 */
    void _handleCapture();

    /** GET /status - 设备状态 */
    void _handleStatus();

    /** POST /tap - 点击操作 */
    void _handleTap();

    /** POST /swipe - 滑动操作 */
    void _handleSwipe();

    /** POST /ota - OTA 更新完成回调 */
    void _handleOTAComplete();

    /** POST /ota - OTA 上传流式回调 */
    void _handleOTAUpload();

    /** GET /config - 获取配置 */
    void _handleGetConfig();

    /** POST /config - 设置配置 */
    void _handleSetConfig();

    /** 404 处理 */
    void _handleNotFound();

    // ---- 辅助方法 ----

    /** 发送 JSON 成功响应 */
    void _sendJsonSuccess(const String& message);

    /** 发送 JSON 错误响应 */
    void _sendJsonError(int code, const String& message);

    /** 解析 JSON 请求体 */
    bool _parseJsonBody(JsonDocument& doc);

    /** 获取设备状态 JSON */
    String _buildStatusJson();
};

#endif // WIFI_AP_SERVER_H
