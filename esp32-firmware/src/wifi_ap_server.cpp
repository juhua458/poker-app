/**
 * ============================================================================
 * 青云扑克 ESP32-S3-CAM - WiFi AP + HTTP 服务模块 实现
 * ============================================================================
 */

#include "wifi_ap_server.h"
#include <ArduinoJson.h>

// ============================================================================
// 构造函数
// ============================================================================
WiFiAPServer::WiFiAPServer(USBHIDTouchpad* hid, CameraDriver* camera,
                           OTAUpdater* ota, BehaviorRandomizer* randomizer)
    : _hid(hid),
      _camera(camera),
      _ota(ota),
      _randomizer(randomizer),
      _server(HTTP_PORT),
      _running(false)
{
}

// ============================================================================
// 初始化
// ============================================================================
bool WiFiAPServer::begin(const char* ssid, const char* password)
{
    Serial.println("[WiFi] Starting AP mode...");

    _apSsid = ssid;

    // 配置静态 IP
    IPAddress apIP, gatewayIP, subnetMask;
    apIP.fromString(DEFAULT_AP_IP);
    gatewayIP.fromString(DEFAULT_AP_GATEWAY);
    subnetMask.fromString(DEFAULT_AP_SUBNET);

    WiFi.mode(WIFI_AP);
    WiFi.softAPConfig(apIP, gatewayIP, subnetMask);

    // 启动 AP
    if (!WiFi.softAP(ssid, password)) {
        Serial.println("[WiFi] Error: failed to start AP");
        return false;
    }

    Serial.printf("[WiFi] AP started: SSID=%s, IP=%s\n",
                  ssid, WiFi.softAPIP().toString().c_str());

    // 注册 HTTP 路由
    _server.on("/capture", HTTP_GET, std::bind(&WiFiAPServer::_handleCapture, this));
    _server.on("/status",  HTTP_GET, std::bind(&WiFiAPServer::_handleStatus, this));
    _server.on("/tap",     HTTP_POST, std::bind(&WiFiAPServer::_handleTap, this));
    _server.on("/swipe",   HTTP_POST, std::bind(&WiFiAPServer::_handleSwipe, this));
    _server.on("/ota",     HTTP_POST,
               std::bind(&WiFiAPServer::_handleOTAComplete, this),
               std::bind(&WiFiAPServer::_handleOTAUpload, this));
    _server.on("/config",  HTTP_GET, std::bind(&WiFiAPServer::_handleGetConfig, this));
    _server.on("/config",  HTTP_POST, std::bind(&WiFiAPServer::_handleSetConfig, this));
    _server.onNotFound(std::bind(&WiFiAPServer::_handleNotFound, this));

    // 启动服务器
    _server.begin();
    _running = true;

    Serial.printf("[HTTP] Server started on port %d\n", HTTP_PORT);
    Serial.println("[HTTP] Endpoints:");
    Serial.println("  GET  /capture - Camera JPEG frame");
    Serial.println("  GET  /status  - Device status JSON");
    Serial.println("  POST /tap     - Tap screen {x, y, duration}");
    Serial.println("  POST /swipe   - Swipe screen {x1,y1,x2,y2,duration}");
    Serial.println("  POST /ota     - OTA firmware update");
    Serial.println("  GET  /config  - Get randomizer config");
    Serial.println("  POST /config  - Set randomizer config");

    return true;
}

// ============================================================================
// 处理客户端请求
// ============================================================================
void WiFiAPServer::handleClient()
{
    if (_running) {
        _server.handleClient();
    }
}

// ============================================================================
// 关闭
// ============================================================================
void WiFiAPServer::end()
{
    if (_running) {
        _server.stop();
        _running = false;
        Serial.println("[HTTP] Server stopped");
    }
}

// ============================================================================
// 辅助方法
// ============================================================================
String WiFiAPServer::getAPIP()
{
    return WiFi.softAPIP().toString();
}

int WiFiAPServer::getConnectedClients()
{
    return WiFi.softAPgetStationNum();
}

bool WiFiAPServer::isRunning() const
{
    return _running;
}

