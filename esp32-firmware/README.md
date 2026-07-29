# 青云扑克 ESP32-S3-CAM 固件

ESP32-S3 N16R8 + OV5640 固件，实现 USB HID 触摸屏模拟 + WiFi AP 摄像头 HTTP 服务 + OTA 无线更新。

## 硬件要求

| 组件 | 规格 |
|------|------|
| 主控 | ESP32-S3 N16R8 (16MB Flash, 8MB PSRAM) |
| 摄像头 | OV5640 (500万像素) |
| 接口 | USB-C (USB HID + 供电) |
| 目标手机 | 一加13T (Android 15, 1080×2344) |

## 功能概览

### 1. USB HID 触摸屏模拟
- 通过 USB-C 连接手机，被识别为触摸屏设备
- HID 描述符：Usage Page 0x0D (Digitizer), Usage 0x05 (Touch Pad)
- 绝对坐标模式，Logical Maximum = 32767
- 单点触摸：Contact ID + Tip Switch + X + Y + Contact Count
- 完整触摸序列：DOWN → MOVE → UP
- 坐标映射：手机屏幕 1080×2344 → HID 坐标 0-32767

### 2. WiFi AP + HTTP 摄像头服务
- ESP32 创建 WiFi 热点
  - SSID: `QingYun-ESP32`
  - 密码: `poker12345`
  - 静态 IP: `192.168.4.1`
- HTTP 端点 (见下方 API 文档)

### 3. 行为随机化 (防检测)
- 坐标高斯偏移：每次点击添加 ±3-8 像素随机偏移
- 点击间隔抖动：±50-200ms
- 触摸持续时间随机化：基础时间 ±10-30ms
- 所有参数可通过 API 动态调整

### 4. OTA 无线更新
- HTTP POST 流式上传新固件
- MD5 校验确保完整性
- 失败自动回滚 (ESP-IDF OTA 双分区机制)

### 5. 安全与稳定性
- 看门狗定时器 (30秒超时)
- USB 断连/重连监控
- 坐标越界保护
- PSRAM 帧缓冲区自动回收
- JSON 请求体容错解析

## 项目结构

```
firmware/
├── src/
│   ├── main.cpp                # 主入口，初始化各模块
│   ├── usb_hid_touchpad.cpp    # USB HID 触摸屏实现
│   ├── usb_hid_touchpad.h
│   ├── wifi_ap_server.cpp      # WiFi AP + HTTP 服务
│   ├── wifi_ap_server.h
│   ├── camera_driver.cpp       # OV5640 摄像头驱动
│   ├── camera_driver.h
│   ├── ota_updater.cpp         # OTA 更新模块
│   ├── ota_updater.h
│   ├── behavior_randomizer.cpp # 行为随机化
│   └── behavior_randomizer.h
├── partitions.csv              # 自定义分区表 (16MB, OTA 双分区)
├── platformio.ini              # PlatformIO 配置
├── verify_firmware.py          # 多轮自检脚本
└── README.md                   # 本文件
```

## 编译步骤

### 环境准备

