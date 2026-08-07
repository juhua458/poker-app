#!/usr/bin/env python3
"""精确测量底池和公共牌的实际像素坐标"""
from PIL import Image
import numpy as np

PATH = "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-13-02-33-09-28_1d58bed7e226cc412b0128cc8fb4cf50.jpg"
img = Image.open(PATH)
W, H = img.size
np_img = np.array(img)

print(f"分辨率: {W}×{H}")

# ========== 底池区域精确定位 ==========
# 底池是绿色背景+白色/黄色文字的区域
# 扫描 y 方向，找到包含"底池"文字的区域
print("\n=== 底池区域 ===")
# 底池大约在屏幕中央偏上，先扫描 y=30%-45% 范围
for y_start_pct in [0.33, 0.34, 0.35, 0.36]:
    for y_end_pct in [0.38, 0.39, 0.40, 0.41, 0.42]:
        x1 = int(0.25 * W)
        x2 = int(0.75 * W)
        y1 = int(y_start_pct * H)
        y2 = int(y_end_pct * H)
        region = np_img[y1:y2, x1:x2]
        # 检测黄色文字 (底池数字是黄色的)
        r = region[:,:,0].astype(int)
        g = region[:,:,1].astype(int)
        b = region[:,:,2].astype(int)
        # 黄色: R高G高B低
        yellow = ((r > 200) & (g > 150) & (b < 100)).sum()
        # 白色文字 (底池标签)
        white = ((r > 200) & (g > 200) & (b > 200)).sum()
        total = region.shape[0] * region.shape[1]
        if yellow > 10 or white > 10:
            print(f"  y={y_start_pct:.2f}-{y_end_pct:.2f} ({y1}-{y2}px): 黄={yellow} 白={white}")

# ========== 公共牌区域精确定位 ==========
print("\n=== 公共牌区域 ===")
# 找到5张牌的精确位置 — 扫描白色卡片区域
# 先找到牌的白色区域
gray = np_img.mean(axis=2)
white_mask = gray > 220

# 扫描中部区域找白色卡片
print("扫描 y=45%-62% 范围找白色卡片...")
for y_test in [0.44, 0.45, 0.46, 0.50, 0.54, 0.58, 0.60, 0.62]:
    y = int(y_test * H)
    row = white_mask[y, :]
    white_runs = []
    in_run = False
    start = 0
    for x in range(W):
        if row[x] and not in_run:
            in_run = True
            start = x
        elif not row[x] and in_run:
            in_run = False
            if x - start > 30:  # 宽度>30px才算
                white_runs.append((start, x, x - start))
    if white_runs:
        runs_str = ", ".join([f"({s}-{e},{l}px)" for s,e,l in white_runs[:7]])
        print(f"  y={y_test:.2f} ({y}px): {runs_str}")

# ========== 花色符号位置 ==========
# 在已知的牌区域内，找出花色symbol的实际位置
print("\n=== 花色符号位置 ===")
# 用社区牌的 bounding box: communityCardsBase x170-306, y1075-1267
# 每张牌约136px宽, 192px高
# 每张牌的suit symbol在rank下方, card内部位置
# 对于192px高的牌, suit symbol大约在 y=40%-80% of card height
card_w = 136
card_h = 192
print(f"单张牌尺寸: {card_w}×{card_h} px")
print(f"公共牌y范围: 1075-1267 ({1075/H:.2%}-{1267/H:.2%})")
print(f"公共牌x范围: 170-906")

# 第一张牌 8♣: x=170-306
# suit symbol 应该在牌面内部
card1 = np_img[1075:1267, 170:306]
print(f"\n第一张牌 (8♣) 区域: {card1.shape[1]}×{card1.shape[0]} px")

# 检查不同y比例下的彩色像素
for ratio_start, ratio_end in [(0.30, 0.70), (0.35, 0.75), (0.40, 0.80), (0.45, 0.85)]:
    sy1 = int(card_h * ratio_start)
    sy2 = int(card_h * ratio_end)
    suit_area = card1[sy1:sy2, :int(card_w * 0.5)]  # 左侧50%
    r = suit_area[:,:,0].astype(int)
    g = suit_area[:,:,1].astype(int)
    b = suit_area[:,:,2].astype(int)
    black = ((r < 90) & (g < 90) & (b < 90)).sum()
    red = ((r > 130) & (r-g > 50) & (r-b > 50)).sum()
    print(f"  y={ratio_start:.0%}-{ratio_end:.0%} ({sy1}-{sy2}px): 黑={black} 红={red}")

# ========== 手牌花色位置 ==========
print("\n=== 手牌花色位置 ===")
# 手牌: handCardsBase x50-185, x175-321, y1748-2046
# 手牌区域: 约 135px宽, 298px高 (两张牌整体)
# 每张牌单独约 135px宽, 149px高
print(f"手牌区域: x=50-321, y=1748-2046")
hand_region = np_img[1748:2046, 50:321]
print(f"手牌整体区域: {hand_region.shape[1]}×{hand_region.shape[0]} px")

# 左牌 K♣
left_card = np_img[1748:2046, 50:185]
print(f"左牌(K♣): {left_card.shape[1]}×{left_card.shape[0]} px")
for ratio_start, ratio_end in [(0.40, 0.70), (0.45, 0.75), (0.50, 0.80), (0.45, 0.95)]:
    sy1 = int(left_card.shape[0] * ratio_start)
    sy2 = int(left_card.shape[0] * ratio_end)
    suit_area = left_card[sy1:sy2, :int(left_card.shape[1] * 0.65)]
    r = suit_area[:,:,0].astype(int)
    g = suit_area[:,:,1].astype(int)
    b = suit_area[:,:,2].astype(int)
    black = ((r < 90) & (g < 90) & (b < 90)).sum()
    red = ((r > 130) & (r-g > 50) & (r-b > 50)).sum()
    print(f"  y={ratio_start:.0%}-{ratio_end:.0%} ({sy1}-{sy2}px): 黑={black} 红={red}")

# 右牌 Q♦
right_card = np_img[1748:2046, 175:321]
print(f"右牌(Q♦): {right_card.shape[1]}×{right_card.shape[0]} px")
for ratio_start, ratio_end in [(0.40, 0.70), (0.45, 0.75), (0.50, 0.80), (0.45, 0.95)]:
    sy1 = int(right_card.shape[0] * ratio_start)
    sy2 = int(right_card.shape[0] * ratio_end)
    suit_area = right_card[sy1:sy2, :int(right_card.shape[1] * 0.65)]
    r = suit_area[:,:,0].astype(int)
    g = suit_area[:,:,1].astype(int)
    b = suit_area[:,:,2].astype(int)
    black = ((r < 90) & (g < 90) & (b < 90)).sum()
    red = ((r > 130) & (r-g > 50) & (r-b > 50)).sum()
    print(f"  y={ratio_start:.0%}-{ratio_end:.0%} ({sy1}-{sy2}px): 黑={black} 红={red}")

# ========== 最终建议 ==========
print("\n=== 最终建议坐标 ===")
print("底池: x=25%-75%, y=35%-42% (绿色pill+黄白文字)")
print("花色符号(公共牌): 牌内 y=35%-75% (而非35%-92%)")
print("花色符号(手牌): 牌内 y=45%-80% (而非45%-95%)")
