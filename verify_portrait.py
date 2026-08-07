#!/usr/bin/env python3
"""
V2.9.208 本地CV验证 — 用 1080×2344 竖屏 GG 截图精准验证
截图: Screenshot_2026-06-13-02-33-09-28 (river, K♣Q♦, 8♣5♠7♦5♣7♥, pot=2200)
"""
from PIL import Image
import numpy as np
import os

PATH = "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
OUTPUT = "/app/data/所有对话/主对话/poker-app-latest/verification_output"
os.makedirs(OUTPUT, exist_ok=True)

img = Image.open(PATH)
W, H = img.size
print(f"截图分辨率: {W}×{H}")
assert W == 1080 and H == 2344, f"分辨率不匹配: {W}×{H}"

np_img = np.array(img)

def crop(x1_pct, y1_pct, x2_pct, y2_pct, name=""):
    x1, y1 = int(x1_pct * W), int(y1_pct * H)
    x2, y2 = int(x2_pct * W), int(y2_pct * H)
    region = np_img[y1:y2, x1:x2]
    if name:
        Image.fromarray(region).save(f"{OUTPUT}/{name}.png")
    return region, x1, y1, x2, y2

def color_analysis(region):
    """颜色分析 — 对应 CardRecognizer.detectSuit Step 1"""
    if region.size == 0:
        return 0, 0, 0
    r = region[:,:,0].astype(int)
    g = region[:,:,1].astype(int)
    b = region[:,:,2].astype(int)
    red = int(((r > 130) & (r - g > 50) & (r - b > 50)).sum())
    black = int(((r < 90) & (g < 90) & (b < 90)).sum())
    return red, black, red + black

def shape_analysis(region, known_color):
    """形状分析 — 对应 CardRecognizer.analyzeSuitShape"""
    if region.size == 0:
        return None
    h, w = region.shape[:2]
    r = region[:,:,0].astype(int)
    g = region[:,:,1].astype(int)
    b = region[:,:,2].astype(int)
    if known_color == "red":
        colored = (r > 130) & (r - g > 50) & (r - b > 50)
    elif known_color == "black":
        colored = (r < 90) & (g < 90) & (b < 90)
    else:
        colored = ((r > 130) & (r - g > 50) & (r - b > 50)) | ((r < 90) & (g < 90) & (b < 90))
    cnt = int(colored.sum())
    if cnt < 10:
        return None
    half_h = h // 2
    half_w = w // 2
    top = int(colored[:half_h, :].sum())
    bottom = int(colored[half_h:, :].sum())
    top_ratio = top / cnt
    row_widths = colored.sum(axis=1)
    max_top_w = int(row_widths[:half_h].max()) if half_h > 0 else 0
    max_bot_w = int(row_widths[half_h:].max()) if half_h < h else 0
    top_edge_end = max(1, int(h * 0.2))
    bot_edge_start = int(h * 0.8)
    top_edge_w = int(row_widths[:top_edge_end].max())
    bot_edge_w = int(row_widths[bot_edge_start:].max())
    ys, xs = np.where(colored)
    center_x = xs.mean()
    symmetry = 1.0 - abs(center_x - w/2.0) / (w/2.0)
    h_s = d_s = c_s = s_s = 0.0
    if known_color == "red":
        h_s = top_ratio * 2.0 + (max_top_w / cnt) * 1.5 + symmetry * 0.5
        if top_edge_w > bot_edge_w * 1.3: h_s += 1.0
        d_s = (1.0 - abs(top_ratio - 0.5) * 2.0) * 2.0 + symmetry * 1.5 + (max_top_w / cnt) * 0.5
    elif known_color == "black":
        s_s = (1.0 - top_ratio) * 2.0 + (max_bot_w / cnt) * 1.5 + symmetry * 0.5
        if bot_edge_w > top_edge_w * 1.2: s_s += 0.8
        c_s = top_ratio * 1.5 + (top_edge_w / (w * 0.5 + 1)) * 1.0 + symmetry * 0.3
    scores = [("h", h_s, "♥"), ("d", d_s, "♦"), ("c", c_s, ""), ("s", s_s, "♠")]
    scores.sort(key=lambda x: -x[1])
    best, second = scores[0], scores[1]
    if best[1] > 0 and best[1] > second[1] * 1.2:
        return best[0], best[2], best[1], second[1]
    return None

# ============================================================
# Phase 1: 花色识别验证
# ============================================================
print("\n" + "=" * 60)
print("Phase 1: 花色识别验证")
print("=" * 60)

