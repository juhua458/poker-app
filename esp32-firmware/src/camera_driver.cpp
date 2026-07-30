/**
 * ============================================================================
 * 青云扑克 ESP32-S3-CAM - OV5640 摄像头驱动模块 实现
 * ============================================================================
 */

#include "camera_driver.h"
#if defined(BOARD_HAS_PSRAM)
#include <esp32-hal-psram.h>
#endif

// ============================================================================
// 构造/析构
// ============================================================================
CameraDriver::CameraDriver()
    : _initialized(false),
      _currentFrameSize(DEFAULT_FRAME_SIZE),
      _currentQuality(DEFAULT_JPEG_QUALITY)
{
    memset(&_config, 0, sizeof(_config));
}

CameraDriver::~CameraDriver()
{
    end();
}

// ============================================================================
// 初始化摄像头
// ============================================================================
bool CameraDriver::begin(framesize_t frameSize, int jpegQuality)
{
    if (_initialized) {
        Serial.println("[CAM] Already initialized, deinit first");
        end();
    }

    Serial.println("[CAM] Initializing OV5640 camera...");

    // 构建配置
    _buildConfig(frameSize, jpegQuality);

    // 打印 PSRAM 信息
    size_t freePsram = getFreePsram();
    bool hasPsram = (freePsram > 100000);  // >100KB 视为 PSRAM 可用
    Serial.printf("[CAM] Free PSRAM before init: %u bytes (%.1f KB)\n",
                  freePsram, freePsram / 1024.0f);
    Serial.printf("[CAM] PSRAM available: %s\n", hasPsram ? "YES" : "NO");

    if (hasPsram) {
        // PSRAM 可用：使用双缓冲，帧缓冲放在 PSRAM
        _config.fb_location = CAMERA_FB_IN_PSRAM;
        _config.fb_count = 2;
        _config.grab_mode = CAMERA_GRAB_LATEST;
        Serial.println("[CAM] Using PSRAM for frame buffers (dual buffer)");
    } else {
        // PSRAM 不可用：降级到内部 SRAM，单缓冲 + 低分辨率
        Serial.println("[CAM] WARNING: PSRAM not available! Using internal SRAM fallback");
        _config.fb_location = CAMERA_FB_IN_DRAM;
        _config.fb_count = 1;
        _config.grab_mode = CAMERA_GRAB_LATEST;
        // 先用 QVGA 尝试，内部 SRAM 有限
        _config.frame_size = FRAMESIZE_QVGA;
        _config.jpeg_quality = 15;  // 降低质量减少内存
        Serial.println("[CAM] Fallback: QVGA, quality=15, single buffer in SRAM");
    }

    // 初始化摄像头
    esp_err_t err = esp_camera_init(&_config);

    // 如果失败且用了 PSRAM，尝试降级到 SRAM
    if (err != ESP_OK && hasPsram) {
        Serial.printf("[CAM] PSRAM init failed: %s, trying SRAM fallback...\n", esp_err_to_name(err));
        _config.fb_location = CAMERA_FB_IN_DRAM;
        _config.fb_count = 1;
        _config.frame_size = FRAMESIZE_QVGA;
        _config.jpeg_quality = 15;
        err = esp_camera_init(&_config);
    }

    // 如果 QVGA 也失败，尝试更小的 QQVGA
    if (err != ESP_OK) {
        Serial.printf("[CAM] QVGA failed: %s, trying QQVGA...\n", esp_err_to_name(err));
        esp_camera_deinit();  // 清理上次失败的初始化
        memset(&_config, 0, sizeof(_config));
        _buildConfig(FRAMESIZE_QQVGA, 20);
        _config.fb_location = CAMERA_FB_IN_DRAM;
        _config.fb_count = 1;
        _config.grab_mode = CAMERA_GRAB_LATEST;
        err = esp_camera_init(&_config);
    }

    if (err != ESP_OK) {
        Serial.printf("[CAM] Camera init failed completely: 0x%x (%s)\n", err, esp_err_to_name(err));
        Serial.println("[CAM] Continuing without camera, WiFi + HID still available");
        return false;
    }

    _initialized = true;
    _currentFrameSize = frameSize;
    _currentQuality = jpegQuality;

    // 配置传感器
    sensor_t* s = esp_camera_sensor_get();
    if (s) {
        // OV5640 特定设置
        s->set_vflip(s, 0);           // 不翻转
        s->set_hmirror(s, 0);         // 不镜像
        s->set_brightness(s, 0);      // 亮度 0
        s->set_contrast(s, 0);        // 对比度 0
        s->set_saturation(s, 0);      // 饱和度 0
        s->set_sharpness(s, 1);       // 锐度 +1 (扑克牌需要清晰边缘)
        s->set_quality(s, jpegQuality);
        s->set_framesize(s, frameSize);

        Serial.printf("[CAM] Sensor ID: 0x%x\n", s->id.PID);
    }

    // 打印初始化后 PSRAM 信息
    freePsram = getFreePsram();
    Serial.printf("[CAM] Free PSRAM after init: %u bytes (%.1f KB)\n",
                  freePsram, freePsram / 1024.0f);
    Serial.printf("[CAM] Camera initialized: %s, quality=%d\n",
                  getFrameSizeName(), jpegQuality);

    return true;
}

