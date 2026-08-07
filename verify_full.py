#!/usr/bin/env python3
"""完整验证 — 包含颜色+形状分析，模拟 Kotlin detectSuit + analyzeSuitShape"""
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
    return region

def full_detect_suit(region, is_hand):
    """完整模拟 Kotlin detectSuit + analyzeSuitShape"""
    if region.size == 0:
        return "?", "?"
    ch, cw = region.shape[:2]
    
    # suit 区域（修复后的参数）
    if is_hand:
        sy1 = int(ch * 0.45)
        sy2 = int(ch * 0.75)
    else:
        sy1 = int(ch * 0.35)
        sy2 = int(ch * 0.75)
    suit_w = int(cw * 0.65)
    suit_area = region[sy1:sy2, :suit_w]
    
    if suit_area.size == 0:
        return "?", "?"
    
    r = suit_area[:,:,0].astype(int)
    g = suit_area[:,:,1].astype(int)
    b = suit_area[:,:,2].astype(int)
    
    # Step 1: 颜色分析
    red = int(((r > 130) & (r - g > 50) & (r - b > 50)).sum())
    black = int(((r < 90) & (g < 90) & (b < 90)).sum())
    total = red + black
    
    if total < 15:
        return "?", "?"
    
    if red > black * 2:
        known_color = "red"
    elif black > red * 2:
        known_color = "black"
    else:
        known_color = "unknown"
    
    # Step 2: 形状分析
    ah, aw = suit_area.shape[:2]
    if known_color == "red":
        colored = (r > 130) & (r - g > 50) & (r - b > 50)
    elif known_color == "black":
        colored = (r < 90) & (g < 90) & (b < 90)
    else:
        colored = ((r > 130) & (r - g > 50) & (r - b > 50)) | ((r < 90) & (g < 90) & (b < 90))
    
    cnt = int(colored.sum())
    if cnt < 10:
        return "?", "?"
    
    half_h = ah // 2
    half_w = aw // 2
    
    top = int(colored[:half_h, :].sum())
    bottom = int(colored[half_h:, :].sum())
    left = int(colored[:, :half_w].sum())
    right = int(colored[:, half_w:].sum())
    top_ratio = top / cnt
    
    row_widths = colored.sum(axis=1)
    max_top_w = int(row_widths[:half_h].max()) if half_h > 0 else 0
    max_bot_w = int(row_widths[half_h:].max()) if half_h < ah else 0
    top_edge_end = max(1, int(ah * 0.2))
    bot_edge_start = int(ah * 0.8)
    top_edge_w = int(row_widths[:top_edge_end].max()) if top_edge_end > 0 else 0
    bot_edge_w = int(row_widths[bot_edge_start:].max()) if bot_edge_start < ah else 0
    
    ys, xs = np.where(colored)
    center_x = xs.mean()
    symmetry = 1.0 - abs(center_x - aw/2.0) / (aw/2.0)
    
    # 评分逻辑 (对应 Kotlin analyzeSuitShape)
    h_s = d_s = c_s = s_s = 0.0
    if known_color == "red":
        # ♥: 顶部宽+对称
        h_s = top_ratio * 2.0 + (max_top_w / cnt) * 1.5 + symmetry * 0.5
        if top_edge_w > bot_edge_w * 1.3:
            h_s += 1.0
        # ♦: 上下对称+菱形
        d_s = (1.0 - abs(top_ratio - 0.5) * 2.0) * 2.0 + symmetry * 1.5 + (max_top_w / cnt) * 0.5
    elif known_color == "black":
        # ♠: 底部宽+倒三角
        s_s = (1.0 - top_ratio) * 2.0 + (max_bot_w / cnt) * 1.5 + symmetry * 0.5
        if bot_edge_w > top_edge_w * 1.2:
            s_s += 0.8
        # : 顶部宽+三叶
        c_s = top_ratio * 1.5 + (top_edge_w / (aw * 0.5 + 1)) * 1.0 + symmetry * 0.3
    
    scores = [("h", h_s, "♥"), ("d", d_s, "♦"), ("c", c_s, "♣"), ("s", s_s, "♠")]
    scores.sort(key=lambda x: -x[1])
    best = scores[0]
    second = scores[1]
    
    if best[1] > 0 and best[1] > second[1] * 1.2:
        return best[0], best[2]
    
    # 颜色兜底
    if known_color == "red":
        return "h", "♥"
    elif known_color == "black":
        return "s", "♠"
    return "?", "?"

# ========== 完整花色验证 ==========
print("=" * 60)
print("花色识别完整验证 (颜色+形状分析)")
print("=" * 60)

test_cards = [
    ("K♣", 0.046, 0.746, 0.171, 0.873, True, "c"),
    ("Q♦", 0.161, 0.746, 0.300, 0.873, True, "d"),
    ("8♣", 0.157, 0.459, 0.283, 0.541, False, "c"),
    ("5♠", 0.296, 0.459, 0.421, 0.541, False, "s"),
    ("7♦", 0.440, 0.459, 0.561, 0.541, False, "d"),
    ("5♣", 0.579, 0.459, 0.704, 0.541, False, "c"),
    ("7♥", 0.718, 0.459, 0.843, 0.541, False, "h"),
]

suit_map = {"♥": "h", "♦": "d", "♣": "c", "": "s"}
correct = 0

for name, x1, y1, x2, y2, is_hand, exp_suit in test_cards:
    region = crop(x1, y1, x2, y2, f"full_{name}")
    ch, cw = region.shape[:2]
    det_key, det_sym = full_detect_suit(region, is_hand)
    suit_ok = det_key == exp_suit
    status = "✅" if suit_ok else "❌"
    print(f"  {name}: {cw}×{ch}px | 识别={det_sym}({det_key}) 期望={exp_suit} {status}")
    if suit_ok:
        correct += 1

print(f"\n花色识别准确率: {correct}/{len(test_cards)} ({correct/len(test_cards)*100:.0f}%)")

# ========== 底池验证 ==========
print("\n" + "=" * 60)
print("底池区域验证")
print("=" * 60)
pot = crop(0.25, 0.35, 0.75, 0.42, "pot_fixed")
r = pot[:,:,0].astype(int); g = pot[:,:,1].astype(int); b = pot[:,:,2].astype(int)
yellow = int(((r > 200) & (g > 150) & (b < 100)).sum())
white = int(((r > 200) & (g > 200) & (b > 200)).sum())
print(f"黄色像素: {yellow}, 白色像素: {white}")
print("✅ 底池区域命中" if white > 50 else "⚠️ 底池区域可能未命中")