# 手牌: K♣ (黑色) Q♦ (红色) — 位于左下约 x=2%-15%, y=74%-87%
# 公共牌: 8♣ 5♠ 7♦ 5♣ 7♥ — 位于屏幕中部 x=12%-82%, y=44%-62%
hand_cards = [
    ("K♣", 0.02, 0.74, 0.15, 0.87, "black"),
    ("Q♦", 0.08, 0.74, 0.20, 0.87, "red"),
]
board_cards = [
    ("8♣",  0.12, 0.44, 0.24, 0.62, "black"),
    ("5♠",  0.24, 0.44, 0.36, 0.62, "black"),
    ("7♦",  0.36, 0.44, 0.48, 0.62, "red"),
    ("5♣",  0.48, 0.44, 0.60, 0.62, "black"),
    ("7♥",  0.60, 0.44, 0.72, 0.62, "red"),
]

def verify_card(name, x1, y1, x2, y2, expected_color, card_type):
    region, px1, py1, px2, py2 = crop(x1, y1, x2, y2, f"p1_{name}")
    ch, cw = region.shape[:2]
    # suit symbol 区域 (isHand=True: y=45%-95%, isHand=False: y=35%-92%)
    if card_type == "hand":
        sy1 = int(ch * 0.45); sy2 = int(ch * 0.95)
    else:
        sy1 = int(ch * 0.35); sy2 = int(ch * 0.92)
    suit_region = region[sy1:sy2, :int(cw * 0.65)]
    red_px, black_px, total = color_analysis(suit_region)
    if red_px > black_px * 2:
        detected_color = "red"
    elif black_px > red_px * 2:
        detected_color = "black"
    else:
        detected_color = "unknown"
    color_ok = detected_color == expected_color
    # 形状分析
    shape = shape_analysis(suit_region, detected_color if detected_color != "unknown" else expected_color)
    expected_suit = name[-1]
    suit_map = {"♥": "h", "♦": "d", "♣": "c", "♠": "s"}
    expected_key = suit_map.get(expected_suit, "?")
    if shape:
        det_key, det_sym, best_s, sec_s = shape
        shape_ok = det_key == expected_key
        result = f"形状={det_sym}(score={best_s:.2f}>{sec_s:.2f}) [{'✅' if shape_ok else '❌'}]"
    else:
        fallback = "♥" if detected_color == "red" else "♠"
        result = f"形状不确定→兜底{fallback}"
    print(f"  {name}: 颜色={detected_color}(red={red_px},black={black_px}) [{'✅' if color_ok else '❌'}] | {result}")

print("\n手牌:")
for name, x1, y1, x2, y2, color in hand_cards:
    verify_card(name, x1, y1, x2, y2, color, "hand")
print("\n公共牌:")
for name, x1, y1, x2, y2, color in board_cards:
    verify_card(name, x1, y1, x2, y2, color, "board")

# ============================================================
# Phase 2: 底池 OCR 区域验证
# ============================================================
print("\n" + "=" * 60)
print("Phase 2: 底池区域验证")
print("=" * 60)

# 代码中 GG 底池区域: x=30%-70%, y=16%-24%
pot_region, px1, py1, px2, py2 = crop(0.30, 0.16, 0.70, 0.24, "p2_pot_region")
print(f"  代码底池区域: x=30%-70%, y=16%-24% → 像素({px1},{py1})→({px2},{py2})")
print(f"  区域尺寸: {pot_region.shape[1]}×{pot_region.shape[0]}")
# 分析暗色像素
gray = np.mean(pot_region, axis=2)
dark = int((gray < 100).sum())
total_px = pot_region.shape[0] * pot_region.shape[1]
print(f"  暗色像素: {dark}/{total_px} ({dark/total_px:.3f})")

# 实际底池位置: 图中 "底池 2,200" 大约在 x=30%-70%, y=33%-41%
actual_pot, apx1, apy1, apx2, apy2 = crop(0.30, 0.33, 0.70, 0.41, "p2_actual_pot")
print(f"\n  实际底池区域: x=30%-70%, y=33%-41% → 像素({apx1},{apy1})→({apx2},{apy2})")
print(f"  区域尺寸: {actual_pot.shape[1]}×{actual_pot.shape[0]}")
gray2 = np.mean(actual_pot, axis=2)
dark2 = int((gray2 < 100).sum())
total_px2 = actual_pot.shape[0] * actual_pot.shape[1]
print(f"  暗色像素: {dark2}/{total_px2} ({dark2/total_px2:.3f})")

# 判断代码区域是否命中底池
print(f"\n  结论: 代码区域 y=16%-24% 对应的是庄家名+筹码区域")
print(f"  实际底池在 y=33%-41%（桌面中央），与代码坐标偏差约 17%")

# ============================================================
# Phase 3: 按钮坐标验证
# ============================================================
print("\n" + "=" * 60)
print("Phase 3: 按钮坐标验证")
print("=" * 60)