// ============================================================================
// GET /capture - 摄像头画面
// ============================================================================
void WiFiAPServer::_handleCapture()
{
    if (!_camera || !_camera->isInitialized()) {
        _sendJsonError(503, "Camera not initialized");
        return;
    }

    camera_fb_t* fb = _camera->captureFrame();
    if (!fb) {
        _sendJsonError(500, "Camera capture failed");
        return;
    }

    // 发送 JPEG 图像
    _server.sendHeader("Content-Type", "image/jpeg");
    _server.sendHeader("Content-Length", String(fb->len));
    _server.sendHeader("Access-Control-Allow-Origin", "*");
    _server.send_P(200, "image/jpeg", reinterpret_cast<const char*>(fb->buf), fb->len);

    // 释放帧缓冲区 (必须!)
    _camera->returnFrame(fb);
}

// ============================================================================
// GET /status - 设备状态
// ============================================================================
void WiFiAPServer::_handleStatus()
{
    String json = _buildStatusJson();
    _server.sendHeader("Access-Control-Allow-Origin", "*");
    _server.send(200, "application/json", json);
}

// ============================================================================
// POST /tap - 点击操作
// ============================================================================
void WiFiAPServer::_handleTap()
{
    JsonDocument doc;
    if (!_parseJsonBody(doc)) {
        _sendJsonError(400, "Invalid JSON body");
        return;
    }

    // 解析参数
    int x = doc["x"] | -1;
    int y = doc["y"] | -1;
    int duration = doc["duration"] | 50;

    // 参数校验
    if (x < 0 || x >= 1080 || y < 0 || y >= 2344) {
        _sendJsonError(400, "Coordinates out of range (x:0-1079, y:0-2343)");
        return;
    }

    if (duration < 10 || duration > 5000) {
        _sendJsonError(400, "Duration out of range (10-5000ms)");
        return;
    }

    // 应用行为随机化
    uint16_t randX, randY;
    _randomizer->randomizeCoords((uint16_t)x, (uint16_t)y, randX, randY);
    uint32_t randDuration = _randomizer->randomizeDuration((uint32_t)duration);

    // 执行随机延迟
    uint32_t preDelay = _randomizer->getRandomDelay();
    if (preDelay > 0) {
        delay(preDelay);
    }

    // 执行点击
    bool ok = _hid->tap(randX, randY, randDuration);

    if (ok) {
        char msg[128];
        snprintf(msg, sizeof(msg),
                 "Tap executed: screen(%d,%d)→rand(%d,%d) dur=%d→%dms",
                 x, y, randX, randY, duration, randDuration);
        _sendJsonSuccess(msg);
    } else {
        _sendJsonError(500, "HID tap failed (device not mounted?)");
    }
}

// ============================================================================
// POST /swipe - 滑动操作
// ============================================================================
void WiFiAPServer::_handleSwipe()
{
    JsonDocument doc;
    if (!_parseJsonBody(doc)) {
        _sendJsonError(400, "Invalid JSON body");
        return;
    }

    // 解析参数
    int x1 = doc["x1"] | -1;
    int y1 = doc["y1"] | -1;
    int x2 = doc["x2"] | -1;
    int y2 = doc["y2"] | -1;
    int duration = doc["duration"] | 300;

    // 参数校验
    if (x1 < 0 || x1 >= 1080 || y1 < 0 || y1 >= 2344 ||
        x2 < 0 || x2 >= 1080 || y2 < 0 || y2 >= 2344) {
        _sendJsonError(400, "Coordinates out of range (x:0-1079, y:0-2343)");
        return;
    }

    if (duration < 50 || duration > 10000) {
        _sendJsonError(400, "Duration out of range (50-10000ms)");
        return;
    }

    // 应用行为随机化
    uint16_t randX1, randY1, randX2, randY2;
    _randomizer->randomizeCoords((uint16_t)x1, (uint16_t)y1, randX1, randY1);
    _randomizer->randomizeCoords((uint16_t)x2, (uint16_t)y2, randX2, randY2);
    uint32_t randDuration = _randomizer->randomizeDuration((uint32_t)duration);

    // 执行随机延迟
    uint32_t preDelay = _randomizer->getRandomDelay();
    if (preDelay > 0) {
        delay(preDelay);
    }

    // 执行滑动
    bool ok = _hid->swipe(randX1, randY1, randX2, randY2, randDuration);

    if (ok) {
        char msg[160];
        snprintf(msg, sizeof(msg),
                 "Swipe executed: (%d,%d)→(%d,%d) rand(%d,%d)→(%d,%d) dur=%dms",
                 x1, y1, x2, y2, randX1, randY1, randX2, randY2, randDuration);
        _sendJsonSuccess(msg);
    } else {
        _sendJsonError(500, "HID swipe failed (device not mounted?)");
    }
}

