# GG扑克适配状态 v2.9.206

## ✅ 已完成

### 1. 坐标系统
- [x] 底部3按钮坐标确认 (y=0.960)
- [x] All-in坐标修正 (0.819, 0.751)
- [x] 右侧4档下注按钮坐标 (100%/75%/50%/33%)
- [x] Insurance拒绝按钮坐标 (0.85, 0.55)

### 2. 按钮识别
- [x] 按钮文字双向fallback (raise↔allin, check↔call)
- [x] GG英文按钮匹配 (Fold/Check/Call/Raise/All In)

### 3. 自动下注
- [x] 下注金额自动输入 (先点%按钮，再点加注确认)
- [x] JS决策数据增加pot字段
- [x] getBetButtonAction() 计算应点击的%按钮

### 4. 特殊模式
- [x] Insurance自动拒绝
- [x] Straddle/BombPot/PKO/Rush & Cash 检测+策略

### 5. 平台配置
- [x] GG竖屏坐标配置
- [x] GG分级Rake参数化
- [x] 平台切换UI

## ⏳ 进行中

### 6. Shot Clock保护
- [ ] 检测决策倒计时
- [ ] 超时强制行动

### 7. 搓牌动画处理
- [ ] 检测squeeze状态
- [ ] 等待动画完成

### 8. 手动游戏类型选择
- [ ] 长按菜单选择游戏类型

## 📊 代码变更统计
- GameModeConfig.kt: +45行 (allin坐标、bet按钮、Insurance坐标、getBetButtonAction)
- FloatingService.kt: +44行 (下注自动输入、Insurance自动拒绝)
- poker_helper.html: +1行 (pot字段)

##  测试要点
1. 下注金额匹配：策略说raise 600，底池800 → 应点75%按钮
2. All-in坐标：短码时加注按钮变成All In → 应正确点击
3. Insurance：弹出Insurance → 应自动拒绝
4. 按钮文字变化：Check→All In → 应正确识别