1. 安装 [PlatformIO](https://platformio.org/)
2. 安装 VSCode + PlatformIO IDE 插件（推荐）

### 首次编译

```bash
# 克隆项目
cd firmware

# 编译
pio run

# 编译并上传 (通过 UART)
pio run -t upload

# 串口监控
pio device monitor
```

### 烧录注意事项

1. **首次烧录**必须通过 UART（非 USB-CDC），因为 USB 口被配置为 HID 设备
2. ESP32-S3 的 UART0 引脚为 GPIO43(TX) 和 GPIO44(RX)
3. 编译成功后使用 `pio run -t upload` 上传
4. 后续更新可通过 WiFi 的 `/ota` 端点无线完成

### 修改摄像头引脚

如果您的 ESP32-S3-CAM 板引脚不同，在 `platformio.ini` 的 `build_flags` 中修改：

```ini
build_flags =
    -DCAM_PIN_XCLK=15
    -DCAM_PIN_SIOD=4
    -DCAM_PIN_SIOC=5
    ; ... 其他引脚
```

### 修改 PSRAM 类型

- WROOM-1 模块 (1.8V OPI PSRAM)：`board_build.arduino.memory_type = qio_opi`
- WROOM-2 模块 (3.3V QSPI PSRAM)：`board_build.arduino.memory_type = qio_qspi`

## API 文档

所有端点基础 URL: `http://192.168.4.1`

### GET /capture

获取摄像头 JPEG 画面。

**响应**: `image/jpeg`

**示例**:
```bash
curl http://192.168.4.1/capture --output frame.jpg
```

### GET /status

获取设备状态 JSON。

**响应**: `application/json`
```json
{
  "device": "QingYun-ESP32-CAM",
  "version": "1.0.0",
  "usb_hid": { "mounted": true },
  "wifi": { "ap_ssid": "QingYun-ESP32", "ap_ip": "192.168.4.1", "clients": 1 },
  "camera": { "initialized": true, "frame_size": "SVGA (800x600)" },
  "memory": { "free_heap": 234567, "free_psram": 7890123 },
  "ota": { "partition": "ota_0", "updating": false },
  "randomizer": { "enabled": true, "coord_sigma": 3.0 },
  "uptime_ms": 12345
}
```

### POST /tap

执行点击操作。

**请求体**:
```json
{
  "x": 540,        // 屏幕X坐标 (0-1079)
  "y": 1172,       // 屏幕Y坐标 (0-2343)
  "duration": 50   // 可选，触摸持续时间ms (默认50，范围10-5000)
}
```

**响应**:
```json
{
  "success": true,
  "message": "Tap executed: screen(540,1172)→rand(543,1175) dur=50→58ms"
}
```

**示例**:
```bash
curl -X POST http://192.168.4.1/tap \
  -H "Content-Type: application/json" \
  -d '{"x":540,"y":1172,"duration":50}'
```

### POST /swipe

执行滑动操作。

**请求体**:
```json
{
  "x1": 100,       // 起始X坐标 (0-1079)
  "y1": 500,       // 起始Y坐标 (0-2343)
  "x2": 500,       // 终止X坐标 (0-1079)
  "y2": 500,       // 终止Y坐标 (0-2343)
  "duration": 300  // 可选，滑动时间ms (默认300，范围50-10000)
}
```

**示例**:
```bash
curl -X POST http://192.168.4.1/swipe \
  -H "Content-Type: application/json" \
  -d '{"x1":540,"y1":1800,"x2":540,"y2":500,"duration":300}'
```

### POST /ota

OTA 固件更新。流式上传，支持大文件。

**请求头**:
- `Content-Type: application/octet-stream`
- `X-MD5`: 固件 MD5 校验和 (可选，十六进制字符串)

**请求体**: 固件二进制数据

**示例**:
```bash
# 生成 MD5
md5sum .pio/build/esp32s3-n16r8/firmware.bin

# 上传固件
curl -X POST http://192.168.4.1/ota \
  -H "Content-Type: application/octet-stream" \
  -H "X-MD5: <md5_hex>" \
  --data-binary @firmware.bin
```

### GET /config

获取行为随机化配置。

**响应**:
```json
{
  "enabled": true,
  "coordOffsetMin": -8,
  "coordOffsetMax": 8,
  "coordOffsetSigma": 3.0,
  "timingJitterMin": -50,
  "timingJitterMax": 200,
  "durationJitterMin": -10,
  "durationJitterMax": 30,
  "swipeStepJitterMin": -2,
  "swipeStepJitterMax": 5
}
```

### POST /config

更新行为随机化配置。只需发送需要修改的字段。

**请求体**:
```json
{
  "enabled": false,          // 关闭随机化
  "coordOffsetSigma": 5.0   // 增大偏移标准差
}
```

**示例**:
```bash
curl -X POST http://192.168.4.1/config \
  -H "Content-Type: application/json" \
  -d '{"coordOffsetSigma":5.0}'
```

## 分区表

| 分区 | 偏移 | 大小 | 用途 |
|------|------|------|------|
| nvs | 0x9000 | 16KB | 非易失性存储 |
| otadata | 0xD000 | 8KB | OTA 数据 |
| phy_init | 0xF000 | 4KB | PHY 初始化 |
| app0 (ota_0) | 0x10000 | ~3.9MB | 应用分区 A |
| app1 (ota_1) | 0x400000 | ~3.9MB | 应用分区 B |
| spiffs | 0x7F0000 | ~8.1MB | 文件系统 |

## HID 报告格式

触摸报告 (7 bytes):

| 偏移 | 字段 | 大小 | 说明 |
|------|------|------|------|
| 0 | contact_id | 8 bits | 触点标识 (固定 0) |
| 1 | tip_switch | 1 bit | 1=按下, 0=抬起 |
| 1 | padding | 7 bits | 填充 (全 0) |
| 2-3 | x | 16 bits | X 坐标 (0-32767, LE) |
| 4-5 | y | 16 bits | Y 坐标 (0-32767, LE) |
| 6 | contact_count | 8 bits | 活跃触点数 (0 或 1) |

坐标转换公式：
- `hid_x = screen_x * 32767 / 1079`
- `hid_y = screen_y * 32767 / 2343`

## 自检报告

运行 `python3 verify_firmware.py` 执行四轮自检：

| 轮次 | 内容 | 通过 | 失败 | 警告 |
|------|------|------|------|------|
| 1 | 语法和 HID 描述符 | 54 | 0 | 1 |
| 2 | 逻辑、坐标、状态机 | 12 | 0 | 0 |
| 3 | 内存、边界、容错 | 19 | 0 | 2 |
| 4 | PlatformIO 配置 | 33 | 0 | 0 |
| **总计** | | **118** | **0** | **3** |

3 个警告项：
1. TOUCH_REPORT_SIZE 常量在头文件中定义（测试脚本搜索范围有限，实际正确）
2. 摄像头帧缓冲区泄漏误报（错误路径已有 returnFrame 调用）
3. WiFi AP 模式无事件处理器（AP 模式自动恢复，无需额外处理）

## 故障排除

| 问题 | 解决方案 |
|------|---------|
| 编译失败：找不到 Adafruit_TinyUSB | 运行 `pio pkg install` 安装依赖 |
| USB 设备未被手机识别 | 确认 build_flags 中 `ARDUINO_USB_MODE=0` |
| 摄像头初始化失败 | 检查引脚定义是否匹配实际板子 |
| OTA 更新失败 | 检查固件大小不超过 3.9MB，确认 MD5 匹配 |
| WiFi 连不上 | 确认密码正确 (poker12345)，设备距离足够近 |
| 触摸坐标偏移 | 检查目标手机屏幕分辨率是否为 1080×2344 |

## 许可证

私有项目，仅供青云扑克项目使用。
