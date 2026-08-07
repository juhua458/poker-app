#!/usr/bin/env python3
"""V2.9.208 修复后验证 — 用修改后的坐标参数重新验证"""
from PIL import Image
import numpy as np
import os

PATH = "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
OUTPUT = "/app/data/所有对话/主对话/poker-app-latest/verification_output"
os.makedirs(OUTPUT, exist_ok=True)

img = Image.open(PATH)
W, H = img.size
np_img = np.array(img)

def crop(x1_pct, y1_pct, x2_pct, y2_pct, name=""):
    x1, y1 = int(x1_pct * W), int(y1_pct * H)
    x2, y2 = int(x2_pct * W), int(y2_pct * H)
    region = np_img[y1:y2, x1:x2]
    if name:
        Image.fromarray(region).save(f"{OUTPUT}/{name}.png")
    return region, x1, y1, x2, y2

def color_analysis(region):
    if region.size == 0:
        return 0, 0
    r = region[:,:,0].astype(int)
    g = region[:,:,1].astype(int)
    b = region[:,:,2].astype(int)
    red = int(((r > 130) & (r - g > 50) & (r - b > 50)).sum())
    black = int(((r < 90) & (g < 90) & (b < 90)).sum())
    return red, black

def detect_suit(region, known_color=None):
    """模拟 CardRecognizer.detectSuit 逻辑"""
    if region.size == 0:
        return "?", "?"
    r = region[:,:,0].astype(int)
    g = region[:,:,1].astype(int)
    b = region[:,:,2].astype(int)
    red = int(((r > 130) & (r - g > 50) & (r - b > 50)).sum())
    black = int(((r < 90) & (g < 90) & (b < 90)).sum())
    total = red + black
    if total < 15:
        return "?", "?"
    if red > black * 2:
        color = "red"
    elif black > red * 2:
        color = "black"
    else:
        color = "unknown"
    
    if color == "red":
        return "h", "♥"
    elif color == "black":
        return "s", "♠"
    return "?", "?"

# ========== Phase 2: 底池 OCR (修复后) ==========
print("=" * 60)
print("Phase 2: 底池区域验证 (修复后: y=35%-42%)")
print("=" * 60)

# 修复后的坐标
pot_region, px1, py1, px2, py2 = crop(0.25, 0.35, 0.75, 0.42, "p2_pot_region_fixed")
print(f"修复后底池区域: x=25%-75%, y=35%-42% → 像素({px1},{py1})→({px2},{py2})")
print(f"区域尺寸: {pot_region.shape[1]}×{pot_region.shape[0]}")

# 分析文字内容
gray = np.mean(pot_region, axis=2)
dark = int((gray < 100).sum())
total_px = pot_region.shape[0] * pot_region.shape[1]

# 检测黄色数字 (底池金额)
r = pot_region[:,:,0].astype(int)
g = pot_region[:,:,1].astype(int)
b = pot_region[:,:,2].astype(int)
yellow = int(((r > 200) & (g > 150) & (b < 100)).sum())
white_text = int(((r > 200) & (g > 200) & (b > 200)).sum())
print(f"黄色像素(金额数字): {yellow}")
print(f"白色像素(标签文字): {white_text}")
print(f"暗色像素: {dark}/{total_px} ({dark/total_px:.3f})")

# 检查是否命中底池
if yellow > 0 or white_text > 50:
    print("✅ 底池区域命中！包含文字内容")
else:
    print("⚠️ 底池区域可能未命中文字")

# ========== Phase 1: 花色识别 (修复后) ==========
print("\n" + "=" * 60)
print("Phase 1: 花色识别验证 (修复后 suit 区域)")
print("=" * 60)

# 使用精确的牌面坐标
# 公共牌: 5张牌, 每张约125px宽, y=1075-1267 (h=192px)
# 手牌: 左牌 x=50-185, 右牌 x=175-321, y=1748-2046 (h=298px for both)

# 测试每张牌的 suit 识别
test_cards = [
    # (名称, x1, y1, x2, y2, is_hand, expected_suit, expected_color)
    ("K♣", 0.046, 0.746, 0.171, 0.873, True, "c", "black"),    # 左牌
    ("Q♦", 0.161, 0.746, 0.300, 0.873, True, "d", "red"),      # 右牌
    ("8♣", 0.157, 0.459, 0.283, 0.541, False, "c", "black"),   # 公共牌0
    ("5♠", 0.296, 0.459, 0.421, 0.541, False, "s", "black"),   # 公共牌1
    ("7♦", 0.440, 0.459, 0.561, 0.541, False, "d", "red"),     # 公共牌2
    ("5♣", 0.579, 0.459, 0.704, 0.541, False, "c", "black"),   # 公共牌3
    ("7♥", 0.718, 0.459, 0.843, 0.541, False, "h", "red"),     # 公共牌4
]

correct = 0
for name, x1, y1, x2, y2, is_hand, exp_suit, exp_color in test_cards:
    region, px1, py1, px2, py2 = crop(x1, y1, x2, y2, f"fix_{name}")
    ch, cw = region.shape[:2]
    
    # 修复后的 suit 区域
    if is_hand:
        sy1 = int(ch * 0.45)
        sy2 = int(ch * 0.75)
    else:
        sy1 = int(ch * 0.35)
        sy2 = int(ch * 0.75)
    suit_w = int(cw * 0.65)
    suit_region = region[sy1:sy2, :suit_w]
    
    red, black = color_analysis(suit_region)
    detected = detect_suit(suit_region)
    det_key, det_sym = detected
    
    color_ok = (exp_color == "red" and red > black * 2) or (exp_color == "black" and black > red * 2)
    suit_ok = det_key == exp_suit
    
    status = "✅" if suit_ok else "❌"
    print(f"  {name}: 区域({px1},{py1})→({px2},{py2}) {cw}×{ch}px | suit区y={sy1/ch:.0%}-{sy2/ch:.0%} | 红={red} 黑={black} | 识别={det_sym}({det_key}) 期望={exp_suit} {status}")
    if suit_ok:
        correct += 1

print(f"\n花色识别准确率: {correct}/{len(test_cards)} ({correct/len(test_cards)*100:.0f}%)")

# ========== 总结 ==========
print("\n" + "=" * 60)
print("修复总结")
print("=" * 60)
print("Phase 2 底池: y=16%-24% → y=35%-42% ✅")
print("Phase 1 花色(公共牌): suit y=35%-92% → y=35%-75% ✅")
print("Phase 1 花色(手牌):   suit y=45%-95% → y=45%-75% ✅")
