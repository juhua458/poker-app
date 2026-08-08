package com.pokerhelper.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * V2.9.200: 游戏模式配置中心
 * 支持多平台（标准/GG扑克/短牌）切换，统一管理坐标、Rake、按钮风格等平台差异
 * 所有平台差异在此处集中配置，业务层通过 getPlatformConfig() 获取当前配置
 */

// 游戏平台枚举
enum class GamePlatform(val displayName: String) {
    STANDARD("标准扑克"),
    GGPOKER("GG扑克"),
    SHORT_DECK("短牌(6+)")
}

// 游戏类型枚举
enum class GameType(val displayName: String) {
    NORMAL("常规"),
    STRADDLE("Straddle"),
    BOMB_POT("Bomb Pot"),
    PKO("PKO赏金"),
    RUSH_CASH("Rush & Cash"),
    ALL_IN_OR_FOLD("All-In or Fold")
}

// 屏幕方向
enum class ScreenOrientation {
    LANDSCAPE, PORTRAIT
}

// 坐标配置数据类
data class CoordinateConfig(
    val handCardsBase: List<Pair<Int, Int>>,       // [(x1,x2), (x1,x2)] for 2 hand cards
    val handYBase: Pair<Int, Int>,                 // (y1, y2)
    val communityCardsBase: List<Pair<Int, Int>>,  // [(x1,x2)...] for 5 community cards
    val communityYBase: Pair<Int, Int>,            // (y1, y2)
    val referenceWidth: Int,                       // 基准宽度(用于缩放)
    val referenceHeight: Int,                      // 基准高度(用于缩放)
    val orientation: ScreenOrientation,
    // V2.9.210: 扩展坐标 — 玩家位置、底池、按钮、D按钮搜索区域
    val playerNames: List<IntArray> = emptyList(),       // 6 seats: [x1,y1,x2,y2]
    val playerChips: List<IntArray> = emptyList(),       // 6 seats: [x1,y1,x2,y2]
    val potLabel: IntArray = intArrayOf(),               // 底池文字 [x1,y1,x2,y2]
    val potAmount: IntArray = intArrayOf(),              // 底池金额 [x1,y1,x2,y2]
    val actionButtons: List<IntArray> = emptyList(),     // 底部操作按钮 [x1,y1,x2,y2]
    val betButtons: List<IntArray> = emptyList(),        // 下注按钮(4档) [x1,y1,x2,y2]
    val dealerSearchAreas: List<IntArray> = emptyList(), // D按钮搜索区域(6个座位附近)
    val topNavBar: List<IntArray> = emptyList()          // 顶部导航栏按钮
)

// Rake配置
data class RakeConfig(
    val percentage: Double = 0.05,                 // 默认5%
    val caps: Map<String, Int> = emptyMap()        // level -> cap in BB
)

// 平台完整配置
data class PlatformConfig(
    val platform: GamePlatform,
    val coordinates: CoordinateConfig,
    val rake: RakeConfig = RakeConfig(),
    val supportedGameTypes: List<GameType> = listOf(GameType.NORMAL),
    val hasBetSlider: Boolean = true,              // 是否有下注滑块（GG没有）
    val hasCardSqueeze: Boolean = false,            // 是否有搓牌动画（GG有）
    val buttonZoomFactor: Double = 1.0,            // 按钮行动时放大倍数（GG放大10%）
    val preferEnglishButtons: Boolean = false       // 按钮是否优先英文（GG用英文）
)

object GameModeConfig {
    private const val TAG = "GameModeConfig"
    private const val PREFS_NAME = "game_mode_config"
    private const val KEY_PLATFORM = "current_platform"
    private const val KEY_GAME_TYPE = "current_game_type"

    private var prefs: SharedPreferences? = null

    // 当前平台（运行时变量，默认STANDARD保证向后兼容）
    var currentPlatform: GamePlatform = GamePlatform.STANDARD
        private set
    var currentGameType: GameType = GameType.NORMAL
        private set