// ============================================================================
// POST /ota - OTA 流式上传回调 (每收到一块数据调用一次)
// ============================================================================
void WiFiAPServer::_handleOTAUpload()
{
    HTTPUpload& upload = _server.upload();

    // ---- 上传开始 ----
    if (upload.status == UPLOAD_FILE_START) {
        Serial.printf("[OTA] Upload start: filename=%s, totalSize=%u\n",
                      upload.filename.c_str(), upload.totalSize);

        if (upload.totalSize == 0) {
            Serial.println("[OTA] Error: total size is 0");
            return;
        }

        // 获取预期的 MD5 (可选，从 Header 中)
        String expectedMd5 = _server.header("X-MD5");

        // 初始化 OTA 更新
        OTAResult result = _ota->handleUpdate(
            nullptr, 0, upload.totalSize, expectedMd5);

        if (result != OTAResult::SUCCESS) {
            Serial.printf("[OTA] Error: init failed (%d)\n", (int)result);
            return;
        }

        Serial.println("[OTA] Upload initialized successfully");
    }
    // ---- 上传写入 ----
    else if (upload.status == UPLOAD_FILE_WRITE) {
        // 将收到的数据块写入 OTA 分区
        if (_ota->isUpdating() && upload.currentSize > 0) {
            OTAResult result = _ota->handleUpdate(
                upload.buf, upload.currentSize,
                0,  // totalSize=0 表示后续数据块
                ""  // MD5 仅在首次调用时需要
            );

            if (result != OTAResult::SUCCESS) {
                Serial.printf("[OTA] Error: write failed (%d)\n", (int)result);
            }
        }
    }
    // ---- 上传结束 ----
    else if (upload.status == UPLOAD_FILE_END) {
        Serial.printf("[OTA] Upload end: totalSize=%u\n", upload.totalSize);
    }
    // ---- 上传中止 ----
    else if (upload.status == UPLOAD_FILE_ABORTED) {
        Serial.println("[OTA] Upload aborted by client");
        _ota->abort();
    }
}

// ============================================================================
// POST /ota - OTA 上传完成回调
// ============================================================================
void WiFiAPServer::_handleOTAComplete()
{
    if (_ota->isUpdating()) {
        // 上传完成但 OTA 未正常结束
        _ota->abort();
        _sendJsonError(500, "OTA update incomplete");
        return;
    }

    // 检查更新结果 - 如果成功，设备会在 handleUpdate 中自动重启
    _sendJsonSuccess("OTA update complete, device will restart...");
}

// ============================================================================
// GET /config - 获取配置
// ============================================================================
void WiFiAPServer::_handleGetConfig()
{
    if (!_randomizer) {
        _sendJsonError(500, "Randomizer not initialized");
        return;
    }

    const RandomizerConfig& cfg = _randomizer->getConfig();
    JsonDocument doc;
    doc["enabled"]             = cfg.enabled;
    doc["coordOffsetMin"]      = cfg.coordOffsetMin;
    doc["coordOffsetMax"]      = cfg.coordOffsetMax;
    doc["coordOffsetSigma"]    = cfg.coordOffsetSigma;
    doc["timingJitterMin"]     = cfg.timingJitterMin;
    doc["timingJitterMax"]     = cfg.timingJitterMax;
    doc["durationJitterMin"]   = cfg.durationJitterMin;
    doc["durationJitterMax"]   = cfg.durationJitterMax;
    doc["swipeStepJitterMin"]  = cfg.swipeStepJitterMin;
    doc["swipeStepJitterMax"]  = cfg.swipeStepJitterMax;

    String json;
    serializeJson(doc, json);
    _server.sendHeader("Access-Control-Allow-Origin", "*");
    _server.send(200, "application/json", json);
}

