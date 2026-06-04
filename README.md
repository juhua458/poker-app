# 扑克AI助手 v1.0

德州扑克AI辅助App，基于捉鸡麻将App框架改造。

## 功能特性

- 🃏 手动选牌（2张手牌 + 0-5张公共牌）
- 🎯 场景选择（翻前开池/加注/跟注，翻后过牌/下注/全下）
- 📊 胜率计算（蒙特卡洛模拟）
- 💡 策略建议（基于v13策略引擎）
- 🪟 悬浮窗显示（横屏右侧面板）

## 技术栈

- Kotlin + Android SDK 34
- NanoHTTPD (HTTP服务器)
- WebView (策略引擎)
- MediaProjection (截屏)

## 构建

APK通过GitHub Actions自动构建，发布到Release。