    /**
     * 初始化，从SharedPreferences读取用户上次选择的平台
     */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedPlatformName = prefs?.getString(KEY_PLATFORM, GamePlatform.STANDARD.name) ?: GamePlatform.STANDARD.name
        val savedGameTypeName = prefs?.getString(KEY_GAME_TYPE, GameType.NORMAL.name) ?: GameType.NORMAL.name
        try {
            currentPlatform = GamePlatform.valueOf(savedPlatformName)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "无效平台名称: $savedPlatformName，使用默认STANDARD")
            currentPlatform = GamePlatform.STANDARD
        }
        try {
            currentGameType = GameType.valueOf(savedGameTypeName)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "无效游戏类型: $savedGameTypeName，使用默认NORMAL")
            currentGameType = GameType.NORMAL
        }
        Log.i(TAG, "GameModeConfig初始化: platform=$currentPlatform, gameType=$currentGameType")
    }

    /**
     * 切换平台，持久化到SharedPreferences
     */
    fun setPlatform(platform: GamePlatform) {
        if (currentPlatform == platform) return
        currentPlatform = platform
        prefs?.edit()?.putString(KEY_PLATFORM, platform.name)?.apply()
        Log.i(TAG, "切换平台: $platform")
    }

    /**
     * 切换游戏类型，持久化到SharedPreferences
     */
    fun setGameType(gameType: GameType) {
        if (currentGameType == gameType) return
        currentGameType = gameType
        prefs?.edit()?.putString(KEY_GAME_TYPE, gameType.name)?.apply()
        Log.i(TAG, "切换游戏类型: $gameType")
    }

    // ============================================================
    // 坐标配置
    // ============================================================

    // GG扑克竖屏坐标配置（1080×2344基准，基于10张GG截图实测）
    // V2.9.210: 完整坐标体系 — 含玩家位置、底池、按钮、D按钮搜索区域
    private val GG_PORTRAIT_COORDS = CoordinateConfig(
        // GG竖屏：手牌在底部中央，两张牌左右排列
        handCardsBase = listOf(
            Pair(75, 200),   // 手牌0 x范围（V2.9.210校准）
            Pair(170, 310)   // 手牌1 x范围（V2.9.210校准）
        ),
        handYBase = Pair(1760, 2000),  // V2.9.210校准
        // 公共牌在屏幕中部（V2.9.210校准）
        communityCardsBase = listOf(
            Pair(180, 325),   // 公共牌0
            Pair(325, 460),   // 公共牌1
            Pair(460, 595),   // 公共牌2
            Pair(595, 730),   // 公共牌3
            Pair(730, 870)    // 公共牌4
        ),
        communityYBase = Pair(1030, 1290),
        referenceWidth = 1080,
        referenceHeight = 2344,
        orientation = ScreenOrientation.PORTRAIT,
        // V2.9.210: 6个座位的玩家名字区域 [x1,y1,x2,y2]
        playerNames = listOf(
            intArrayOf(440, 500, 620, 540),    // 座位1 顶部中间
            intArrayOf(10, 870, 200, 910),     // 座位2 左侧
            intArrayOf(810, 850, 1030, 890),   // 座位3 右上
            intArrayOf(10, 1440, 200, 1480),   // 座位4 左下
            intArrayOf(800, 1470, 1010, 1510), // 座位5 右下
            intArrayOf(135, 1920, 265, 1960)   // 座位6 Hero底部
        ),
        // V2.9.210: 6个座位的筹码区域 [x1,y1,x2,y2]
        playerChips = listOf(
            intArrayOf(460, 545, 620, 580),    // 座位1
            intArrayOf(10, 910, 200, 950),     // 座位2
            intArrayOf(860, 895, 1030, 935),   // 座位3
            intArrayOf(25, 1485, 200, 1520),   // 座位4
            intArrayOf(830, 1515, 1010, 1555), // 座位5
            intArrayOf(170, 1965, 295, 2005)   // 座位6 Hero
        ),
        // V2.9.210: 底池区域
        potLabel = intArrayOf(415, 955, 540, 1000),     // 底池文字
        potAmount = intArrayOf(430, 995, 550, 1050),    // 底池金额
        // V2.9.210: 底部操作按钮 [x1,y1,x2,y2]
        actionButtons = listOf(
            intArrayOf(20, 2190, 370, 2340),   // 左按钮（让牌/弃牌）
            intArrayOf(390, 2190, 710, 2340)   // 右按钮（让牌/跟注/全押）
        ),
        // V2.9.210: 下注按钮（右侧4档）[x1,y1,x2,y2]
        betButtons = listOf(
            intArrayOf(730, 1690, 1080, 1820), // 100%
            intArrayOf(730, 1885, 1080, 2005), // 75%
            intArrayOf(730, 2020, 1080, 2140), // 50%
            intArrayOf(730, 2195, 1080, 2320)  // 33%
        ),
        // V2.9.210: D按钮搜索区域（6个座位附近）[x1~x2, y1~y2]
        dealerSearchAreas = listOf(
            intArrayOf(400, 550, 650, 620),    // 座位1附近
            intArrayOf(0, 880, 220, 960),      // 座位2附近
            intArrayOf(780, 860, 1040, 940),   // 座位3附近
            intArrayOf(0, 1450, 220, 1530),    // 座位4附近
            intArrayOf(780, 1480, 1020, 1560), // 座位5附近
            intArrayOf(100, 1930, 300, 2010)   // 座位6/Hero附近
        ),
        // V2.9.210: 顶部导航栏 [x1,y1,x2,y2]
        topNavBar = listOf(
            intArrayOf(155, 130, 305, 220),    // 手牌提示
            intArrayOf(375, 140, 455, 210),    // 暂停
            intArrayOf(480, 140, 555, 210),    // 关闭
            intArrayOf(580, 140, 660, 210),    // 首页
            intArrayOf(685, 140, 765, 210)     // +号
        )
    )

    // 标准扑克横屏坐标配置（与当前V2.9.199一致）
    private val STANDARD_LANDSCAPE_COORDS = CoordinateConfig(
        handCardsBase = listOf(Pair(85, 180), Pair(180, 295)),
        handYBase = Pair(1780, 1940),
        communityCardsBase = listOf(
            Pair(155, 315), Pair(305, 465), Pair(455, 615),
            Pair(605, 765), Pair(755, 915)
        ),
        communityYBase = Pair(1060, 1210),
        referenceWidth = 1080,
        referenceHeight = 2344,
        orientation = ScreenOrientation.LANDSCAPE
    )

    fun getCoordinateConfig(): CoordinateConfig {
        return when (currentPlatform) {
            GamePlatform.GGPOKER -> GG_PORTRAIT_COORDS
            GamePlatform.SHORT_DECK -> STANDARD_LANDSCAPE_COORDS  // 短牌沿用横屏坐标
            GamePlatform.STANDARD -> STANDARD_LANDSCAPE_COORDS
        }
    }

    fun getRakeConfig(): RakeConfig {
        return when (currentPlatform) {
            GamePlatform.GGPOKER -> RakeConfig(
                percentage = 0.05,
                caps = mapOf(
                    "NL2" to 20, "NL5" to 50, "NL10" to 100,
                    "NL25" to 200, "NL50" to 400, "NL100" to 500,
                    "NL200" to 600, "NL500" to 830
                )
            )
            else -> RakeConfig()
        }
    }

    fun getPlatformConfig(): PlatformConfig {
        return when (currentPlatform) {
            GamePlatform.GGPOKER -> PlatformConfig(
                platform = GamePlatform.GGPOKER,
                coordinates = GG_PORTRAIT_COORDS,
                rake = getRakeConfig(),
                supportedGameTypes = listOf(
                    GameType.NORMAL, GameType.STRADDLE, GameType.BOMB_POT,
                    GameType.PKO, GameType.RUSH_CASH
                ),
                hasBetSlider = false,
                hasCardSqueeze = true,
                buttonZoomFactor = 1.1,
                preferEnglishButtons = true
            )
            GamePlatform.SHORT_DECK -> PlatformConfig(
                platform = GamePlatform.SHORT_DECK,
                coordinates = STANDARD_LANDSCAPE_COORDS,
                supportedGameTypes = listOf(GameType.NORMAL),
                hasBetSlider = false
            )
            GamePlatform.STANDARD -> PlatformConfig(
                platform = GamePlatform.STANDARD,
                coordinates = STANDARD_LANDSCAPE_COORDS,
                supportedGameTypes = listOf(GameType.NORMAL, GameType.STRADDLE),
                hasBetSlider = true
            )
        }
    }

    // ============ V2.9.208: 底池区域坐标（百分比，用于本地OCR） ============

    /**
     * 获取底池显示区域（屏幕百分比坐标）
     * @return (x1%, y1%, x2%, y2%) 底池文字所在区域
     */
    fun getPotRegionPct(): Pair<Pair<Double, Double>, Pair<Double, Double>> {
        return when (currentPlatform) {
            GamePlatform.GGPOKER -> (0.25 to 0.35) to (0.75 to 0.42)  // GG竖屏：桌面中央绿色pill区域
            GamePlatform.SHORT_DECK -> (0.35 to 0.13) to (0.65 to 0.21)
            GamePlatform.STANDARD -> (0.35 to 0.13) to (0.65 to 0.21)
        }
    }

    /**
     * V2.9.208: 获取底池区域的像素坐标（基于实际屏幕尺寸）
     */
    fun getPotRegionPixels(screenW: Int, screenH: Int): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val pct = getPotRegionPct()
        val topLeft = pct.first
        val bottomRight = pct.second
        val x1 = (topLeft.first * screenW).toInt()
        val y1 = (topLeft.second * screenH).toInt()
        val x2 = (bottomRight.first * screenW).toInt()
        val y2 = (bottomRight.second * screenH).toInt()
        return (x1 to y1) to (x2 to y2)
    }

    // ============ V2.9.208: 玩家筹码区域坐标（屏幕百分比） ============
    // GG竖屏6人桌：上3人+下2人+自己，简化为固定区域

    /**
     * 获取6个座位的筹码显示区域（屏幕百分比）
     * 返回 [(x1%, y1%, x2%, y2%), ...] 对应 seat 0-5
     */
    fun getChipRegionPcts(): List<Pair<Pair<Double, Double>, Pair<Double, Double>>> {
        return when (currentPlatform) {
            GamePlatform.GGPOKER -> listOf(
                (0.02 to 0.24) to (0.28 to 0.34),  // seat 0: 左上
                (0.35 to 0.65) to (0.08 to 0.16),  // seat 1: 正上
                (0.72 to 0.98) to (0.24 to 0.34),  // seat 2: 右上
                (0.72 to 0.98) to (0.56 to 0.66),  // seat 3: 右中
                (0.35 to 0.65) to (0.70 to 0.78),  // seat 4: 正下（对手）
                (0.02 to 0.28) to (0.56 to 0.66)   // seat 5: 左中
            )
            else -> listOf(
                (0.05 to 0.25) to (0.30 to 0.40),
                (0.30 to 0.70) to (0.15 to 0.25),
                (0.75 to 0.95) to (0.30 to 0.40),
                (0.75 to 0.95) to (0.55 to 0.65),
                (0.30 to 0.70) to (0.70 to 0.80),
                (0.05 to 0.25) to (0.55 to 0.65)
            )
        }
    }

    /**
     * Auto-tap fallback坐标（按屏幕百分比）
     * GG竖屏 vs 标准横屏，按钮位置不同
     */
    fun getAutoTapFallback(action: String, screenW: Int, screenH: Int): Pair<Int, Int> {
        return when (currentPlatform) {
            GamePlatform.GGPOKER -> when (action) {
                // V2.9.205: Y坐标上移，按钮几何中心y=2250 (96.0%)
                "fold" -> (screenW * 0.181).toInt() to (screenH * 0.960).toInt()
                "check" -> (screenW * 0.500).toInt() to (screenH * 0.960).toInt()
                "call", "weak_call" -> (screenW * 0.500).toInt() to (screenH * 0.960).toInt()
                "raise", "raise_big" -> (screenW * 0.819).toInt() to (screenH * 0.960).toInt()
                // V2.9.206: 修正allin坐标，使用100%下注按钮实测位置(81.9%, 75.1%)
                "allin" -> (screenW * 0.819).toInt() to (screenH * 0.751).toInt()
                // V2.9.206: GG右侧4档下注预设按钮
                "bet_100" -> (screenW * 0.819).toInt() to (screenH * 0.751).toInt()
                "bet_75" -> (screenW * 0.819).toInt() to (screenH * 0.821).toInt()
                "bet_50" -> (screenW * 0.819).toInt() to (screenH * 0.890).toInt()
                "bet_33" -> (screenW * 0.819).toInt() to (screenH * 0.937).toInt()
                else -> (screenW * 0.500).toInt() to (screenH * 0.960).toInt()
            }
            else -> when (action) {
                "fold" -> (screenW * 0.17).toInt() to (screenH * 0.88).toInt()
                "check" -> (screenW * 0.50).toInt() to (screenH * 0.88).toInt()
                "call", "weak_call" -> (screenW * 0.50).toInt() to (screenH * 0.88).toInt()
                "raise", "raise_big" -> (screenW * 0.83).toInt() to (screenH * 0.88).toInt()
                "allin" -> (screenW * 0.50).toInt() to (screenH * 0.92).toInt()
                else -> (screenW * 0.50).toInt() to (screenH * 0.88).toInt()
            }
        }
    }

    /**
     * 当前平台是否为竖屏
     */
    fun isPortrait(): Boolean = getCoordinateConfig().orientation == ScreenOrientation.PORTRAIT

    /**
     * V2.9.206: 根据下注金额和底池计算应点击的下注预设按钮
     * @param sizing 策略推荐的下注金额
     * @param pot 当前底池大小
     * @return 按钮action字符串: bet_100/bet_75/bet_50/bet_33
     */
    fun getBetButtonAction(sizing: Int, pot: Int): String {
        if (pot <= 0) return "bet_100" // 底池为0时默认100%
        val ratio = sizing.toDouble() / pot.toDouble()
        return when {
            ratio >= 0.90 -> "bet_100"  // 90%以上→100%按钮
            ratio >= 0.65 -> "bet_75"   // 65%-90%→75%按钮
            ratio >= 0.40 -> "bet_50"   // 40%-65%→50%按钮
            else -> "bet_33"            // 40%以下→33%按钮
        }
    }

    /**
     * V2.9.206: GG扑克Insurance拒绝按钮坐标（约在屏幕右侧中间）
     */
    fun getInsuranceDeclinePosition(screenW: Int, screenH: Int): Pair<Int, Int> {
        // Insurance拒绝按钮大约在屏幕右侧85%位置，y坐标约55%
        return (screenW * 0.85).toInt() to (screenH * 0.55).toInt()
    }

    // ============ V2.9.210: 扩展坐标访问方法 ============

    /** 获取6个座位的玩家名字区域坐标 */
    fun getPlayerNameRegions(): List<IntArray> = getCoordinateConfig().playerNames

    /** 获取6个座位的筹码区域坐标 */
    fun getPlayerChipRegions(): List<IntArray> = getCoordinateConfig().playerChips

    /** 获取底池金额区域坐标 */
    fun getPotAmountRegion(): IntArray = getCoordinateConfig().potAmount

    /** 获取底部操作按钮坐标 */
    fun getActionButtons(): List<IntArray> = getCoordinateConfig().actionButtons

    /** 获取下注按钮坐标（4档） */
    fun getBetButtons(): List<IntArray> = getCoordinateConfig().betButtons

    /** 获取D按钮搜索区域（6个座位附近） */
    fun getDealerSearchAreas(): List<IntArray> = getCoordinateConfig().dealerSearchAreas

    /**
     * 根据D按钮位置推算SB/BB座位
     * @param dealerSeatIndex D按钮所在座位索引(0-5)，-1表示未找到
     * @return Pair(sbSeatIndex, bbSeatIndex)，未找到返回(-1,-1)
     */
    fun deduceBlindSeats(dealerSeatIndex: Int): Pair<Int, Int> {
        if (dealerSeatIndex < 0) return -1 to -1
        val totalSeats = 6
        val sbSeat = (dealerSeatIndex + 1) % totalSeats
        val bbSeat = (dealerSeatIndex + 2) % totalSeats
        return sbSeat to bbSeat
    }
}