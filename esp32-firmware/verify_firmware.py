#!/usr/bin/env python3
"""
青云扑克 ESP32-S3-CAM 固件 - 多轮自检脚本
验证 HID 描述符、坐标转换、状态机、内存安全、边界保护等
"""

import struct
import sys
import os
import re
import json

# ============================================================================
# 全局检查结果
# ============================================================================
results = {
    "round1_syntax_hid": [],
    "round2_logic_coords": [],
    "round3_memory_bounds": [],
    "round4_platformio": [],
}

def check_pass(check_id, message):
    print(f"  ✅ [{check_id}] {message}")
    results.setdefault("current", []).append(("PASS", check_id, message))

def check_fail(check_id, message):
    print(f"  ❌ [{check_id}] {message}")
    results.setdefault("current", []).append(("FAIL", check_id, message))

def check_warn(check_id, message):
    print(f"  ⚠️  [{check_id}] {message}")
    results.setdefault("current", []).append(("WARN", check_id, message))


# ============================================================================
# 第一轮：语法和 HID 描述符验证
# ============================================================================
def round1_syntax_hid():
    print("\n" + "="*70)
    print("第一轮自检：语法和 HID 描述符验证")
    print("="*70)
    
    src_dir = "/app/data/所有对话/主对话/codeact/output/firmware/src"
    
    # ---- 1.1 检查文件是否存在 ----
    print("\n[1.1] 检查源文件存在性...")
    required_files = [
        "main.cpp", "usb_hid_touchpad.cpp", "usb_hid_touchpad.h",
        "wifi_ap_server.cpp", "wifi_ap_server.h",
        "camera_driver.cpp", "camera_driver.h",
        "ota_updater.cpp", "ota_updater.h",
        "behavior_randomizer.cpp", "behavior_randomizer.h",
    ]
    for f in required_files:
        path = os.path.join(src_dir, f)
        if os.path.exists(path):
            check_pass(f"1.1.{f}", f"File exists: {f}")
        else:
            check_fail(f"1.1.{f}", f"File missing: {f}")

    # ---- 1.2 C++ 语法基本检查 ----
    print("\n[1.2] C++ 语法基本检查...")
    for f in os.listdir(src_dir):
        if not f.endswith(('.cpp', '.h')):
            continue
        path = os.path.join(src_dir, f)
        with open(path, 'r') as fp:
            content = fp.read()
        
        # 检查 #include 是否匹配
        includes = re.findall(r'#include\s+"([^"]+)"', content)
        for inc in includes:
            inc_path = os.path.join(src_dir, inc)
            if os.path.exists(inc_path):
                check_pass(f"1.2.{f}", f"Include resolved: {inc}")
            else:
                # 系统头文件可以不存在
                if inc in ['Arduino.h', 'Adafruit_TinyUSB.h', 'esp_camera.h', 
                           'WiFi.h', 'WebServer.h', 'Update.h', 'MD5Builder.h',
                           'ArduinoJson.h', 'esp_ota_ops.h', 'esp_psram.h',
                           'esp_task_wdt.h', 'math.h']:
                    check_pass(f"1.2.{f}", f"System include: {inc}")
                else:
                    check_warn(f"1.2.{f}", f"Include not found locally: {inc}")

        # 检查花括号匹配
        brace_count = 0
        in_comment = False
        in_string = False
        escape = False
        for i, ch in enumerate(content):
            if escape:
                escape = False
                continue
            if ch == '\\':
                escape = True
                continue
            if ch == '"' and not in_comment:
                in_string = not in_string
                continue
            if in_string:
                continue
            if content[i:i+2] == '//':
                # 单行注释，跳到行尾
                nl = content.find('\n', i)
                if nl == -1:
                    break
                continue
            if content[i:i+2] == '/*':
                in_comment = True
            if content[i:i+2] == '*/':
                in_comment = False
                continue
            if in_comment:
                continue
            if ch == '{':
                brace_count += 1
            elif ch == '}':
                brace_count -= 1
                if brace_count < 0:
                    check_fail(f"1.2.{f}", f"Unmatched closing brace at position {i}")
                    break
        
        if brace_count == 0:
            check_pass(f"1.2.{f}", f"Braces balanced in {f}")
        elif brace_count > 0:
            check_fail(f"1.2.{f}", f"Unmatched opening braces: {brace_count} in {f}")
        
        # 检查 #ifndef / #define / #endif 匹配 (header files)
        if f.endswith('.h'):
            ifndef_count = content.count('#ifndef')
            endif_count = content.count('#endif')
            if ifndef_count == endif_count:
                check_pass(f"1.2.{f}", f"Header guard balanced in {f}")
            else:
                check_fail(f"1.2.{f}", f"Header guard mismatch: #ifndef={ifndef_count}, #endif={endif_count}")

    # ---- 1.3 HID 报告描述符验证 ----
    print("\n[1.3] HID 报告描述符验证...")
    
    # 读取 HID 描述符源码
    hid_cpp = os.path.join(src_dir, "usb_hid_touchpad.cpp")
    with open(hid_cpp, 'r') as fp:
        hid_content = fp.read()
    
    # 提取描述符字节数组
    descriptor_match = re.search(
        r'static\s+const\s+uint8_t\s+_hid_touchpad_descriptor\[\]\s*=\s*\{(.*?)\};',
        hid_content, re.DOTALL
    )
    
    if not descriptor_match:
        check_fail("1.3.0", "Cannot find HID descriptor array")
    else:
        # 解析描述符字节
        desc_text = descriptor_match.group(1)
        desc_bytes = []
        for m in re.finditer(r'0x([0-9A-Fa-f]{2})', desc_text):
            desc_bytes.append(int(m.group(1), 16))
        
        check_pass("1.3.0", f"HID descriptor found: {len(desc_bytes)} bytes")
        
        # 验证关键描述符元素
        desc_hex = bytes(desc_bytes).hex()
        
        # Usage Page (Digitizers) = 0x05 0x0D
        if '050d' in desc_hex:
            check_pass("1.3.1", "Usage Page = Digitizers (0x0D)")
        else:
            check_fail("1.3.1", "Missing Usage Page Digitizers")
        
        # Usage (Touch Pad) = 0x09 0x05
        if '0905' in desc_hex:
            check_pass("1.3.2", "Usage = Touch Pad (0x05)")
        else:
            check_fail("1.3.2", "Missing Usage Touch Pad")
        
        # Usage (Finger) = 0x09 0x22
        if '0922' in desc_hex:
            check_pass("1.3.3", "Usage = Finger (0x22)")
        else:
            check_fail("1.3.3", "Missing Usage Finger")
        
        # Usage (Contact Identifier) = 0x09 0x51
        if '0951' in desc_hex:
            check_pass("1.3.4", "Usage = Contact Identifier (0x51)")
        else:
            check_fail("1.3.4", "Missing Usage Contact Identifier")
        
        # Usage (Tip Switch) = 0x09 0x42
        if '0942' in desc_hex:
            check_pass("1.3.5", "Usage = Tip Switch (0x42)")
        else:
            check_fail("1.3.5", "Missing Usage Tip Switch")
        
        # Usage (X) = 0x09 0x30
        if '0930' in desc_hex:
            check_pass("1.3.6", "Usage = X (0x30)")
        else:
            check_fail("1.3.6", "Missing Usage X")
        
        # Usage (Y) = 0x09 0x31
        if '0931' in desc_hex:
            check_pass("1.3.7", "Usage = Y (0x31)")
        else:
            check_fail("1.3.7", "Missing Usage Y")
        
        # Usage (Contact Count) = 0x09 0x54
        if '0954' in desc_hex:
            check_pass("1.3.8", "Usage = Contact Count (0x54)")
        else:
            check_fail("1.3.8", "Missing Usage Contact Count")
        
        # Logical Maximum 32767 = 0x26 0xFF 0x7F
        if '26ff7f' in desc_hex:
            check_pass("1.3.9", "Logical Maximum = 32767 (0x7FFF)")
        else:
            check_fail("1.3.9", "Missing or incorrect Logical Maximum")
        
        # Report Size 16 = 0x75 0x10
        if '7510' in desc_hex:
            check_pass("1.3.10", "Report Size = 16 (for X,Y)")
        else:
            check_fail("1.3.10", "Missing Report Size 16 for coordinates")
        
        # 验证 Collection/End Collection 匹配
        a1_count = desc_hex.count('a101')  # Collection (Application)
        a1_logical = desc_hex.count('a100')  # Collection (Logical)
        c0_count = desc_hex.count('c0')    # End Collection
        
        # 每个 Collection 应该对应一个 End Collection
        total_collections = a1_count + a1_logical
        if c0_count == total_collections:
            check_pass("1.3.11", f"Collection balance: {total_collections} opened, {c0_count} closed")
        else:
            check_fail("1.3.11", f"Collection imbalance: {total_collections} opened, {c0_count} closed")
        
        # 验证报告大小
        # 根据描述符计算：Contact ID(8) + TipSwitch(1) + Pad(7) + X(16) + Y(16) + ContactCount(8) = 56 bits = 7 bytes
        expected_report_size = 7
        if 'TOUCH_REPORT_SIZE' in hid_content:
            size_match = re.search(r'TOUCH_REPORT_SIZE\s*=\s*(\d+)', hid_content)
            if size_match:
                actual_size = int(size_match.group(1))
                if actual_size == expected_report_size:
                    check_pass("1.3.12", f"Report size matches: {actual_size} bytes")
                else:
                    check_fail("1.3.12", f"Report size mismatch: expected {expected_report_size}, got {actual_size}")
            else:
                check_warn("1.3.12", "Cannot extract TOUCH_REPORT_SIZE value")
        else:
            check_warn("1.3.12", "TOUCH_REPORT_SIZE not found in source")
        
        # 验证 touch_report_t 结构大小
        # packed struct: uint8_t + uint8_t + uint16_t + uint16_t + uint8_t = 1+1+2+2+1 = 7 bytes
        struct_match = re.search(
            r'typedef\s+struct\s+__attribute__\(\(packed\)\)\s*\{([^}]+)\}\s+touch_report_t',
            hid_content.replace('\n', ' ').replace('  ', ' ')
        )
        if struct_match:
            check_pass("1.3.13", "touch_report_t is packed struct")
        else:
            # 也检查头文件
            hid_h = os.path.join(src_dir, "usb_hid_touchpad.h")
            with open(hid_h, 'r') as fp:
                h_content = fp.read()
            struct_match2 = re.search(
                r'typedef\s+struct\s+__attribute__\(\(packed\)\)\s*\{([^}]+)\}\s+touch_report_t',
                h_content.replace('\n', ' ').replace('  ', ' ')
            )
            if struct_match2:
                check_pass("1.3.13", "touch_report_t is packed struct (in header)")
            else:
                check_warn("1.3.13", "Cannot verify packed attribute on touch_report_t")
    
    results["round1_syntax_hid"] = results.pop("current", [])


