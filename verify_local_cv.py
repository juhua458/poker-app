#!/usr/bin/env python3
"""
V2.9.208 本地CV验证脚本
用真实GG牌桌截图验证4个Phase的坐标和逻辑
"""
from PIL import Image
import numpy as np
import os

# ========== 配置 ==========
SCREEN_W, SCREEN_H = 1080, 2344  # GG竖屏基准分辨率

# 使用3张6月4日的GG牌桌截图
SCREENSHOTS = [
    "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-04-23-11-39-98_f5951c3528432f6a0e00921a7ee8e0e4.jpg",
    "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-04-23-12-14-22_f5951c3528432f6a0e00921a7ee8e0e4.jpg",
    "/app/data/所有对话/主对话/用户上传/Screenshot_2026-06-04-23-12-53-21_f5951c3528432f6a0e00921a7ee8e0e4.jpg",
]

OUTPUT_DIR = "/app/data/所有对话/主对话/poker-app-latest/verification_output"
os.makedirs(OUTPUT_DIR, exist_ok=True)


def load_image(path):
    img = Image.open(path)
    return np.array(img), img.size  # (H, W, 3), (W, H)


def pct_to_pixels(pct, w, h):
    """百分比坐标转像素"""
    x1, y1, x2, y2 = pct
    return (int(x1*w), int(y1*h), int(x2*w), int(y2*h))


def extract_region(img_np, x1, y1, x2, y2):
    """裁剪区域"""
    h, w = img_np.shape[:2]
    x1 = max(0, min(x1, w-1))
    y1 = max(0, min(y1, h-1))
    x2 = max(x1+1, min(x2, w))
    y2 = max(y1+1, min(y2, h))
    return img_np[y1:y2, x1:x2]


def analyze_colors(region):
    """分析区域的红色/黑色像素分布（对应 detectSuit 的颜色分析逻辑）"""
    if region.size == 0:
        return 0, 0, 0
    
    r = region[:,:,0].astype(int)
    g = region[:,:,1].astype(int)
    b = region[:,:,2].astype(int)
    
    # 红色: R高且明显高于G和B
    red_mask = (r > 130) & (r - g > 50) & (r - b > 50)
    # 黑色: 所有通道都低
    black_mask = (r < 90) & (g < 90) & (b < 90)
    
    red_pixels = int(red_mask.sum())
    black_pixels = int(black_mask.sum())
    total = red_pixels + black_pixels
    
    return red_pixels, black_pixels, total


def analyze_shape(region, known_color):
    """分析形状区分同色花色（对应 analyzeSuitShape）"""
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
    
    colored_count = int(colored.sum())
    if colored_count < 10:
        return None
    
    half_h = h // 2
    half_w = w // 2
    
    top_half = int(colored[:half_h, :].sum())
    bottom_half = int(colored[half_h:, :].sum())
    left_half = int(colored[:, :half_w].sum())
    right_half = int(colored[:, half_w:].sum())
    
    # 最宽行位置
    row_widths = colored.sum(axis=1)
    max_top_w = int(row_widths[:half_h].max()) if half_h > 0 else 0
    max_bot_w = int(row_widths[half_h:].max()) if half_h < h else 0
    
    # 顶部/底部边缘宽度（前/后20%行）
    top_edge_end = max(1, int(h * 0.2))
    bot_edge_start = int(h * 0.8)
    top_edge_w = int(row_widths[:top_edge_end].max()) if top_edge_end > 0 else 0
    bot_edge_w = int(row_widths[bot_edge_start:].max()) if bot_edge_start < h else 0
    
    top_ratio = top_half / colored_count
    bottom_ratio = bottom_half / colored_count
    left_ratio = left_half / colored_count
    right_ratio = right_half / colored_count
    
    # 重心x
    ys, xs = np.where(colored)
    center_x = xs.mean() if len(xs) > 0 else w / 2
    symmetry = 1.0 - abs(center_x - w/2.0) / (w/2.0)
    
    # === 评分 ===
    h_score = d_score = c_score = s_score = 0.0
    
    if known_color == "red":
        # ♥ Heart: 宽顶部（双圆弧），窄底部（尖点）
        h_score += top_ratio * 2.0
        h_score += (max_top_w / colored_count) * 1.5
        h_score += symmetry * 0.5
        if top_edge_w > bot_edge_w * 1.3:
            h_score += 1.0
        
        # ♦ Diamond: 上下对称, 中部最宽
        d_score += (1.0 - abs(top_ratio - 0.5) * 2.0) * 2.0
        d_score += symmetry * 1.5
        d_score += (max_top_w / colored_count) * 0.5
    
    elif known_color == "black":
        # ♠ Spade: 窄顶部（尖点），宽底部（圆弧+茎）
        s_score += bottom_ratio * 2.0
        s_score += (max_bot_w / colored_count) * 1.5
        s_score += symmetry * 0.5
        if bot_edge_w > top_edge_w * 1.2:
            s_score += 0.8
        
        # ♣ Club: 最顶部宽（三圆弧），底部窄（茎）
        c_score += top_ratio * 1.5
        c_score += (top_edge_w / (w * 0.5 + 1)) * 1.0
        c_score += symmetry * 0.3
    
    scores = [("h", h_score, "♥"), ("d", d_score, "♦"), ("c", c_score, "♣"), ("s", s_score, "♠")]
    sorted_scores = sorted(scores, key=lambda x: -x[1])
    best = sorted_scores[0]
    second = sorted_scores[1]
    
    if best[1] > 0 and best[1] > second[1] * 1.2:
        return best[0], best[2], best[1], second[1]
    return None