// ============================================================================
// 反初始化
// ============================================================================
void CameraDriver::end()
{
    if (_initialized) {
        esp_camera_deinit();
        _initialized = false;
        Serial.println("[CAM] Camera deinitialized");
    }
}

// ============================================================================
// 捕获帧
// ============================================================================
camera_fb_t* CameraDriver::captureFrame()
{
    if (!_initialized) {
        Serial.println("[CAM] Error: camera not initialized");
        return nullptr;
    }

    camera_fb_t* fb = esp_camera_fb_get();
    if (!fb) {
        Serial.println("[CAM] Error: capture failed (fb_get returned null)");
        return nullptr;
    }

    // 验证帧数据
    if (fb->format != PIXFORMAT_JPEG) {
        Serial.printf("[CAM] Warning: unexpected format %d (expected JPEG)\n", fb->format);
        returnFrame(fb);
        return nullptr;
    }

    if (fb->len == 0 || !fb->buf) {
        Serial.println("[CAM] Error: empty frame buffer");
        returnFrame(fb);
        return nullptr;
    }

    return fb;
}

// ============================================================================
// 释放帧缓冲区
// ============================================================================
void CameraDriver::returnFrame(camera_fb_t* fb)
{
    if (fb) {
        esp_camera_fb_return(fb);
    }
}

// ============================================================================
// 设置帧大小
// ============================================================================
bool CameraDriver::setFrameSize(framesize_t frameSize)
{
    if (!_initialized) {
        Serial.println("[CAM] Error: camera not initialized");
        return false;
    }

    sensor_t* s = esp_camera_sensor_get();
    if (!s) {
        Serial.println("[CAM] Error: cannot get sensor");
        return false;
    }

    if (s->set_framesize(s, frameSize) != 0) {
        Serial.printf("[CAM] Error: failed to set frame size\n");
        return false;
    }

    _currentFrameSize = frameSize;
    Serial.printf("[CAM] Frame size set to %s\n", getFrameSizeName());
    return true;
}

// ============================================================================
// 设置 JPEG 品质
// ============================================================================
bool CameraDriver::setJpegQuality(int quality)
{
    if (!_initialized) {
        Serial.println("[CAM] Error: camera not initialized");
        return false;
    }

    // 品质范围 0-63
    if (quality < 0) quality = 0;
    if (quality > 63) quality = 63;

    sensor_t* s = esp_camera_sensor_get();
    if (!s) {
        Serial.println("[CAM] Error: cannot get sensor");
        return false;
    }

    if (s->set_quality(s, quality) != 0) {
        Serial.printf("[CAM] Error: failed to set JPEG quality\n");
        return false;
    }

    _currentQuality = quality;
    Serial.printf("[CAM] JPEG quality set to %d\n", quality);
    return true;
}

