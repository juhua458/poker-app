/**
 * ============================================================================
 * 青云扑克 ESP32-S3-CAM - 极简平台验证固件 (v1.0.7)
 * ============================================================================
 * 
 * v1.0.8 变更：
 *   - 平台改为 espressif32@6.2.0（Arduino core 2.0.8，TinyUSB 最低要求版本）
 *   - USB 模式改为 ARDUINO_USB_MODE=1 + CDC_ON_BOOT=1（匹配 Arduino IDE 默认）
 *   - 移除 PSRAM 配置（-DBOARD_HAS_PSRAM 和 memory_type），避免框架 PSRAM 初始化崩溃
 *   - 保持极简固件：仅 Serial + 系统信息 + 心跳
 * 
 * 预期行为：
 *   - 串口输出芯片信息、PSRAM 大小、心跳计数
 *   - 如果能正常运行，说明平台层 OK，后续逐步恢复功能模块
 *   - 如果崩溃，说明平台配置仍有问题
 */

#include <Arduino.h>

// ============================================================================
// setup() - 仅做串口初始化和系统信息打印
// ============================================================================
void setup()
{
    Serial.begin(115200);
    delay(1000);  // 等待串口就绪

    Serial.println();
    Serial.println("========================================================");
    Serial.println("  QingYun ESP32-S3-CAM Firmware v1.0.8");
    Serial.println("  MINIMAL TEST - Platform v6.2.0 Validation");
    Serial.println("  DIO + HW USB + No PSRAM");
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

    // 打印 PSRAM 信息（关键！验证 dio_opi 配置是否生效）
    Serial.println("---- PSRAM Info ----");
    size_t psramSize = ESP.getPsramSize();
    size_t freePsram = ESP.getFreePsram();
    Serial.printf("  PSRAM Size: %u bytes (%.1f MB)\n",
                  psramSize, psramSize / (1024.0f * 1024.0f));
    Serial.printf("  Free PSRAM: %u bytes (%.1f MB)\n",
                  freePsram, freePsram / (1024.0f * 1024.0f));
    if (psramSize > 0) {
        Serial.println("  PSRAM: DETECTED OK");
    } else {
        Serial.println("  PSRAM: NOT DETECTED (config error?)");
    }
    Serial.println();

    // 打印 Heap 信息
    Serial.println("---- Heap Info ----");
    Serial.printf("  Free Heap: %u bytes (%.1f KB)\n",
                  ESP.getFreeHeap(), ESP.getFreeHeap() / 1024.0f);
    Serial.printf("  Min Free Heap: %u bytes\n", ESP.getMinFreeHeap());
    Serial.println();

    Serial.println("==========================================");
    Serial.println("  Setup complete. Entering loop...");
    Serial.println("  If you see heartbeat messages, platform is OK");
    Serial.println("==========================================");
}

// ============================================================================
// loop() - 每 2 秒打印一次心跳
// ============================================================================
void loop()
{
    static int counter = 0;
    counter++;

    Serial.printf("[v1.0.8] Heartbeat #%d | Heap: %u | PSRAM: %u\n",
                  counter,
                  ESP.getFreeHeap(),
                  ESP.getFreePsram());

    delay(2000);
}