# ========== Phase 1: 花色识别验证 ==========
def verify_phase1_suit(img_np, img_w, img_h, idx):
    print(f"\n{'='*60}")
    print(f"Phase 1 花色识别验证 — 截图 {idx}")
    print(f"{'='*60}")
    
    # 从截图3 (23:12:53) 中可以看到公共牌区域: 4♠ J♦ A♦ 9♣
    # 但这张截图的公共牌已经消失了。我们用截图2 (23:12:14) 验证
    
    if idx != 1:  # 只有截图2有公共牌
        print("  跳过（此截图无公共牌）")
        return
    
    # 公共牌大约位于屏幕中部
    # 估算：公共牌4张牌横排在屏幕中央偏上
    # 每张牌大约120×150像素
    board_cards_positions = [
        ("4♠", 0.30, 0.35, 0.42, 0.55),   # 第一张牌 4♠ (黑色)
        ("J♦", 0.42, 0.35, 0.54, 0.55),   # 第二张牌 J♦ (红色)
        ("A♦", 0.54, 0.35, 0.66, 0.55),   # 第三张牌 A♦ (红色)
        ("9♣", 0.66, 0.35, 0.78, 0.55),   # 第四张牌 9♣ (黑色)
    ]
    
    # 注意：实际坐标需要根据截图比例调整
    # 截图分辨率可能不是 1080×2344，需要按比例缩放
    actual_w, actual_h = img_w, img_h
    scale_x = actual_w / 1080.0
    scale_y = actual_h / 2344.0
    
    for card_name, x1_pct, y1_pct, x2_pct, y2_pct in board_cards_positions:
        x1 = int(x1_pct * actual_w)
        y1 = int(y1_pct * actual_h)
        x2 = int(x2_pct * actual_w)
        y2 = int(y2_pct * actual_h)
        
        card_region = extract_region(img_np, x1, y1, x2, y2)
        if card_region.size == 0:
            print(f"  {card_name}: 裁剪区域为空，跳过")
            continue
        
        card_h, card_w = card_region.shape[:2]
        
        # suit symbol 区域（公共牌：y=35%-92%）
        suit_start_y = int(card_h * 0.35)
        suit_end_y = int(card_h * 0.92)
        suit_w = int(card_w * 0.65)
        
        suit_region = card_region[suit_start_y:suit_end_y, :suit_w]
        
        red_px, black_px, total = analyze_colors(suit_region)
        
        expected_color = "red" if "♦" in card_name or "♥" in card_name else "black"
        
        # 判断颜色
        if red_px > black_px * 2:
            detected_color = "red"
        elif black_px > red_px * 2:
            detected_color = "black"
        else:
            detected_color = "unknown"
        
        color_ok = detected_color == expected_color
        
        # 形状分析
        shape_result = analyze_shape(suit_region, detected_color)
        
        expected_suit = card_name[-1]
        suit_symbols = {"♥": "h", "♦": "d", "♣": "c", "♠": "s"}
        expected_key = suit_symbols.get(expected_suit, "?")
        
        if shape_result:
            detected_suit_key, detected_symbol, best_score, second_score = shape_result
            shape_ok = detected_suit_key == expected_key
            print(f"  {card_name}: 颜色={detected_color}(red={red_px},black={black_px}) [{color_ok}] | "
                  f"形状={detected_symbol}({best_score:.2f} vs {second_score:.2f}) [{shape_ok}]")
        else:
            # 形状不确定，用颜色兜底
            fallback_suit = "h" if detected_color == "red" else "s"
            fallback_sym = "♥" if detected_color == "red" else "♠"
            print(f"  {card_name}: 颜色={detected_color}(red={red_px},black={black_px}) [{color_ok}] | "
                  f"形状不确定 → 兜底={fallback_sym} (可能不准)")
        
        # 保存裁剪区域到文件
        out_path = f"{OUTPUT_DIR}/phase1_card{card_name.replace('','S').replace('♥','H').replace('♦','D').replace('♣','C')}_suit_region.png"
        Image.fromarray(suit_region).save(out_path)