# GG底部按钮 (代码中的坐标)
buttons = [
    ("弃牌/Fold", 0.181, 0.960),
    ("过牌/Check", 0.500, 0.960),
    ("加注/Raise", 0.819, 0.960),
]
print("  GG底部按钮:")
for name, x_pct, y_pct in buttons:
    px, py = int(x_pct * W), int(y_pct * H)
    # 检查该位置是否有按钮（彩色像素）
    check = np_img[max(0,py-40):py+40, max(0,px-60):px+60]
    if check.size > 0:
        r = check[:,:,0].astype(int)
        g = check[:,:,1].astype(int)
        b = check[:,:,2].astype(int)
        colorful = int(((r > 80) & ((np.abs(r-g) > 30) | (np.abs(r-b) > 30) | (np.abs(g-b) > 30))).sum())
        total = check.shape[0] * check.shape[1]
        ratio = colorful / total
        # 检查是否有文字（亮白色）
        white = int(((r > 180) & (g > 180) & (b > 180)).sum())
        white_ratio = white / total
        print(f"    {name}: 像素({px},{py}) 彩色比={ratio:.3f} 白色文字比={white_ratio:.3f}")

# GG右侧4档预设按钮
right_buttons = [
    ("100%", 0.819, 0.751),
    ("75%", 0.819, 0.821),
    ("50%", 0.819, 0.890),
    ("33%", 0.819, 0.937),
]
print("\n  GG右侧4档按钮:")
for name, x_pct, y_pct in right_buttons:
    px, py = int(x_pct * W), int(y_pct * H)
    check = np_img[max(0,py-35):py+35, max(0,px-100):px+10]
    if check.size > 0:
        r = check[:,:,0].astype(int)
        g = check[:,:,1].astype(int)
        b = check[:,:,2].astype(int)
        white = int(((r > 180) & (g > 180) & (b > 180)).sum())
        total = check.shape[0] * check.shape[1]
        white_ratio = white / total
        print(f"    {name}: 像素({px},{py}) 白色文字比={white_ratio:.3f}")

# 保存按钮区域
btn_region = np_img[int(0.88*H):, :]
Image.fromarray(btn_region).save(f"{OUTPUT}/p3_buttons_area.png")
print(f"\n  底部按钮区域已保存")

# ============================================================
# Phase 4: Hero手牌区域 + 快速通道逻辑
# ============================================================
print("\n" + "=" * 60)
print("Phase 4: 快速通道 + Hero手牌")
print("=" * 60)

# Hero手牌在底部左侧 x=2%-20%, y=74%-88%
hero_hand, hx1, hy1, hx2, hy2 = crop(0.02, 0.74, 0.20, 0.88, "p4_hero_hand")
print(f"  Hero手牌区域: 像素({hx1},{hy1})→({hx2},{hy2})")
print(f"  手牌区域尺寸: {hero_hand.shape[1]}×{hero_hand.shape[0]}")

# 检查手牌区域是否有白色卡片
r = hero_hand[:,:,0].astype(int)
g = hero_hand[:,:,1].astype(int)
b = hero_hand[:,:,2].astype(int)
white_cards = int(((r > 200) & (g > 200) & (b > 200)).sum())
total = hero_hand.shape[0] * hero_hand.shape[1]
print(f"  白色卡片像素: {white_cards}/{total} ({white_cards/total:.3f})")

# 快速通道前置条件检查
print(f"\n  快速通道v2前置条件:")
print(f"  ① 手牌2张 → 需要 recognizeAll() 识别出2张牌")
print(f"   置信度 >= 0.85 → 需要 NCC 模板匹配高分")
print(f"   底池 > 0 → 需要 readPotSize() OCR 成功或缓存")
print(f"  当前底池=2200 → 如果OCR区域正确则满足")

# ============================================================
# 筹码区域验证
# ============================================================
print("\n" + "=" * 60)
print("筹码区域坐标验证")
print("=" * 60)

chip_regions = [
    ("seat0 左上 Nass_ 24,553", 0.02, 0.24, 0.28, 0.34),
    ("seat1 正上 HeinGeerken1 5,100", 0.35, 0.08, 0.65, 0.16),
    ("seat2 右上 hhhhxxxxxx 5,000", 0.72, 0.24, 0.98, 0.34),
    ("seat3 右中 pralomge29 15,611", 0.72, 0.56, 0.98, 0.66),
    ("seat4 正下对手", 0.35, 0.70, 0.65, 0.78),
    ("seat5 左中 Lunde@1 7,139", 0.02, 0.56, 0.28, 0.66),
]
for name, x1, y1, x2, y2 in chip_regions:
    px1, py1, px2, py2 = int(x1*W), int(y1*H), int(x2*W), int(y2*H)
    region = np_img[py1:py2, px1:px2]
    if region.size > 0:
        gray = np.mean(region, axis=2)
        text = int((gray < 150).sum())
        total = region.shape[0] * region.shape[1]
        print(f"  {name}: ({px1},{py1})→({px2},{py2}) 文字比={text/total:.3f}")

# D按钮位置 — 图中D按钮约在 x=82%, y=60%
d_region = crop(0.75, 0.55, 0.95, 0.65, "p_d_button")
print(f"\n  D按钮区域: 图中可见 D 标记约在 (82%, 60%) 位置")

print(f"\n{'='*60}")
print("验证完成")
print(f"{'='*60}")
