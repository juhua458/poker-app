/**
 * ============================================================================
 * 青云扑克 ESP32-S3-CAM - OTA 无线更新模块 实现
 * ============================================================================
 */

#include "ota_updater.h"
#include <esp_ota_ops.h>

// ============================================================================
// 构造函数
// ============================================================================
OTAUpdater::OTAUpdater()
    : _updating(false),
      _totalReceived(0),
      _totalSize(0),
      _progressCallback(nullptr)
{
}

// ============================================================================
// 初始化
// ============================================================================
void OTAUpdater::begin()
{
    // 打印当前分区信息
    const esp_partition_t* running = esp_ota_get_running_partition();
    if (running) {
        Serial.printf("[OTA] Running partition: %s (offset=0x%x, size=%uKB)\n",
                      running->label, running->address, running->size / 1024);
    }

    const esp_partition_t* next = esp_ota_get_next_update_partition(nullptr);
    if (next) {
        Serial.printf("[OTA] Update partition: %s (offset=0x%x, size=%uKB)\n",
                      next->label, next->address, next->size / 1024);
    }

    Serial.println("[OTA] OTA module initialized");
}

// ============================================================================
// 处理 OTA 更新
// ============================================================================
OTAResult OTAUpdater::handleUpdate(const uint8_t* data, size_t dataLen,
                                    size_t totalSize, const String& expectedMd5)
{
    // 参数校验
    // 注意：首次调用时 data 可以为 nullptr (仅初始化)，后续调用必须有数据
    if (!_updating && data == nullptr && dataLen == 0) {
        // 纯初始化调用，totalSize 必须有效
    } else if (_updating && (data == nullptr || dataLen == 0)) {
        // 后续调用必须携带数据
        return OTAResult::SUCCESS;  // 空数据块，跳过
    }

    // ---- 首次调用：初始化更新 ----
    if (!_updating) {
        if (totalSize == 0) {
            Serial.println("[OTA] Error: total size is 0");
            return OTAResult::ERROR_INVALID_SIZE;
        }

        // 检查可用空间
        size_t freeSpace = ESP.getFreeSketchSpace();
        if (totalSize > freeSpace) {
            Serial.printf("[OTA] Error: not enough space (need %u, have %u)\n",
                          totalSize, freeSpace);
            return OTAResult::ERROR_NO_SPACE;
        }

        // 开始更新
        if (!Update.begin(totalSize)) {
            Serial.printf("[OTA] Error: Update.begin() failed: %s\n",
                          Update.errorString());
            return OTAResult::ERROR_NO_SPACE;
        }

        _updating = true;
        _totalReceived = 0;
        _totalSize = totalSize;
        _expectedMd5 = expectedMd5;

        // 初始化 MD5 计算
        if (_expectedMd5.length() > 0) {
            _md5 = MD5Builder();
            _md5.begin();
        }

        Serial.printf("[OTA] Starting update: %u bytes, MD5=%s\n",
                      totalSize, expectedMd5.length() > 0 ? expectedMd5.c_str() : "N/A");
    }

    // ---- 后续调用：写入数据 ----
    if (!_updating) {
        return OTAResult::ERROR_UPDATE_IN_PROGRESS;
    }

    // 写入数据
    size_t written = Update.write(const_cast<uint8_t*>(data), dataLen);
    if (written != dataLen) {
        Serial.printf("[OTA] Error: write failed (expected %u, wrote %u): %s\n",
                      dataLen, written, Update.errorString());
        Update.abort();
        _updating = false;
        return OTAResult::ERROR_WRITE_FAILED;
    }

    // 更新 MD5
    if (_expectedMd5.length() > 0) {
        _md5.add(const_cast<uint8_t*>(data), dataLen);
    }

    _totalReceived += dataLen;
    _reportProgress();

    // ---- 最后一块数据：完成更新 ----
    if (_totalReceived >= _totalSize) {
        // 校验 MD5
        if (_expectedMd5.length() > 0) {
            _md5.calculate();
            String actualMd5 = _md5.toString();

            if (!actualMd5.equalsIgnoreCase(_expectedMd5)) {
                Serial.printf("[OTA] MD5 mismatch! Expected: %s, Got: %s\n",
                              _expectedMd5.c_str(), actualMd5.c_str());
                Update.abort();
                _updating = false;
                return OTAResult::ERROR_MD5_MISMATCH;
            }
            Serial.printf("[OTA] MD5 verified: %s\n", actualMd5.c_str());
        }

        // 完成更新
        if (!Update.end(true)) {  // true = 设置新分区为启动分区
            Serial.printf("[OTA] Error: Update.end() failed: %s\n",
                          Update.errorString());
            _updating = false;
            return OTAResult::ERROR_WRITE_FAILED;
        }

        _updating = false;
        Serial.println("[OTA] Update complete! Restarting...");

        // 延迟后重启
        restart(2000);
        return OTAResult::SUCCESS;
    }

    return OTAResult::SUCCESS;  // 数据已写入，等待更多数据
}

// ============================================================================
// 中止更新
// ============================================================================
void OTAUpdater::abort()
{
    if (_updating) {
        Update.abort();
        _updating = false;
        Serial.println("[OTA] Update aborted");
    }
}

// ============================================================================
// 设置进度回调
// ============================================================================
void OTAUpdater::setProgressCallback(OTAProgressCallback callback)
{
    _progressCallback = callback;
}

// ============================================================================
// 重启设备
// ============================================================================
void OTAUpdater::restart(uint32_t delayMs)
{
    Serial.printf("[OTA] Restarting in %lu ms...\n", delayMs);
    delay(delayMs);
    ESP.restart();
}

// ============================================================================
// 获取当前分区名称
// ============================================================================
String OTAUpdater::getCurrentPartitionName()
{
    const esp_partition_t* running = esp_ota_get_running_partition();
    if (running) {
        return String(running->label);
    }
    return "unknown";
}

// ============================================================================
// 获取 OTA 状态 JSON
// ============================================================================
String OTAUpdater::getStatusJson()
{
    String json = "{";
    json += "\"updating\":" + String(_updating ? "true" : "false") + ",";
    json += "\"partition\":\"" + getCurrentPartitionName() + "\",";
    json += "\"received\":" + String(_totalReceived) + ",";
    json += "\"total\":" + String(_totalSize) + ",";
    json += "\"progress\":" + String(_totalSize > 0 ?
            (int)((_totalReceived * 100) / _totalSize) : 0);
    json += "}";
    return json;
}

// ============================================================================
// 是否正在更新
// ============================================================================
bool OTAUpdater::isUpdating() const
{
    return _updating;
}

// ============================================================================
// 进度报告
// ============================================================================
void OTAUpdater::_reportProgress()
{
    if (_totalSize == 0) return;

    // 每 10% 报告一次
    static int lastPercent = -1;
    int percent = (int)((_totalReceived * 100) / _totalSize);

    if (percent != lastPercent && percent % 10 == 0) {
        Serial.printf("[OTA] Progress: %d%% (%u/%u bytes)\n",
                      percent, _totalReceived, _totalSize);
        lastPercent = percent;
    }

    // 调用回调
    if (_progressCallback) {
        _progressCallback(_totalReceived, _totalSize);
    }
}
