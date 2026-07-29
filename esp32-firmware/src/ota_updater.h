/**
 * ============================================================================
 * 青云扑克 ESP32-S3-CAM - OTA 无线更新模块
 * ============================================================================
 * 
 * 功能：通过 HTTP POST 接收新固件并更新
 * - 支持 MD5 校验确保固件完整性
 * - 更新过程有进度反馈 (Serial 输出)
 * - 失败自动回滚 (ESP-IDF OTA 机制)
 * - 双分区设计，更新到备用分区，启动失败自动回退
 * 
 * OTA 更新流程:
 * 1. 客户端 POST /ota，Header 中包含 X-MD5 和 Content-Length
 * 2. 服务器流式接收固件数据，写入 OTA 分区
 * 3. 接收完成后校验 MD5
 * 4. 标记新分区为启动分区
 * 5. 重启设备
 * ============================================================================
 */

#ifndef OTA_UPDATER_H
#define OTA_UPDATER_H

#include <Arduino.h>
#include <Update.h>
#include <MD5Builder.h>

// ============================================================================
// OTA 结果枚举
// ============================================================================
enum class OTAResult {
    SUCCESS,               // 更新成功，需要重启
    ERROR_NO_SPACE,        // 空间不足
    ERROR_WRITE_FAILED,    // 写入失败
    ERROR_MD5_MISMATCH,    // MD5 校验失败
    ERROR_INVALID_SIZE,    // 固件大小无效
    ERROR_UPDATE_IN_PROGRESS, // 已有更新进行中
    ERROR_ABORTED,         // 更新被中止
    ERROR_UNKNOWN          // 未知错误
};

// ============================================================================
// OTA 进度回调类型
// @param progress 已接收字节数
// @param total 总字节数
// ============================================================================
typedef void (*OTAProgressCallback)(size_t progress, size_t total);

// ============================================================================
// OTAUpdater 类
// ============================================================================
class OTAUpdater {
public:
    OTAUpdater();

    /**
     * 初始化 OTA 模块
     */
    void begin();

    /**
     * 处理 OTA 更新
     * @param data 固件数据指针
     * @param dataLen 数据长度
     * @param totalSize 预期总大小
     * @param expectedMd5 预期的 MD5 校验和 (十六进制字符串，可为空)
     * @return OTA 结果
     */
    OTAResult handleUpdate(const uint8_t* data, size_t dataLen,
                           size_t totalSize, const String& expectedMd5 = "");

    /**
     * 中止正在进行的更新
     */
    void abort();

    /**
     * 设置进度回调
     */
    void setProgressCallback(OTAProgressCallback callback);

    /**
     * 重启设备
     * @param delayMs 重启前延迟 (ms)
     */
    void restart(uint32_t delayMs = 1000);

    /**
     * 获取当前运行的分区名称
     */
    String getCurrentPartitionName();

    /**
     * 获取 OTA 状态信息 (JSON 格式)
     */
    String getStatusJson();

    /**
     * 是否正在更新中
     */
    bool isUpdating() const;

private:
    bool _updating;
    size_t _totalReceived;
    size_t _totalSize;
    MD5Builder _md5;
    OTAProgressCallback _progressCallback;
    String _expectedMd5;

    /**
     * 计算进度百分比并调用回调
     */
    void _reportProgress();
};

#endif // OTA_UPDATER_H