// ============================================================================
// 获取传感器对象
// ============================================================================
sensor_t* CameraDriver::getSensor()
{
    if (!_initialized) return nullptr;
    return esp_camera_sensor_get();
}

// ============================================================================
// 辅助方法
// ============================================================================
bool CameraDriver::isInitialized() const
{
    return _initialized;
}

const char* CameraDriver::getFrameSizeName() const
{
    switch (_currentFrameSize) {
        case FRAMESIZE_QVGA:    return "QVGA (320x240)";
        case FRAMESIZE_VGA:     return "VGA (640x480)";
        case FRAMESIZE_SVGA:    return "SVGA (800x600)";
        case FRAMESIZE_XGA:     return "XGA (1024x768)";
        case FRAMESIZE_UXGA:    return "UXGA (1600x1200)";
        case FRAMESIZE_QXGA:    return "QXGA (2048x1536)";
        default:                return "Unknown";
    }
}

size_t CameraDriver::getFreePsram()
{
    return heap_caps_get_free_size(MALLOC_CAP_SPIRAM);
}

void CameraDriver::printStatus()
{
    Serial.println("---- Camera Status ----");
    Serial.printf("  Initialized: %s\n", _initialized ? "Yes" : "No");
    if (_initialized) {
        Serial.printf("  Frame Size: %s\n", getFrameSizeName());
        Serial.printf("  JPEG Quality: %d\n", _currentQuality);
        Serial.printf("  Free PSRAM: %u bytes (%.1f KB)\n",
                      getFreePsram(), getFreePsram() / 1024.0f);
        Serial.printf("  Free Heap: %u bytes\n", ESP.getFreeHeap());
    }
    Serial.println("-----------------------");
}

// ============================================================================
// 构建摄像头配置
// ============================================================================
void CameraDriver::_buildConfig(framesize_t frameSize, int jpegQuality)
{
    memset(&_config, 0, sizeof(_config));

    // 引脚配置 (从宏定义获取)
    _config.pin_pwdn     = CAM_PIN_PWDN;
    _config.pin_reset    = CAM_PIN_RESET;
    _config.pin_xclk     = CAM_PIN_XCLK;
    _config.pin_sccb_sda = CAM_PIN_SIOD;
    _config.pin_sccb_scl = CAM_PIN_SIOC;

    _config.pin_d7       = CAM_PIN_D7;
    _config.pin_d6       = CAM_PIN_D6;
    _config.pin_d5       = CAM_PIN_D5;
    _config.pin_d4       = CAM_PIN_D4;
    _config.pin_d3       = CAM_PIN_D3;
    _config.pin_d2       = CAM_PIN_D2;
    _config.pin_d1       = CAM_PIN_D1;
    _config.pin_d0       = CAM_PIN_D0;

    _config.pin_vsync    = CAM_PIN_VSYNC;
    _config.pin_href     = CAM_PIN_HREF;
    _config.pin_pclk     = CAM_PIN_PCLK;

    // 时钟和输出配置
    _config.xclk_freq_hz = 20000000;         // 20MHz XCLK
    _config.ledc_timer   = LEDC_TIMER_0;
    _config.ledc_channel = LEDC_CHANNEL_0;

    // 像素格式和帧大小
    _config.pixel_format = PIXFORMAT_JPEG;    // JPEG 格式输出
    _config.frame_size   = frameSize;
    _config.jpeg_quality = jpegQuality;

    // 帧缓冲区配置
    // PSRAM 可用时使用双缓冲，提高帧率
    _config.fb_count     = DEFAULT_FB_COUNT;
    _config.grab_mode    = CAMERA_GRAB_WHEN_EMPTY;

    Serial.printf("[CAM] Pin config: XCLK=%d, SIOD=%d, SIOC=%d\n",
                  CAM_PIN_XCLK, CAM_PIN_SIOD, CAM_PIN_SIOC);
    Serial.printf("[CAM] Pin config: D0=%d..D7=%d, VSYNC=%d, HREF=%d, PCLK=%d\n",
                  CAM_PIN_D0, CAM_PIN_D7, CAM_PIN_VSYNC, CAM_PIN_HREF, CAM_PIN_PCLK);
}
