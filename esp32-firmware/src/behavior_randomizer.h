/**
 * ============================================================================
 * 青云扑克 ESP32-S3-CAM - 行为随机化模块
 * ============================================================================
 * 
 * 功能：为触摸操作添加随机化偏移，模拟真人操作以规避检测
 * - 坐标高斯偏移：每次点击添加 ±3-8 像素随机偏移
 * - 点击间隔抖动：实际执行时间 = 指令时间 ± 随机(50-200ms)
 * - 触摸持续时间随机化：基础 50ms ± 随机(10-30ms)
 * - 滑动速度微调：步进间隔随机化
 * 
 * 所有参数可通过 API 动态调整
 * ============================================================================
 */

#ifndef BEHAVIOR_RANDOMIZER_H
#define BEHAVIOR_RANDOMIZER_H

#include <Arduino.h>

// ============================================================================
// 默认随机化参数
// ============================================================================
struct RandomizerConfig {
    // 坐标偏移范围 (像素)
    int16_t  coordOffsetMin;       // 最小偏移 (默认: -8)
    int16_t  coordOffsetMax;       // 最大偏移 (默认: +8)
    float    coordOffsetSigma;     // 高斯分布标准差 (默认: 3.0)
    
    // 时间抖动范围 (ms)
    int32_t  timingJitterMin;      // 最小时间偏移 (默认: -50)
    int32_t  timingJitterMax;      // 最大时间偏移 (默认: +200)
    
    // 触摸持续时间随机化 (ms)
    int32_t  durationJitterMin;    // 最小持续时间偏移 (默认: -10)
    int32_t  durationJitterMax;    // 最大持续时间偏移 (默认: +30)
    
    // 滑动步进间隔随机化 (ms)
    int32_t  swipeStepJitterMin;   // 最小步进偏移 (默认: -2)
    int32_t  swipeStepJitterMax;   // 最大步进偏移 (默认: +5)
    
    // 是否启用随机化
    bool     enabled;
};

// ============================================================================
// BehaviorRandomizer 类
// ============================================================================
class BehaviorRandomizer {
public:
    BehaviorRandomizer();

    /**
     * 初始化随机数生成器
     */
    void begin();

    /**
     * 对屏幕坐标添加随机偏移
     * @param screenX 原始屏幕X坐标
     * @param screenY 原始屏幕Y坐标
     * @param outX 输出X坐标 (添加偏移后)
     * @param outY 输出Y坐标 (添加偏移后)
     */
    void randomizeCoords(uint16_t screenX, uint16_t screenY,
                         uint16_t& outX, uint16_t& outY);

    /**
     * 对触摸持续时间添加随机偏移
     * @param durationMs 原始持续时间
     * @return 随机化后的持续时间
     */
    uint32_t randomizeDuration(uint32_t durationMs);

    /**
     * 获取随机延迟时间 (用于点击间隔)
     * @return 随机延迟 (ms)
     */
    uint32_t getRandomDelay();

    /**
     * 对滑动步进间隔添加随机偏移
     * @param stepMs 原始步进间隔
     * @return 随机化后的步进间隔
     */
    uint32_t randomizeSwipeStep(uint32_t stepMs);

    /**
     * 获取当前配置
     */
    const RandomizerConfig& getConfig() const;

    /**
     * 更新配置
     */
    void setConfig(const RandomizerConfig& config);

    /**
     * 启用/禁用随机化
     */
    void setEnabled(bool enabled);

private:
    RandomizerConfig _config;
    bool _initialized;

    /**
     * 生成高斯分布随机数 (Box-Muller 变换)
     * @param mean 均值
     * @param sigma 标准差
     * @return 随机数
     */
    float _gaussianRandom(float mean, float sigma);

    /**
     * 生成均匀分布随机整数
     * @param min 最小值
     * @param max 最大值
     * @return 随机数
     */
    int32_t _uniformRandom(int32_t min, int32_t max);

    /**
     * 限制坐标在屏幕范围内
     */
    uint16_t _clampScreenX(int32_t value);
    uint16_t _clampScreenY(int32_t value);
};

#endif // BEHAVIOR_RANDOMIZER_H
