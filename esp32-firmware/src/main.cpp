/**
 * ============================================================================
 * 青云扑克 ESP32-S3-CAM - 极简平台验证固件 (v1.0.9)
 * ============================================================================
 * 
 * v1.0.9 变更：
 *   - 移除 ARDUINO_USB_CDC_ON_BOOT（避免框架初始化阶段USB CDC abort）
 *   - 移除 ARDUINO_USB_MODE=1（走CH343串口，不碰Native USB）
 *   - 移除 TinyUSB 依赖（避免静态初始化冲突）
 *   - 使用 default_16MB.csv 分区表
 *   - 只编译 main.cpp（build_src_filter 排除其他源文件）
 *   - 禁用 brownout detector
 *   - 禁用 CORE_DEBUG_LEVEL（减少初始化开销）
 * 
 * 预期行为：
 *   - 串口（CH343, 115200）输出 banner + 芯片信息 + 心跳
 *   - 如果成功，说明平台层OK，后续逐步恢复功能
 *   - 如果仍崩溃，基本可以确认板子硬件与当前框架不兼容
 */

#include <Arduino.h>

// 禁用 brownout detector（防止上电瞬间电压波动导致复位）
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"

// ============================================================================
// setup() - 仅做串口初始化和系统信息打印
// ============================================================================
void setup()
{
    // 禁用 brownout detector
    WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);

    Serial.begin(115200);
    delay(2000);  // 等待串口就绪（CH343需要更长时间）

    Serial.println();
    Serial.println("========================================================");
    Serial.println("  QingYun ESP32-S3-CAM Firmware v1.0.9");
    Serial.println("  MINIMAL TEST - Platform v6.2.0 / Core 2.0.8");
    Serial.println("  DIO + CH343 UART + No USB CDC + No TinyUSB");
    Serial.println("========================================================");
    Serial.println();

    // 打印芯片基本信息
    Serial.println("---- Chip Info ----");
    Serial.printf("  Chip Model: ESP32-S3, Rev %d\n", ESP.getChipRevision());
    Serial.printf("  CPU Freq: %d MHz\n", ESP.getCpuFreqMHz());
    Serial.printf("  Cores: %d\n", ESP.getChipCores());
    Serial.printf("  SDK Version: %s\n", ESP.getSdkVersion());
    Serial.println();

    // 打印 Flash 信息
    Serial.println("---- Flash Info ----");
    Serial.printf("  Flash Size: %u bytes (%.1f MB)\n",
                  ESP.getFlashChipSize(),
                  ESP.getFlashChipSize() / (1024.0f * 1024.0f));
    Serial.printf("  Flash Speed: %u MHz\n", ESP.getFlashChipSpeed());
    Serial.printf("  Flash Mode: %d (0=QIO, 1=QOUT, 2=DIO, 3=DOUT)\n",
                  ESP.getFlashChipMode());
    Serial.println();

    // 打印 PSRAM 信息
    Serial.println("---- PSRAM Info ----");
    size_t psramSize = ESP.getPsramSize();
    size_t freePsram = ESP.getFreePsram();
    Serial.printf("  PSRAM Size: %u bytes (%.1f MB)\n",
                  psramSize, psramSize / (1024.0f * 1024.0f));
    Serial.printf("  Free PSRAM: %u bytes (%.1f MB)\n",
                  freePsram, freePsram / (1024.0f * 1024.0f));
    if (psramSize > 0) {
        Serial.println("  [OK] PSRAM DETECTED");
    } else {
        Serial.println("  [WARN] PSRAM NOT DETECTED (expected with BOARD_HAS_PSRAM)");
    }
    Serial.println();

    // 打印 Heap 信息
    Serial.println("---- Heap Info ----");
    Serial.printf("  Free Heap: %u bytes (%.1f KB)\n",
                  ESP.getFreeHeap(), ESP.getFreeHeap() / 1024.0f);
    Serial.printf("  Min Free Heap: %u bytes\n", ESP.getMinFreeHeap());
    Serial.println();

    Serial.println("==========================================");
    Serial.println("  Setup COMPLETE. Entering loop...");
    Serial.println("  Heartbeat every 2s = platform is OK");
    Serial.println("==========================================");
}

// ============================================================================
// loop() - 每 2 秒打印一次心跳
// ============================================================================
void loop()
{
    static int counter = 0;
    counter++;

    Serial.printf("[v1.0.9] Heartbeat #%d | Heap: %u | PSRAM: %u\n",
                  counter,
                  ESP.getFreeHeap(),
                  ESP.getFreePsram());

    delay(2000);
}