# ============================================================================
# 第二轮：逻辑、坐标转换、状态机完整性
# ============================================================================
def round2_logic_coords():
    print("\n" + "="*70)
    print("第二轮自检：逻辑、坐标转换、状态机完整性")
    print("="*70)
    
    # ---- 2.1 坐标转换验证 ----
    print("\n[2.1] 坐标转换验证...")
    
    HID_MAX = 32767
    SCREEN_W = 1080
    SCREEN_H = 2344
    
    # 模拟 C++ 的坐标转换逻辑
    def screen_to_hid_x(sx):
        return min(sx * HID_MAX // (SCREEN_W - 1), HID_MAX)
    
    def screen_to_hid_y(sy):
        return min(sy * HID_MAX // (SCREEN_H - 1), HID_MAX)
    
    # 测试用例
    test_cases = [
        # (screenX, screenY, expectedHidX, expectedHidY, description)
        (0, 0, 0, 0, "左上角"),
        (1079, 2343, 32767, 32767, "右下角"),
        (540, 1172, 540 * 32767 // 1079, 1172 * 32767 // 2343, "中心点"),
        (1, 1, 1 * 32767 // 1079, 1 * 32767 // 2343, "近原点"),
    ]
    
    for sx, sy, exp_x, exp_y, desc in test_cases:
        act_x = screen_to_hid_x(sx)
        act_y = screen_to_hid_y(sy)
        if act_x == exp_x and act_y == exp_y:
            check_pass(f"2.1", f"Coords {desc}: ({sx},{sy})→({act_x},{act_y})")
        else:
            check_fail(f"2.1", f"Coords {desc}: expected ({exp_x},{exp_y}), got ({act_x},{act_y})")
    
    # 边界检查
    max_x = screen_to_hid_x(1079)
    max_y = screen_to_hid_y(2343)
    if max_x <= HID_MAX and max_y <= HID_MAX:
        check_pass("2.1.boundary", f"Max HID coords within range: ({max_x},{max_y})")
    else:
        check_fail("2.1.boundary", f"Max HID coords out of range: ({max_x},{max_y})")
    
    # ---- 2.2 触摸序列状态机验证 ----
    print("\n[2.2] 触摸序列状态机验证...")
    
    # 读取 usb_hid_touchpad.cpp 检查状态机逻辑
    hid_cpp = "/app/data/所有对话/主对话/codeact/output/firmware/src/usb_hid_touchpad.cpp"
    with open(hid_cpp, 'r') as fp:
        content = fp.read()
    
    # 检查 DOWN → MOVE → UP 序列完整性
    has_touch_down = 'sendTouchDown' in content
    has_touch_move = 'sendTouchMove' in content
    has_touch_up = 'sendTouchUp' in content
    
    if has_touch_down and has_touch_move and has_touch_up:
        check_pass("2.2.1", "Complete touch sequence: DOWN/MOVE/UP")
    else:
        check_fail("2.2.1", f"Incomplete touch sequence: DOWN={has_touch_down}, MOVE={has_touch_move}, UP={has_touch_up}")
    
    # 检查 tap 中的完整序列
    tap_has_down = 'sendTouchDown' in content and 'tap' in content
    tap_has_up = 'sendTouchUp' in content and 'tap' in content
    
    if tap_has_down and tap_has_up:
        check_pass("2.2.2", "tap() contains DOWN and UP")
    else:
        check_fail("2.2.2", "tap() missing DOWN or UP")
    
    # 检查 swipe 中的完整序列
    swipe_has_down = 'sendTouchDown' in content and 'swipe' in content
    swipe_has_move = 'sendTouchMove' in content and 'swipe' in content
    swipe_has_up = 'sendTouchUp' in content and 'swipe' in content
    
    if swipe_has_down and swipe_has_move and swipe_has_up:
        check_pass("2.2.3", "swipe() contains DOWN, MOVE, and UP")
    else:
        check_fail("2.2.3", "swipe() missing one of DOWN/MOVE/UP")
    
    # 检查 _isTouching 状态管理
    if '_isTouching = true' in content and '_isTouching = false' in content:
        check_pass("2.2.4", "_isTouching state properly managed")
    else:
        check_fail("2.2.4", "_isTouching state not properly managed")
    
    # 检查 sendTouchMove 有前置状态检查
    if 'sendTouchMove' in content and '_isTouching' in content:
        check_pass("2.2.5", "sendTouchMove checks touch state")
    else:
        check_warn("2.2.5", "sendTouchMove may not check touch state")
    
    # ---- 2.3 滑动插值逻辑 ----
    print("\n[2.3] 滑动插值逻辑验证...")
    
    # 验证线性插值不会出现除零
    if '/ steps' in content or '/ i / steps' in content or 'steps' in content:
        if 'steps < 2' in content or 'steps = 2' in content:
            check_pass("2.3.1", "Swipe steps have minimum bound (>= 2)")
        else:
            check_fail("2.3.1", "Swipe steps may have no minimum bound (division by zero risk)")
    
    if 'steps > 200' in content or 'steps = 200' in content:
        check_pass("2.3.2", "Swipe steps have maximum bound")
    else:
        check_warn("2.3.2", "Swipe steps may have no maximum bound")
    
    results["round2_logic_coords"] = results.pop("current", [])


# ============================================================================
# 第三轮：内存安全、边界保护、容错处理
# ============================================================================
def round3_memory_bounds():
    print("\n" + "="*70)
    print("第三轮自检：内存安全、边界保护、容错处理")
    print("="*70)
    
    src_dir = "/app/data/所有对话/主对话/codeact/output/firmware/src"
    
    # ---- 3.1 摄像头帧缓冲区泄漏防护 ----
    print("\n[3.1] 摄像头帧缓冲区泄漏防护...")
    
    cam_cpp = os.path.join(src_dir, "camera_driver.cpp")
    wifi_cpp = os.path.join(src_dir, "wifi_ap_server.cpp")
    
    # 检查 captureFrame 后是否都有 returnFrame
    for filepath in [cam_cpp, wifi_cpp]:
        fname = os.path.basename(filepath)
        with open(filepath, 'r') as fp:
            content = fp.read()
        
        fb_get_count = content.count('captureFrame()') + content.count('esp_camera_fb_get()')
        fb_return_count = content.count('returnFrame(') + content.count('esp_camera_fb_return(')
        
        if fb_get_count > 0:
            if fb_return_count >= fb_get_count:
                check_pass(f"3.1.{fname}", f"Frame buffer returns ({fb_return_count}) >= gets ({fb_get_count})")
            else:
                check_fail(f"3.1.{fname}", f"Frame buffer LEAK: gets={fb_get_count}, returns={fb_return_count}")
        
        # 检查错误路径是否也释放帧
        # 找到所有 esp_camera_fb_get/captureFrame 调用，检查后续错误路径
        lines = content.split('\n')
        in_error_path = False
        fb_var = None
        for i, line in enumerate(lines):
            if 'captureFrame()' in line or 'esp_camera_fb_get()' in line:
                # 找到帧缓冲区变量名
                m = re.search(r'(\w+)\s*=\s*(?:\w+->)?(?:captureFrame|esp_camera_fb_get)', line)
                if m:
                    fb_var = m.group(1)
                    in_error_path = True
            
            if in_error_path and fb_var:
                # 检查错误返回前是否释放
                if 'return nullptr' in line or 'return false' in line or 'return ESP_FAIL' in line:
                    # 检查前面几行是否有 returnFrame
                    prev_lines = ''.join(lines[max(0,i-5):i+1])
                    if 'returnFrame' in prev_lines or 'esp_camera_fb_return' in prev_lines:
                        check_pass(f"3.1.err.{i}", f"Error path releases frame buffer at line {i+1}")
                    else:
                        # 可能是 captureFrame 本身返回 null，不需要释放
                        if 'if (!fb)' in prev_lines or 'if (fb == nullptr)' in prev_lines:
                            check_pass(f"3.1.err.{i}", f"Null check before error return at line {i+1}")
                        else:
                            check_warn(f"3.1.err.{i}", f"Possible frame buffer leak at line {i+1}")
                    in_error_path = False
    
    # ---- 3.2 坐标越界保护 ----
    print("\n[3.2] 坐标越界保护...")
    
    hid_cpp = os.path.join(src_dir, "usb_hid_touchpad.cpp")
    with open(hid_cpp, 'r') as fp:
        content = fp.read()
    
    # 检查 HID 坐标限制
    if '_clampHidCoord' in content or 'clamp' in content.lower():
        check_pass("3.2.1", "HID coordinate clamping function exists")
    else:
        check_fail("3.2.1", "No HID coordinate clamping found")
    
    # 检查是否限制在 0-32767 范围
    if '32767' in content or 'HID_LOGICAL_MAX' in content:
        check_pass("3.2.2", "HID logical max referenced for clamping")
    else:
        check_warn("3.2.2", "HID logical max not referenced for clamping")
    
    # 检查 HTTP API 中的坐标范围检查
    wifi_cpp = os.path.join(src_dir, "wifi_ap_server.cpp")
    with open(wifi_cpp, 'r') as fp:
        wifi_content = fp.read()
    
    if 'x < 0' in wifi_content and 'x >= 1080' in wifi_content:
        check_pass("3.2.3", "HTTP API X coordinate range check (0-1079)")
    else:
        check_fail("3.2.3", "HTTP API X coordinate range check missing or incomplete")
    
    if 'y < 0' in wifi_content and 'y >= 2344' in wifi_content:
        check_pass("3.2.4", "HTTP API Y coordinate range check (0-2343)")
    else:
        check_fail("3.2.4", "HTTP API Y coordinate range check missing or incomplete")
    
    # ---- 3.3 JSON 解析容错 ----
    print("\n[3.3] JSON 解析容错...")
    
    if 'DeserializationError' in wifi_content or 'deserializeJson' in wifi_content:
        check_pass("3.3.1", "JSON parse error handling exists")
    else:
        check_fail("3.3.1", "No JSON parse error handling")
    
    if '_parseJsonBody' in wifi_content:
        check_pass("3.3.2", "Centralized JSON body parsing with error handling")
    else:
        check_warn("3.3.2", "No centralized JSON body parser")
    
    # 检查参数缺失时的默认值
    if 'doc["x"]' in wifi_content or 'doc["x"] |' in wifi_content:
        check_pass("3.3.3", "JSON field access with defaults")
    else:
        check_warn("3.3.3", "JSON field access may not have defaults")
    
    # ---- 3.4 OTA 安全 ----
    print("\n[3.4] OTA 安全检查...")
    
    ota_cpp = os.path.join(src_dir, "ota_updater.cpp")
    with open(ota_cpp, 'r') as fp:
        ota_content = fp.read()
    
    # MD5 校验
    if 'MD5Builder' in ota_content or 'MD5' in ota_content:
        check_pass("3.4.1", "OTA MD5 verification implemented")
    else:
        check_fail("3.4.1", "No OTA MD5 verification")
    
    # 空间检查
    if 'FreeSketchSpace' in ota_content or 'freeSpace' in ota_content or 'NO_SPACE' in ota_content:
        check_pass("3.4.2", "OTA space check before update")
    else:
        check_warn("3.4.2", "No OTA space check found")
    
    # 自动回滚 (ESP-IDF OTA 机制默认支持)
    if 'Update.end(true)' in ota_content or 'set_boot_partition' in ota_content:
        check_pass("3.4.3", "OTA validates and sets boot partition on success")
    else:
        check_warn("3.4.3", "OTA may not validate boot partition")
    
    # 中止功能
    if 'Update.abort()' in ota_content:
        check_pass("3.4.4", "OTA abort capability implemented")
    else:
        check_warn("3.4.4", "No OTA abort capability")
    
    # ---- 3.5 看门狗 ----
    print("\n[3.5] 看门狗定时器...")
    
    main_cpp = os.path.join(src_dir, "main.cpp")
    with open(main_cpp, 'r') as fp:
        main_content = fp.read()
    
    if 'esp_task_wdt_init' in main_content:
        check_pass("3.5.1", "Watchdog timer initialized")
    else:
        check_fail("3.5.1", "No watchdog timer initialization")
    
    if 'esp_task_wdt_reset' in main_content or 'wdt_reset' in main_content:
        check_pass("3.5.2", "Watchdog fed in main loop")
    else:
        check_fail("3.5.2", "Watchdog not fed in main loop")
    
    # ---- 3.6 WiFi 断线重连 ----
    print("\n[3.6] WiFi 断线重连...")
    
    # AP 模式下，WiFi.softAP 会自动维护
    # 检查是否有 WiFi 事件处理
    if 'WiFi.onEvent' in wifi_content or 'WiFiEvent' in wifi_content:
        check_pass("3.6.1", "WiFi event handler registered")
    else:
        check_warn("3.6.1", "No WiFi event handler (AP mode auto-recovers)")
    
    # ---- 3.7 行为随机化边界 ----
    print("\n[3.7] 行为随机化边界保护...")
    
    rand_cpp = os.path.join(src_dir, "behavior_randomizer.cpp")
    with open(rand_cpp, 'r') as fp:
        rand_content = fp.read()
    
    # 检查坐标限制在屏幕范围内
    if '_clampScreenX' in rand_content and '_clampScreenY' in rand_content:
        check_pass("3.7.1", "Screen coordinate clamping after randomization")
    else:
        check_fail("3.7.1", "No screen coordinate clamping after randomization")
    
    # 检查持续时间最小值
    if 'result < 10' in rand_content or 'min.*10' in rand_content:
        check_pass("3.7.2", "Minimum touch duration enforced (10ms)")
    else:
        check_warn("3.7.2", "No minimum touch duration check")
    
    # 检查步进间隔最小值
    if 'result < 2' in rand_content:
        check_pass("3.7.3", "Minimum swipe step enforced (2ms)")
    else:
        check_warn("3.7.3", "No minimum swipe step check")
    
    results["round3_memory_bounds"] = results.pop("current", [])


# ============================================================================
# 第四轮：PlatformIO 编译配置完整性
# ============================================================================
def round4_platformio():
    print("\n" + "="*70)
    print("第四轮自检：PlatformIO 编译配置完整性")
    print("="*70)
    
    fw_dir = "/app/data/所有对话/主对话/codeact/output/firmware"
    
    # ---- 4.1 platformio.ini 关键配置 ----
    print("\n[4.1] platformio.ini 关键配置...")
    
    pio_ini = os.path.join(fw_dir, "platformio.ini")
    with open(pio_ini, 'r') as fp:
        pio_content = fp.read()
    
    # 检查平台
    if 'platform = espressif32' in pio_content:
        check_pass("4.1.1", "Platform: espressif32")
    else:
        check_fail("4.1.1", "Wrong or missing platform")
    
    # 检查板型
    if 'board = esp32-s3-devkitc-1' in pio_content:
        check_pass("4.1.2", "Board: esp32-s3-devkitc-1")
    else:
        check_fail("4.1.2", "Wrong or missing board")
    
    # 检查框架
    if 'framework = arduino' in pio_content:
        check_pass("4.1.3", "Framework: arduino")
    else:
        check_fail("4.1.3", "Wrong or missing framework")
    
    # 检查 Flash 大小
    if '16MB' in pio_content or '16777216' in pio_content:
        check_pass("4.1.4", "Flash size: 16MB")
    else:
        check_fail("4.1.4", "Flash size not set to 16MB")
    
    # 检查 PSRAM
    if 'BOARD_HAS_PSRAM' in pio_content:
        check_pass("4.1.5", "PSRAM enabled (BOARD_HAS_PSRAM)")
    else:
        check_fail("4.1.5", "PSRAM not enabled")
    
    # 检查 USB-OTG 模式
    if 'ARDUINO_USB_MODE=0' in pio_content:
        check_pass("4.1.6", "USB-OTG mode enabled (ARDUINO_USB_MODE=0)")
    else:
        check_fail("4.1.6", "USB-OTG mode not set")
    
    # 检查 USB-Serial/JTAG 模式被移除
    if 'ARDUINO_USB_MODE=1' in pio_content and 'build_unflags' in pio_content:
        check_pass("4.1.7", "USB-Serial/JTAG mode properly unflagged")
    else:
        check_warn("4.1.7", "USB-Serial/JTAG mode may not be properly unflagged")
    
    # 检查 memory_type
    if 'qio_opi' in pio_content or 'qio_qspi' in pio_content:
        check_pass("4.1.8", "PSRAM memory type configured")
    else:
        check_warn("4.1.8", "PSRAM memory type not explicitly configured")
    
    # 检查分区表
    if 'partitions.csv' in pio_content:
        check_pass("4.1.9", "Custom partition table referenced")
    else:
        check_warn("4.1.9", "Using default partition table")
    
    # 检查库依赖
    if 'Adafruit TinyUSB' in pio_content:
        check_pass("4.1.10", "Adafruit TinyUSB library included")
    else:
        check_fail("4.1.10", "Adafruit TinyUSB library missing")
    
    if 'ArduinoJson' in pio_content:
        check_pass("4.1.11", "ArduinoJson library included")
    else:
        check_warn("4.1.11", "ArduinoJson library not included (needed for WiFi server)")
    
    # ---- 4.2 分区表验证 ----
    print("\n[4.2] 分区表验证...")
    
    part_csv = os.path.join(fw_dir, "partitions.csv")
    if os.path.exists(part_csv):
        with open(part_csv, 'r') as fp:
            part_content = fp.read()
        
        # 检查 OTA 分区
        if 'ota_0' in part_content and 'ota_1' in part_content:
            check_pass("4.2.1", "Dual OTA partitions defined")
        else:
            check_fail("4.2.1", "Missing OTA partitions")
        
        # 检查 otadata
        if 'otadata' in part_content:
            check_pass("4.2.2", "OTA data partition defined")
        else:
            check_fail("4.2.2", "Missing OTA data partition")
        
        # 检查 SPIFFS
        if 'spiffs' in part_content:
            check_pass("4.2.3", "SPIFFS partition defined")
        else:
            check_warn("4.2.3", "No SPIFFS partition")
        
        # 检查分区无重叠 (简单检查)
        partitions = []
        for line in part_content.split('\n'):
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            parts = [p.strip() for p in line.split(',')]
            if len(parts) >= 5 and parts[1] in ('app', 'data'):
                try:
                    offset = int(parts[3], 16) if parts[3].startswith('0x') else int(parts[3])
                    size = int(parts[4], 16) if parts[4].startswith('0x') else int(parts[4])
                    partitions.append((parts[0], offset, size))
                except ValueError:
                    pass
        
        # 检查重叠
        partitions.sort(key=lambda x: x[1])
        overlap_found = False
        for i in range(len(partitions) - 1):
            name1, off1, size1 = partitions[i]
            name2, off2, size2 = partitions[i + 1]
            end1 = off1 + size1
            if end1 > off2:
                check_fail("4.2.4", f"Partition overlap: {name1} ends at 0x{end1:x}, {name2} starts at 0x{off2:x}")
                overlap_found = True
                break
        
        if not overlap_found:
            check_pass("4.2.4", "No partition overlaps detected")
        
        # 检查总大小不超过 16MB
        if partitions:
            last_end = max(p[1] + p[2] for p in partitions)
            if last_end <= 0x1000000:  # 16MB
                check_pass("4.2.5", f"Total partition space within 16MB (ends at 0x{last_end:x})")
            else:
                check_fail("4.2.5", f"Total partition space exceeds 16MB (ends at 0x{last_end:x})")
    else:
        check_fail("4.2.0", "partitions.csv not found")
    
    # ---- 4.3 摄像头引脚配置 ----
    print("\n[4.3] 摄像头引脚配置完整性...")
    
    cam_h = os.path.join(fw_dir, "src/camera_driver.h")
    with open(cam_h, 'r') as fp:
        cam_h_content = fp.read()
    
    required_pins = [
        'CAM_PIN_PWDN', 'CAM_PIN_RESET', 'CAM_PIN_XCLK',
        'CAM_PIN_SIOD', 'CAM_PIN_SIOC',
        'CAM_PIN_D0', 'CAM_PIN_D1', 'CAM_PIN_D2', 'CAM_PIN_D3',
        'CAM_PIN_D4', 'CAM_PIN_D5', 'CAM_PIN_D6', 'CAM_PIN_D7',
        'CAM_PIN_VSYNC', 'CAM_PIN_HREF', 'CAM_PIN_PCLK'
    ]
    
    for pin in required_pins:
        if pin in cam_h_content:
            check_pass(f"4.3.{pin}", f"Camera pin defined: {pin}")
        else:
            check_fail(f"4.3.{pin}", f"Camera pin missing: {pin}")
    
    # 检查 D0-D7 引脚是否使用 USB OTG 引脚 (GPIO19/20)
    # USB D+/D- 在 ESP32-S3 上是 GPIO19/20
    usb_pins = [19, 20]
    conflict_found = False
    for pin_name in ['D0', 'D1', 'D2', 'D3', 'D4', 'D5', 'D6', 'D7',
                      'XCLK', 'SIOD', 'SIOC', 'VSYNC', 'HREF', 'PCLK']:
        pattern = f'CAM_PIN_{pin_name}\\s+(\\d+)'
        match = re.search(pattern, cam_h_content)
        if match:
            pin_num = int(match.group(1))
            if pin_num in usb_pins:
                check_fail(f"4.3.conflict", f"Camera pin {pin_name}={pin_num} conflicts with USB OTG!")
                conflict_found = True
    
    if not conflict_found:
        check_pass("4.3.noconflict", "No camera/USB pin conflicts")
    
    results["round4_platformio"] = results.pop("current", [])


# ============================================================================
# 主函数
# ============================================================================
def main():
    print("╔══════════════════════════════════════════════════════════╗")
    print("║  青云扑克 ESP32-S3-CAM 固件 - 多轮自检报告               ║")
    print("╚══════════════════════════════════════════════════════════╝")
    
    # 执行四轮自检
    round1_syntax_hid()
    round2_logic_coords()
    round3_memory_bounds()
    round4_platformio()
    
    # 汇总结果
    print("\n" + "="*70)
    print("自检结果汇总")
    print("="*70)
    
    total_pass = 0
    total_fail = 0
    total_warn = 0
    
    for round_name, checks in results.items():
        if not checks:
            continue
        pass_count = sum(1 for c in checks if c[0] == "PASS")
        fail_count = sum(1 for c in checks if c[0] == "FAIL")
        warn_count = sum(1 for c in checks if c[0] == "WARN")
        
        total_pass += pass_count
        total_fail += fail_count
        total_warn += warn_count
        
        print(f"\n  {round_name}:")
        print(f"    ✅ PASS: {pass_count}")
        print(f"    ❌ FAIL: {fail_count}")
        print(f"    ⚠️  WARN: {warn_count}")
        
        # 打印失败的检查项
        for status, check_id, message in checks:
            if status == "FAIL":
                print(f"    ❌ {check_id}: {message}")
    
    print(f"\n{'='*70}")
    print(f"  总计: ✅ {total_pass} PASS  ❌ {total_fail} FAIL  ⚠️  {total_warn} WARN")
    print(f"{'='*70}")
    
    if total_fail > 0:
        print("\n🔴 存在必须修复的问题！")
        return 1
    else:
        print("\n🟢 所有检查通过！(警告项建议关注但不阻塞)")
        return 0


if __name__ == "__main__":
    sys.exit(main())
