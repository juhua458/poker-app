/**
 * ============================================================================
 * 青云扑克 ESP32-S3-CAM - 行为随机化模块 实现
 * ============================================================================
 */

#include "behavior_randomizer.h"
#include <math.h>

// ============================================================================
// 构造函数 - 设置默认参数
// ============================================================================
BehaviorRandomizer::BehaviorRandomizer()
    : _initialized(false)
{
    _config.coordOffsetMin    = -8;
    _config.coordOffsetMax    = 8;
    _config.coordOffsetSigma  = 3.0f;
    _config.timingJitterMin   = -50;
    _config.timingJitterMax   = 200;
    _config.durationJitterMin = -10;
    _config.durationJitterMax = 30;
    _config.swipeStepJitterMin = -2;
    _config.swipeStepJitterMax = 5;
    _config.enabled           = true;
}

// ============================================================================
// 初始化
// ============================================================================
void BehaviorRandomizer::begin()
{
    // 使用硬件随机数作为种子
    randomSeed(esp_random());
    _initialized = true;
    Serial.println("[RAND] Behavior randomizer initialized (seed from hardware RNG)");
}

// ============================================================================
// 坐标随机化
// ============================================================================
void BehaviorRandomizer::randomizeCoords(uint16_t screenX, uint16_t screenY,
                                          uint16_t& outX, uint16_t& outY)
{
    if (!_config.enabled || !_initialized) {
        outX = screenX;
        outY = screenY;
        return;
    }

    // 使用高斯分布生成偏移，更接近真人手指的自然抖动
    float offsetX = _gaussianRandom(0.0f, _config.coordOffsetSigma);
    float offsetY = _gaussianRandom(0.0f, _config.coordOffsetSigma);

    // 限制偏移在配置范围内
    if (offsetX < _config.coordOffsetMin) offsetX = _config.coordOffsetMin;
    if (offsetX > _config.coordOffsetMax) offsetX = _config.coordOffsetMax;
    if (offsetY < _config.coordOffsetMin) offsetY = _config.coordOffsetMin;
    if (offsetY > _config.coordOffsetMax) offsetY = _config.coordOffsetMax;

    // 应用偏移并限制在屏幕范围内
    outX = _clampScreenX((int32_t)screenX + (int32_t)roundf(offsetX));
    outY = _clampScreenY((int32_t)screenY + (int32_t)roundf(offsetY));
}

// ============================================================================
// 持续时间随机化
// ============================================================================
uint32_t BehaviorRandomizer::randomizeDuration(uint32_t durationMs)
{
    if (!_config.enabled || !_initialized) {
        return durationMs;
    }

    int32_t jitter = _uniformRandom(_config.durationJitterMin, _config.durationJitterMax);
    int32_t result = (int32_t)durationMs + jitter;

    // 最小持续时间 10ms，确保触摸动作有效
    if (result < 10) result = 10;
    // 最大持续时间 5000ms，防止异常
    if (result > 5000) result = 5000;

    return (uint32_t)result;
}

// ============================================================================
// 获取随机延迟
// ============================================================================
uint32_t BehaviorRandomizer::getRandomDelay()
{
    if (!_config.enabled || !_initialized) {
        return 0;
    }

    int32_t delay = _uniformRandom(_config.timingJitterMin, _config.timingJitterMax);
    // 确保非负
    if (delay < 0) delay = 0;
    return (uint32_t)delay;
}

// ============================================================================
// 滑动步进随机化
// ============================================================================
uint32_t BehaviorRandomizer::randomizeSwipeStep(uint32_t stepMs)
{
    if (!_config.enabled || !_initialized) {
        return stepMs;
    }

    int32_t jitter = _uniformRandom(_config.swipeStepJitterMin, _config.swipeStepJitterMax);
    int32_t result = (int32_t)stepMs + jitter;

    // 最小步进 2ms
    if (result < 2) result = 2;
    // 最大步进 50ms
    if (result > 50) result = 50;

    return (uint32_t)result;
}

// ============================================================================
// 配置管理
// ============================================================================
const RandomizerConfig& BehaviorRandomizer::getConfig() const
{
    return _config;
}

void BehaviorRandomizer::setConfig(const RandomizerConfig& config)
{
    _config = config;
    Serial.printf("[RAND] Config updated: enabled=%d, sigma=%.1f\n",
                  _config.enabled, _config.coordOffsetSigma);
}

void BehaviorRandomizer::setEnabled(bool enabled)
{
    _config.enabled = enabled;
    Serial.printf("[RAND] Randomizer %s\n", enabled ? "enabled" : "disabled");
}

// ============================================================================
// 私有方法
// ============================================================================

// Box-Muller 变换生成高斯分布随机数
float BehaviorRandomizer::_gaussianRandom(float mean, float sigma)
{
    // 生成两个 [0,1) 均匀分布随机数
    float u1 = (float)random(1, 10000) / 10000.0f;
    float u2 = (float)random(1, 10000) / 10000.0f;

    // Box-Muller 变换
    float z = sqrtf(-2.0f * logf(u1)) * cosf(2.0f * 3.14159265f * u2);

    return mean + sigma * z;
}

// 均匀分布随机整数
int32_t BehaviorRandomizer::_uniformRandom(int32_t min, int32_t max)
{
    if (min >= max) return min;
    return min + (random() % (max - min + 1));
}

// 限制屏幕X坐标
uint16_t BehaviorRandomizer::_clampScreenX(int32_t value)
{
    if (value < 0) return 0;
    if (value > 1079) return 1079;  // SCREEN_WIDTH - 1
    return (uint16_t)value;
}

// 限制屏幕Y坐标
uint16_t BehaviorRandomizer::_clampScreenY(int32_t value)
{
    if (value < 0) return 0;
    if (value > 2343) return 2343;  // SCREEN_HEIGHT - 1
    return (uint16_t)value;
}
