/**
 * ============================================================================
 * 青云扑克 ESP32-S3-CAM - OV5640 摄像头驱动模块
 * ============================================================================
 * 
 * 功能：初始化和控制 OV5640 摄像头
 * - 支持 JPEG 格式输出
 * - 使用 PSRAM 帧缓冲区
 * - 帧大小可动态调整
 * - 帧缓冲区自动回收防止泄漏
 * 
 * 引脚定义（ESP32-S3-CAM 标准配置，可在 platformio.ini 中覆盖）:
 *   PWDN  = -1    RESET = -1    XCLK  = 15
 *   SIOD  = 4     SIOC  = 5     D7    = 16
 *   D6    = 14    D5    = 12    D4    = 11
 *   D3    = 10    D2    = 9     D1    = 8
 *   D0    = 7     VSYNC = 6     HREF  = 21
 *   PCLK  = 13
 * ============================================================================
 */

#ifndef CAMERA_DRIVER_H
#define CAMERA_DRIVER_H

#include <Arduino.h>
#include <esp_camera.h>

// ============================================================================
// 摄像头引脚定义
// ============================================================================
// 这些宏可在 platformio.ini 的 build_flags 中覆盖
#ifndef CAM_PIN_PWDN
#define CAM_PIN_PWDN     -1
#endif
#ifndef CAM_PIN_RESET
#define CAM_PIN_RESET    -1
#endif
#ifndef CAM_PIN_XCLK
#define CAM_PIN_XCLK     15
#endif
#ifndef CAM_PIN_SIOD
#define CAM_PIN_SIOD     4
#endif
#ifndef CAM_PIN_SIOC
#define CAM_PIN_SIOC     5
#endif
#ifndef CAM_PIN_D7
#define CAM_PIN_D7       16
#endif
#ifndef CAM_PIN_D6
#define CAM_PIN_D6       14
#endif
#ifndef CAM_PIN_D5
#define CAM_PIN_D5       12
#endif
#ifndef CAM_PIN_D4
#define CAM_PIN_D4       11
#endif
#ifndef CAM_PIN_D3
#define CAM_PIN_D3       10
#endif
#ifndef CAM_PIN_D2
#define CAM_PIN_D2       9
#endif
#ifndef CAM_PIN_D1
#define CAM_PIN_D1       8
#endif
#ifndef CAM_PIN_D0
#define CAM_PIN_D0       7
#endif
#ifndef CAM_PIN_VSYNC
#define CAM_PIN_VSYNC    6
#endif
#ifndef CAM_PIN_HREF
#define CAM_PIN_HREF     21
#endif
#ifndef CAM_PIN_PCLK
#define CAM_PIN_PCLK     13
#endif

// ============================================================================
// 常量
// ============================================================================
constexpr int      DEFAULT_JPEG_QUALITY = 12;       // JPEG 品质 (0-63, 越低越好)
constexpr framesize_t DEFAULT_FRAME_SIZE = FRAMESIZE_SVGA;  // 默认帧大小 800x600
constexpr int      DEFAULT_FB_COUNT     = 2;         // 帧缓冲区数量 (使用PSRAM时可为2)

// ============================================================================
// CameraDriver 类
// ============================================================================
class CameraDriver {
public:
    CameraDriver();
    ~CameraDriver();

    /**
     * 初始化摄像头
     * @param frameSize 帧大小 (默认 SVGA 800x600)
     * @param jpegQuality JPEG品质 (默认 12)
     * @return true 初始化成功
     */
    bool begin(framesize_t frameSize = DEFAULT_FRAME_SIZE,
               int jpegQuality = DEFAULT_JPEG_QUALITY);

    /**
     * 反初始化摄像头 (释放资源)
     */
    void end();

    /**
     * 捕获一帧 JPEG 图像
     * @return 帧缓冲区指针，使用后必须调用 returnFrame() 释放
     *         失败返回 nullptr
     */
    camera_fb_t* captureFrame();

    /**
     * 释放帧缓冲区 (必须调用，否则内存泄漏!)
     * @param fb captureFrame() 返回的指针
     */
    void returnFrame(camera_fb_t* fb);

    /**
     * 设置帧大小
     * @param frameSize 帧大小枚举值
     * @return true 设置成功
     */
    bool setFrameSize(framesize_t frameSize);

    /**
     * 设置 JPEG 品质
     * @param quality 品质值 (0-63, 越低品质越高)
     * @return true 设置成功
     */
    bool setJpegQuality(int quality);

    /**
     * 获取摄像头传感器对象 (用于高级设置)
     * @return 传感器指针，失败返回 nullptr
     */
    sensor_t* getSensor();

    /**
     * 获取当前帧大小字符串
     */
    const char* getFrameSizeName() const;

    /**
     * 摄像头是否已初始化
     */
    bool isInitialized() const;

    /**
     * 获取 PSRAM 空闲大小 (字节)
     */
    size_t getFreePsram();

    /**
     * 打印摄像头状态信息
     */
    void printStatus();

private:
    bool _initialized;
    framesize_t _currentFrameSize;
    int _currentQuality;
    camera_config_t _config;

    /**
     * 构建 camera_config_t 结构
     */
    void _buildConfig(framesize_t frameSize, int jpegQuality);
};

#endif // CAMERA_DRIVER_H