# ========== Phase 2: 底池OCR验证 ==========
def verify_phase2_pot(img_np, img_w, img_h, idx):
    print(f"\n{'='*60}")
    print(f"Phase 2 底池区域验证 — 截图 {idx}")
    print(f"{'='*60}")
    
    # GG底池区域: x=30%-70%, y=16%-24%
    actual_w, actual_h = img_w, img_h
    
    x1 = int(0.30 * actual_w)
    y1 = int(0.16 * actual_h)
    x2 = int(0.70 * actual_w)
    y2 = int(0.24 * actual_h)
    
    pot_region = extract_region(img_np, x1, y1, x2, y2)
    
    print(f"  GG底池区域: ({x1},{y1}) → ({x2},{y2}), 尺寸: {pot_region.shape[1]}×{pot_region.shape[0]}")
    
    # 保存底池区域截图
    out_path = f"{OUTPUT_DIR}/phase2_pot_region_screenshot{idx}.png"
    Image.fromarray(pot_region).save(out_path)
    print(f"  底池区域截图已保存: {out_path}")
    
    # 分析区域内容：看是否有文字/数字
    gray = np.mean(pot_region, axis=2)
    # 暗色像素（文字通常是暗色）
    dark_pixels = int((gray < 100).sum())
    total_pixels = pot_region.shape[0] * pot_region.shape[1]
    dark_ratio = dark_pixels / total_pixels if total_pixels > 0 else 0
    
    print(f"  暗色像素比例: {dark_ratio:.3f} ({dark_pixels}/{total_pixels})")
    
    if idx == 1:
        print(f"  预期: 截图2应包含底池 '50K' 文字")
    elif idx == 0:
        print(f"  预期: 截图1是preflop，底池可能为空或显示 blinds")
    elif idx == 2:
        print(f"  预期: 截图3是preflop，底池可能为空或显示 blinds")