// ============================================================================
// POST /config - 设置配置
// ============================================================================
void WiFiAPServer::_handleSetConfig()
{
    if (!_randomizer) {
        _sendJsonError(500, "Randomizer not initialized");
        return;
    }

    JsonDocument doc;
    if (!_parseJsonBody(doc)) {
        _sendJsonError(400, "Invalid JSON body");
        return;
    }

    RandomizerConfig cfg = _randomizer->getConfig();

    // 更新指定字段
    if (doc.containsKey("enabled"))             cfg.enabled = doc["enabled"];
    if (doc.containsKey("coordOffsetMin"))      cfg.coordOffsetMin = doc["coordOffsetMin"];
    if (doc.containsKey("coordOffsetMax"))      cfg.coordOffsetMax = doc["coordOffsetMax"];
    if (doc.containsKey("coordOffsetSigma"))    cfg.coordOffsetSigma = doc["coordOffsetSigma"];
    if (doc.containsKey("timingJitterMin"))     cfg.timingJitterMin = doc["timingJitterMin"];
    if (doc.containsKey("timingJitterMax"))     cfg.timingJitterMax = doc["timingJitterMax"];
    if (doc.containsKey("durationJitterMin"))   cfg.durationJitterMin = doc["durationJitterMin"];
    if (doc.containsKey("durationJitterMax"))   cfg.durationJitterMax = doc["durationJitterMax"];
    if (doc.containsKey("swipeStepJitterMin"))  cfg.swipeStepJitterMin = doc["swipeStepJitterMin"];
    if (doc.containsKey("swipeStepJitterMax"))  cfg.swipeStepJitterMax = doc["swipeStepJitterMax"];

    _randomizer->setConfig(cfg);
    _sendJsonSuccess("Config updated");
}

// ============================================================================
// 404 处理
// ============================================================================
void WiFiAPServer::_handleNotFound()
{
    _sendJsonError(404, "Not found. Available: /capture, /status, /tap, /swipe, /ota, /config");
}

// ============================================================================
// 辅助方法实现
// ============================================================================
void WiFiAPServer::_sendJsonSuccess(const String& message)
{
    JsonDocument doc;
    doc["success"] = true;
    doc["message"] = message;
    String json;
    serializeJson(doc, json);
    _server.sendHeader("Access-Control-Allow-Origin", "*");
    _server.send(200, "application/json", json);
}

void WiFiAPServer::_sendJsonError(int code, const String& message)
{
    JsonDocument doc;
    doc["success"] = false;
    doc["error"] = message;
    String json;
    serializeJson(doc, json);
    _server.sendHeader("Access-Control-Allow-Origin", "*");
    _server.send(code, "application/json", json);
}

bool WiFiAPServer::_parseJsonBody(JsonDocument& doc)
{
    String body = _server.arg("plain");
    if (body.length() == 0) {
        return false;
    }

    DeserializationError error = deserializeJson(doc, body);
    if (error) {
        Serial.printf("[HTTP] JSON parse error: %s\n", error.c_str());
        return false;
    }

    return true;
}

String WiFiAPServer::_buildStatusJson()
{
    JsonDocument doc;

    // 设备信息
    doc["device"] = "QingYun-ESP32-CAM";
    doc["version"] = "1.0.0";

    // USB HID 状态
    doc["usb_hid"]["mounted"] = _hid ? _hid->isMounted() : false;

    // WiFi AP 状态
    doc["wifi"]["ap_ssid"] = _apSsid;
    doc["wifi"]["ap_ip"] = getAPIP();
    doc["wifi"]["clients"] = getConnectedClients();

    // 摄像头状态
    doc["camera"]["initialized"] = _camera ? _camera->isInitialized() : false;
    if (_camera && _camera->isInitialized()) {
        doc["camera"]["frame_size"] = _camera->getFrameSizeName();
    }

    // 内存信息
    doc["memory"]["free_heap"] = ESP.getFreeHeap();
    doc["memory"]["free_psram"] = _camera ? _camera->getFreePsram() : 0;
    doc["memory"]["min_free_heap"] = ESP.getMinFreeHeap();

    // OTA 状态
    doc["ota"]["partition"] = _ota ? _ota->getCurrentPartitionName() : "unknown";
    doc["ota"]["updating"] = _ota ? _ota->isUpdating() : false;

    // 行为随机化
    if (_randomizer) {
        const RandomizerConfig& cfg = _randomizer->getConfig();
        doc["randomizer"]["enabled"] = cfg.enabled;
        doc["randomizer"]["coord_sigma"] = cfg.coordOffsetSigma;
    }

    // 运行时间
    doc["uptime_ms"] = millis();

    String json;
    serializeJson(doc, json);
    return json;
}