# ========== Phase 3: 按钮坐标验证 ==========
def verify_phase3_buttons(img_np, img_w, img_h, idx):
    print(f"\n{'='*60}")
    print(f"Phase 3 按钮坐标验证 — 截图 {idx}")
    print(f"{'='*60}")
    
    actual_w, actual_h = img_w, img_h
    
    # GG底部3按钮坐标
    gg_bottom_buttons = [
        ("弃牌/Fold", 0.181, 0.960),
        ("过牌/Check or Call", 0.500, 0.960),
        ("加注/Raise", 0.819, 0.960),
    ]
    
    # GG右侧4档预设按钮
    gg_right_buttons = [
        ("100%", 0.819, 0.751),
        ("75%", 0.819, 0.821),
        ("50%", 0.819, 0.890),
        ("33%", 0.819, 0.937),
    ]
    
    print("  GG底部3按钮坐标 (基准1080×2344):")
    for name, x_pct, y_pct in gg_bottom_buttons:
        px = int(x_pct * actual_w)
        py = int(y_pct * actual_h)
        print(f"    {name}: ({px}, {py}) — 百分比({x_pct:.3f}, {y_pct:.3f})")
    
    print("  GG右侧4档按钮坐标 (基准1080×2344):")
    for name, x_pct, y_pct in gg_right_buttons:
        px = int(x_pct * actual_w)
        py = int(y_pct * actual_h)
        print(f"    {name}: ({px}, {py}) — 百分比({x_pct:.3f}, {y_pct:.3f})")
    
    # 验证: 提取按钮区域看是否有对应文字
    # 底部按钮区域大约 y=90%-100%
    btn_region_y1 = int(0.88 * actual_h)
    btn_region_y2 = actual_h
    btn_region = img_np[btn_region_y1:btn_region_y2, :]
    
    out_path = f"{OUTPUT_DIR}/phase3_buttons_region_screenshot{idx}.png"
    Image.fromarray(btn_region).save(out_path)
    print(f"  底部按钮区域截图已保存: {out_path}")
    
    # 截图3应该有底部按钮（Hero有行动权）
    if idx == 2:
        print(f"  截图3有底部按钮（弃牌/过牌/加注），检查区域是否包含按钮")
        # 检查3个按钮位置是否有彩色像素（按钮通常是彩色的）
        for name, x_pct, y_pct in gg_bottom_buttons:
            px = int(x_pct * actual_w)
            py = int(y_pct * actual_h)
            # 检查按钮中心周围 60×60 区域
            check_size = 60
            check_region = img_np[
                max(0, py-check_size):py+check_size,
                max(0, px-check_size):px+check_size
            ]
            if check_region.size > 0:
                # 计算彩色像素比例（按钮通常有鲜艳颜色）
                r = check_region[:,:,0].astype(int)
                g = check_region[:,:,1].astype(int)
                b = check_region[:,:,2].astype(int)
                colorful = ((r > 100) & ((r - g > 30) | (r - b > 30) | (g - b > 30))).sum()
                total = check_region.shape[0] * check_region.shape[1]
                ratio = colorful / total if total > 0 else 0
                print(f"    {name} 位置 ({px},{py}): 彩色像素比例={ratio:.3f}")


# ========== Phase 4: D按钮和快速通道逻辑验证 ==========
def verify_phase4_fastlane(img_np, img_w, img_h, idx):
    print(f"\n{'='*60}")
    print(f"Phase 4 快速通道逻辑验证 — 截图 {idx}")
    print(f"{'='*60}")
    
    actual_w, actual_h = img_w, img_h
    
    # 检查快速通道前置条件
    # 1. 手牌2张 + 置信度 >= 0.85
    # 2. 底池 > 0 (OCR或缓存)
    
    print("  快速通道v2前置条件检查:")
    print("  ① 手牌2张 + 置信度 >= 0.85 → 需要本地CV识别手牌")
    print("  ② 底池 > 0 → Phase 2 OCR 或 cachedPotSize 缓存")
    
    # 截图2 (turn) 有公共牌但无手牌 → 不满足条件①
    # 截图3 (preflop, Hero有行动权) 应该有手牌 → 可能满足
    if idx == 2:
        print("  截图3是Hero行动中的preflop → 应该有手牌")
        print("  Hero手牌位置（底部中央）: 大约 x=35%-65%, y=78%-92%")
        
        hero_hand_x1 = int(0.35 * actual_w)
        hero_hand_y1 = int(0.78 * actual_h)
        hero_hand_x2 = int(0.65 * actual_w)
        hero_hand_y2 = int(0.92 * actual_h)
        
        hero_region = img_np[hero_hand_y1:hero_hand_y2, hero_hand_x1:hero_hand_x2]
        out_path = f"{OUTPUT_DIR}/phase4_hero_hand_region_screenshot{idx}.png"
        Image.fromarray(hero_region).save(out_path)
        print(f"  Hero手牌区域截图已保存: {out_path}")
        
        # 检查该区域是否有彩色像素（手牌是彩色的）
        if hero_region.size > 0:
            r = hero_region[:,:,0].astype(int)
            g = hero_region[:,:,1].astype(int)
            b = hero_region[:,:,2].astype(int)
            white_cards = ((r > 200) & (g > 200) & (b > 200)).sum()
            total = hero_region.shape[0] * hero_region.shape[1]
            ratio = white_cards / total if total > 0 else 0
            print(f"  白色卡片像素比例: {ratio:.3f} (手牌应该有高白色比例)")


# ========== 筹码区域验证 ==========
def verify_chip_regions(img_np, img_w, img_h, idx):
    print(f"\n{'='*60}")
    print(f"筹码区域坐标验证 — 截图 {idx}")
    print(f"{'='*60}")
    
    actual_w, actual_h = img_w, img_h
    
    # GG 6人桌筹码区域 (from GameModeConfig.kt)
    chip_regions = [
        ("seat 0 (左上)", 0.02, 0.24, 0.28, 0.34),
        ("seat 1 (正上)", 0.35, 0.08, 0.65, 0.16),
        ("seat 2 (右上)", 0.72, 0.24, 0.98, 0.34),
        ("seat 3 (右中)", 0.72, 0.56, 0.98, 0.66),
        ("seat 4 (正下-对手)", 0.35, 0.70, 0.65, 0.78),
        ("seat 5 (左中)", 0.02, 0.56, 0.28, 0.66),
    ]
    
    # 截图2的已知筹码（用于验证）:
    # 上左: 4,999,000 | 上右: 469,000 | 右: 584,000 | 下右: 1,807,000 | 下左: 500,000 | 左: 807,000
    
    for name, x1_pct, y1_pct, x2_pct, y2_pct in chip_regions:
        x1 = int(x1_pct * actual_w)
        y1 = int(y1_pct * actual_h)
        x2 = int(x2_pct * actual_w)
        y2 = int(y2_pct * actual_h)
        
        region = img_np[y1:y2, x1:x2]
        out_path = f"{OUTPUT_DIR}/chip_{name.replace(' ','_').replace('(','').replace(')','')}_screenshot{idx}.png"
        Image.fromarray(region).save(out_path)
        
        # 检查是否有文字（暗色像素）
        if region.size > 0:
            gray = np.mean(region, axis=2)
            text_pixels = int((gray < 150).sum())
            total = region.shape[0] * region.shape[1]
            text_ratio = text_pixels / total if total > 0 else 0
            print(f"  {name}: ({x1},{y1})→({x2},{y2}) 文字像素比={text_ratio:.3f}")


# ========== D按钮位置验证 ==========
def verify_d_button(img_np, img_w, img_h, idx):
    print(f"\n{'='*60}")
    print(f"D按钮位置验证 — 截图 {idx}")
    print(f"{'='*60}")
    
    actual_w, actual_h = img_w, img_h
    
    # D按钮通常在桌面上，靠近某个玩家
    # 截图1: D在左中区域 (靠近807,000玩家)
    # 截图2: D在左上区域 (靠近4,999,000玩家)
    # 截图3: D在右上区域 (靠近292,000玩家)
    
    # 检查整个桌面区域是否有"D"标记
    # D按钮通常是一个小圆形标记，带"D"字母
    table_region = img_np[int(0.20*actual_h):int(0.60*actual_h), int(0.10*actual_w):int(0.90*actual_w)]
    
    out_path = f"{OUTPUT_DIR}/d_button_table_region_screenshot{idx}.png"
    Image.fromarray(table_region).save(out_path)
    print(f"  桌面区域截图已保存: {out_path}")
    print(f"  D按钮是小型圆形标记，代码中通过Vision API或固定位置获取")


# ========== 主流程 ==========
def main():
    print("=" * 60)
    print("V2.9.208 本地CV验证脚本")
    print("使用真实GG牌桌截图验证4个Phase的坐标和逻辑")
    print("=" * 60)
    
    for idx, path in enumerate(SCREENSHOTS):
        if not os.path.exists(path):
            print(f"\n⚠ 截图 {idx} 不存在: {path}")
            continue
        
        img_np, img_size = load_image(path)
        img_w, img_h = img_size
        print(f"\n{'#'*60}")
        print(f"# 处理截图 {idx}: {os.path.basename(path)}")
        print(f"# 分辨率: {img_w}×{img_h}")
        print(f"{'#'*60}")
        
        verify_phase1_suit(img_np, img_w, img_h, idx)
        verify_phase2_pot(img_np, img_w, img_h, idx)
        verify_phase3_buttons(img_np, img_w, img_h, idx)
        verify_phase4_fastlane(img_np, img_w, img_h, idx)
        verify_chip_regions(img_np, img_w, img_h, idx)
        verify_d_button(img_np, img_w, img_h, idx)
    
    print(f"\n{'='*60}")
    print(f"验证完成！所有截图已保存到: {OUTPUT_DIR}")
    print(f"{'='*60}")


if __name__ == "__main__":
    main()
