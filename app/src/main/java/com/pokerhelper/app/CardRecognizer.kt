package com.pokerhelper.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 本地牌面识别器 V2 - Rank-only NCC匹配
 * V2.9.197: 混合方案核心
 * - 分离手牌/公共牌rank indicator模板池（解决尺寸差异导致的识别失败）
 * - 只识别rank，suit由云端API补充（混合方案）
 * - 返回置信度分数，用于自适应API调用策略
 * - <300ms完成，用于快速锁定+减少API依赖
 * V2.9.200: 坐标由 GameModeConfig 动态提供，支持多平台（标准/GG/短牌）切换
 */
class CardRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "CardRecognizer"

        private const val RANK_MATCH_THRESHOLD = 0.65  // V2.9.197: 降低阈值让低置信度走API兜底

        // V2.9.184: 运行时缩放因子
        private var scaleX = 1.0f
        private var scaleY = 1.0f
        private var COMMUNITY_Y = 1060 to 1210
        private var COMMUNITY_CARDS = listOf(155 to 315, 305 to 465, 455 to 615, 605 to 765, 755 to 915)
        private var HAND_Y = 1780 to 1940
        private var HAND_CARDS = listOf(85 to 180, 180 to 295)
        // V2.9.200: 记录当前坐标对应的平台，用于切换时强制刷新
        private var currentPlatform: GamePlatform = GamePlatform.STANDARD

        /**
         * V2.9.200: 应用当前游戏模式坐标 + 按屏幕尺寸缩放
         * 平台切换或屏幕尺寸变化时调用
         */
        fun applyGameMode() {
            val config = GameModeConfig.getCoordinateConfig()
            currentPlatform = GameModeConfig.currentPlatform
            COMMUNITY_CARDS = config.communityCardsBase.map { (x1, x2) -> (x1 * scaleX).toInt() to (x2 * scaleX).toInt() }
            COMMUNITY_Y = (config.communityYBase.first * scaleY).toInt() to (config.communityYBase.second * scaleY).toInt()
            HAND_CARDS = config.handCardsBase.map { (x1, x2) -> (x1 * scaleX).toInt() to (x2 * scaleX).toInt() }
            HAND_Y = (config.handYBase.first * scaleY).toInt() to (config.handYBase.second * scaleY).toInt()
            Log.i(TAG, "applyGameMode: platform=$currentPlatform orientation=${config.orientation} scaleX=$scaleX scaleY=$scaleY")
        }

        /**
         * V2.9.200: 更新屏幕尺寸并应用当前游戏模式坐标
         */
        fun updateScreenSize(width: Int, height: Int) {
            val config = GameModeConfig.getCoordinateConfig()
            currentPlatform = GameModeConfig.currentPlatform
            scaleX = width.toFloat() / config.referenceWidth
            scaleY = height.toFloat() / config.referenceHeight
            COMMUNITY_Y = (config.communityYBase.first * scaleY).toInt() to (config.communityYBase.second * scaleY).toInt()
            COMMUNITY_CARDS = config.communityCardsBase.map { (x1, x2) -> (x1 * scaleX).toInt() to (x2 * scaleX).toInt() }
            HAND_Y = (config.handYBase.first * scaleY).toInt() to (config.handYBase.second * scaleY).toInt()
            HAND_CARDS = config.handCardsBase.map { (x1, x2) -> (x1 * scaleX).toInt() to (x2 * scaleX).toInt() }
            Log.i(TAG, "updateScreenSize: ${width}x${height} platform=$currentPlatform scaleX=$scaleX scaleY=$scaleY")
        }

        // ============ V2.9.210: D按钮检测 ============
    fun detectDealerButton(screenshot: Bitmap, searchAreas: List<IntArray>): Int {
        for ((seatIdx, area) in searchAreas.withIndex()) {
            val x1 = area[0].coerceIn(0, screenshot.width - 1)
            val y1 = area[1].coerceIn(0, screenshot.height - 1)
            val x2 = area[2].coerceIn(x1 + 1, screenshot.width)
            val y2 = area[3].coerceIn(y1 + 1, screenshot.height)
            val w = x2 - x1; val h = y2 - y1
            val pixels = IntArray(w * h)
            try { screenshot.getPixels(pixels, 0, w, x1, y1, w, h) } catch (_: Exception) { continue }
            var yellowCount = 0
            for (p in pixels) {
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                if (r > 180 && g > 150 && b < 100) yellowCount++
            }
            val density = yellowCount.toDouble() / pixels.size
            if (density > 0.03) {
                Log.d(TAG, "D按钮检测: 座位$seatIdx, 黄色密度=${String.format("%.3f", density)}")
                return seatIdx
            }
        }
        Log.d(TAG, "D按钮检测: 未找到")
        return -1
    }

    // ============ V2.9.210: 行动者白色光圈检测 ============
    fun detectActivePlayer(screenshot: Bitmap, nameRegions: List<IntArray>, chipRegions: List<IntArray>): Int {
        var bestSeat = -1
        var bestWhiteDensity = 0.0
        for (seatIdx in nameRegions.indices) {
            val nameArea = nameRegions[seatIdx]
            val chipArea = chipRegions[seatIdx]
            val unionX1 = (minOf(nameArea[0], chipArea[0]) - 10).coerceIn(0, screenshot.width - 1)
            val unionY1 = (minOf(nameArea[1], chipArea[1]) - 10).coerceIn(0, screenshot.height - 1)
            val unionX2 = (maxOf(nameArea[2], chipArea[2]) + 10).coerceIn(unionX1 + 1, screenshot.width)
            val unionY2 = (maxOf(nameArea[3], chipArea[3]) + 10).coerceIn(unionY1 + 1, screenshot.height)
            val w = unionX2 - unionX1; val h = unionY2 - unionY1
            if (w < 10 || h < 10) continue
            val pixels = IntArray(w * h)
            try { screenshot.getPixels(pixels, 0, w, unionX1, unionY1, w, h) } catch (_: Exception) { continue }
            val bandW = 5
            var whiteCount = 0
            var edgeTotal = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val isEdge = x < bandW || x >= w - bandW || y < bandW || y >= h - bandW
                    if (!isEdge) continue
                    edgeTotal++
                    val p = pixels[y * w + x]
                    val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                    if (r > 200 && g > 200 && b > 200) whiteCount++
                }
            }
            if (edgeTotal == 0) continue
            val density = whiteCount.toDouble() / edgeTotal
            if (density > bestWhiteDensity) { bestWhiteDensity = density; bestSeat = seatIdx }
        }
        val threshold = 0.05
        if (bestWhiteDensity >= threshold) {
            Log.d(TAG, "行动者检测: 座位$bestSeat, 白色密度=${String.format("%.3f", bestWhiteDensity)}")
            return bestSeat
        }
        Log.d(TAG, "行动者检测: 无活跃玩家, 最高密度=${String.format("%.3f", bestWhiteDensity)}")
        return -1
    }
    } // end companion object

    // V2.9.197: Rank-only模板 — 从截图rank indicator区域提取
    private data class RankTemplate(val grayPixels: DoubleArray, val width: Int, val height: Int)

    // 分离的模板池：手牌和公共牌的rank indicator形状不同，必须分开
    private val handRankTemplates = mutableMapOf<String, MutableList<RankTemplate>>()   // rank -> [templates]
    private val commRankTemplates = mutableMapOf<String, MutableList<RankTemplate>>()   // rank -> [templates]
    private var isInitialized = false

    fun init() {
        if (isInitialized) return

        // V2.9.197: 加载手牌rank indicator模板
        loadRankTemplates("card_templates/rank_hand", handRankTemplates, "手牌")

        // V2.9.197: 加载公共牌rank indicator模板
        loadRankTemplates("card_templates/rank_community", commRankTemplates, "公共牌")

        isInitialized = true
        val hRanks = handRankTemplates.keys.sorted()
        val cRanks = commRankTemplates.keys.sorted()
        Log.i(TAG, "Rank模板加载完成: 手牌${handRankTemplates.values.sumOf { it.size }}个(${hRanks.size}种rank:${hRanks.joinToString()}) | 公共牌${commRankTemplates.values.sumOf { it.size }}个(${cRanks.size}种rank:${cRanks.joinToString()})")
    }

    private fun loadRankTemplates(dir: String, map: MutableMap<String, MutableList<RankTemplate>>, label: String) {
        try {
            val files = context.assets.list(dir) ?: return
            var loaded = 0
            for (filename in files.sorted()) {
                if (!filename.endsWith(".jpg") && !filename.endsWith(".png")) continue
                try {
                    val inputStream: InputStream = context.assets.open("$dir/$filename")
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream.close()
                    if (bitmap != null) {
                        val grayPixels = bitmapToGrayDouble(bitmap)
                        val tpl = RankTemplate(grayPixels, bitmap.width, bitmap.height)

                        // 从文件名提取rank（格式: {idx}_{hand|comm}{cardIdx}_rank.jpg）
                        val rank = extractRankFromFilename(filename, dir)
                        if (rank.isNotEmpty()) {
                            map.getOrPut(rank) { mutableListOf() }.add(tpl)
                            loaded++
                        }
                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load $label template: $filename", e)
                }
            }
            Log.i(TAG, "$label rank模板加载: $loaded")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to list $label templates in $dir", e)
        }
    }

    /**
     * 从文件名推断rank — 使用ground truth映射表
     * 文件名格式: {idx}_{hand|comm}{cardIdx}_rank.jpg
     * 通过idx+cardIdx查ground truth获取rank
     */
    private fun extractRankFromFilename(filename: String, dir: String): String {
        // V2.9.199: ground truth映射 — 15个截图(idx 0-14)，仅同一桌面(1d58bed7)截图
        // 已清除不同桌面的旧模板(idx 0-8 from 06-03)
        val groundTruthHands = arrayOf(
            arrayOf("J","5"),   // 00: 06-13-16-12-04 J♠ 5♠
            arrayOf("3","2"),   // 01: 06-13-12-08-56 3♠ 2♣
            arrayOf("A","4"),   // 02: 06-13-12-05-28 A♠ 4♦
            arrayOf("A","2"),   // 03: 06-13-03-07-43 A♥ 2♦
            arrayOf("K","Q"),   // 04: 06-13-02-33-09 K♣ Q♦
            arrayOf("7","4"),   // 05: 06-13-02-28-21 7♥ 4♥
            arrayOf("K","9"),   // 06: 06-13-02-26-11 K♦ 9♦
            arrayOf("Q","5"),   // 07: 06-13-02-24-21 Q♣ 5♦
            arrayOf("7","7"),   // 08: 06-13-02-20-54 7♠ 7♦
            arrayOf("A","8"),   // 09: 06-12-22-45-58 A♠ 8♠
            arrayOf("A","7"),   // 10: 06-12-02-29-08 A♦ 7♥
            arrayOf("K","K"),   // 11: 06-12-03-04-45 K♠ K♥
            arrayOf("K","J"),   // 12: 06-12-03-49-30 K♥ J♠
            arrayOf("K","J"),   // 13: 06-12-03-49-50 K♥ J♠
            arrayOf("A","6")    // 14: 06-12-22-42-10 A♦ 6♠
        )
        val groundTruthBoards = arrayOf(
            arrayOf("3","8","K"),               // 00: 06-13-16-12-04 3♥ 8♦ K♣
            arrayOf("8","10","6"),              // 01: 06-13-12-08-56 8♦ 10♣ 6♣
            arrayOf("2","J","2","10"),          // 02: 06-13-12-05-28 2♥ J♣ 2♠ 10♥
            arrayOf("5","K","J"),               // 03: 06-13-03-07-43 5♠ K♦ J♦
            arrayOf("8","5","7","5","7"),       // 04: 06-13-02-33-09 8♣ 5♠ 7♦ 5♣ 7♥
            arrayOf("9","4","10"),              // 05: 06-13-02-28-21 9♠ 4♠ 10♦
            arrayOf("10","A","9","8","7"),      // 06: 06-13-02-26-11 10♥ A♥ 9♥ 8♦ 7♣
            arrayOf("9","6","9"),               // 07: 06-13-02-24-21 9♦ 6♦ 9♣
            arrayOf("8","8","A","3","9"),       // 08: 06-13-02-20-54 8♣ 8♥ A♠ 3♦ 9♠
            arrayOf("Q","A","Q","K"),           // 09: 06-12-22-45-58 Q♥ A♥ Q♠ K
            arrayOf("9","6","K","Q","4"),       // 10: 06-12-02-29-08 9♥ 6♦ K♠ Q♠ 4♥
            arrayOf("J","Q","7","9"),           // 11: 06-12-03-04-45 J♣ Q♥ 7♠ 9♦
            arrayOf("5","8","A","6"),           // 12: 06-12-03-49-30 5♥ 8♥ A♣ 6♣
            arrayOf("5","8","A","6","7"),       // 13: 06-12-03-49-50 5♥ 8♥ A♣ 6♣ 7♠
            arrayOf("7","A","10")               // 14: 06-12-22-42-10 7♠ A♠ 10♦
        )

        // 解析文件名: 00_hand0_rank.jpg -> idx=0, type=hand, cardIdx=0
        val parts = filename.split("_")
        if (parts.size < 3) return ""
        val idx = parts[0].toIntOrNull() ?: return ""
        val typeAndCard = parts[1] // "hand0", "hand1", "comm0", "comm1", etc.

        if (dir.contains("rank_hand")) {
            val cardIdx = typeAndCard.removePrefix("hand").toIntOrNull() ?: return ""
            if (idx < groundTruthHands.size && cardIdx < groundTruthHands[idx].size) {
                return groundTruthHands[idx][cardIdx]
            }
        } else if (dir.contains("rank_community")) {
            val cardIdx = typeAndCard.removePrefix("comm").toIntOrNull() ?: return ""
            if (idx < groundTruthBoards.size && cardIdx < groundTruthBoards[idx].size) {
                return groundTruthBoards[idx][cardIdx]
            }
        }
        return ""
    }

    /**
     * 识别整屏截图中的所有牌 — V2.9.197混合方案入口
     * @return HybridRecognitionResult 包含rank识别结果+最低置信度
     */
    fun recognizeAll(screenshot: Bitmap): HybridRecognitionResult {
        if (!isInitialized) init()
        val t0 = System.currentTimeMillis()

        // 识别公共牌
        val communityCards = mutableListOf<IdentifiedCard>()
        val allConfidences = mutableListOf<Float>()

        for ((index, xRange) in COMMUNITY_CARDS.withIndex()) {
            val (x1, x2) = xRange
            val (y1, y2) = COMMUNITY_Y
            if (hasCardAt(screenshot, x1, y1, x2, y2)) {
                val card = recognizeRank(screenshot, x1, y1, x2, y2, isHand = false)
                if (card != null) {
                    communityCards.add(card.copy(position = index))
                    allConfidences.add(card.confidence)
                }
            }
        }

        // 识别手牌
        val handCards = mutableListOf<IdentifiedCard>()
        for ((index, xRange) in HAND_CARDS.withIndex()) {
            val (x1, x2) = xRange
            val (y1, y2) = HAND_Y
            if (hasCardAt(screenshot, x1, y1, x2, y2)) {
                val card = recognizeRank(screenshot, x1, y1, x2, y2, isHand = true)
                if (card != null) {
                    handCards.add(card.copy(position = index))
                    allConfidences.add(card.confidence)
                }
            }
        }

        val elapsed = System.currentTimeMillis() - t0
        val minConfidence = if (allConfidences.isEmpty()) 0f else allConfidences.min()

        Log.d(TAG, "本地CV: ${elapsed}ms hand=${handCards.map{"${it.rank}${it.suit}(${String.format("%.2f",it.confidence)})"}} board=${communityCards.map{"${it.rank}${it.suit}(${String.format("%.2f",it.confidence)})"}} minConf=$minConfidence")

        return HybridRecognitionResult(
            communityCards = communityCards,
            handCards = handCards,
            minConfidence = minConfidence,
            elapsedMs = elapsed
        )
    }

    /**
     * 检测指定区域是否有牌
     */
    private fun hasCardAt(bmp: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        val safeX1 = x1.coerceIn(0, bmp.width - 1)
        val safeY1 = y1.coerceIn(0, bmp.height - 1)
        val safeX2 = x2.coerceIn(safeX1 + 1, bmp.width)
        val safeY2 = y2.coerceIn(safeY1 + 1, bmp.height)

        val w = safeX2 - safeX1
        val h = safeY2 - safeY1
        val pixels = IntArray(w * h)
        try {
            bmp.getPixels(pixels, 0, w, safeX1, safeY1, w, h)
        } catch (_: Exception) { return false }

        // 计算灰度标准差
        var sum = 0.0
        var sumSq = 0.0
        val n = pixels.size.toDouble()
        for (p in pixels) {
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            val gray = 0.299 * r + 0.587 * g + 0.114 * b
            sum += gray
            sumSq += gray * gray
        }
        val mean = sum / n
        val variance = sumSq / n - mean * mean
        return variance > 400.0  // std > 20
    }

    /**
     * V2.9.197: 只识别rank（NCC匹配），suit留给API
     * 从裁剪区域中提取rank indicator子区域，与rank模板匹配
     */
    private fun recognizeRank(bmp: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int, isHand: Boolean): IdentifiedCard? {
        val safeX1 = x1.coerceIn(0, bmp.width - 1)
        val safeY1 = y1.coerceIn(0, bmp.height - 1)
        val safeX2 = x2.coerceIn(safeX1 + 1, bmp.width)
        val safeY2 = y2.coerceIn(safeY1 + 1, bmp.height)

        val w = safeX2 - safeX1
        val h = safeY2 - safeY1
        val pixels = IntArray(w * h)
        try {
            bmp.getPixels(pixels, 0, w, safeX1, safeY1, w, h)
        } catch (_: Exception) { return null }

        // 提取rank indicator子区域
        val rankGray = extractRankIndicator(pixels, w, h, isHand) ?: return null
        val rankW = rankGray.width
        val rankH = rankGray.height

        // 选择对应的模板池
        val templatePool = if (isHand) handRankTemplates else commRankTemplates
        if (templatePool.isEmpty()) {
            val poolLabel = if (isHand) "手牌" else "公共牌"
            Log.w(TAG, "${poolLabel}模板池为空")
            return null
        }

        // NCC匹配 — 对每个rank找最佳模板分数
        var bestRank = ""
        var bestScore = 0.0

        for ((rank, tplList) in templatePool) {
            var rankBest = 0.0
            for (tpl in tplList) {
                // 缩放输入到模板尺寸（处理分辨率差异）
                val scaled = if (rankW != tpl.width || rankH != tpl.height) {
                    resizeDoubleArray(rankGray.grayPixels, tpl.width, tpl.height)
                } else {
                    rankGray.grayPixels
                }
                val score = nccMatch(scaled, tpl.grayPixels)
                if (score > rankBest) rankBest = score
            }
            if (rankBest > bestScore) {
                bestScore = rankBest
                bestRank = rank
            }
        }

        // NCC score范围[-1,1]，映射到[0,1]作为置信度
        val confidence = ((bestScore + 1.0) / 2.0).toFloat()

        // V2.9.208: 花色识别（颜色+形状分析）
        val (suit, suitSym) = detectSuit(pixels, w, h, isHand)

        if (bestScore < RANK_MATCH_THRESHOLD) {
            Log.w(TAG, "Rank匹配分数过低: ${String.format("%.3f", bestScore)} ($bestRank) suit=$suit")
            // 仍然返回结果，但置信度低 → 混合方案会用API兜底
            return IdentifiedCard(
                rank = if (bestRank == "10") "T" else if (bestRank.isNotEmpty()) bestRank else "?",
                suit = suit,
                suitSymbol = suitSym,
                fullKey = if (suit != "?" && (bestRank == "10" || bestRank.isNotEmpty()))
                    (if (bestRank == "10") "T" else bestRank) + suit else "",
                confidence = confidence,
                position = -1
            )
        }

        val rankDisplay = if (bestRank == "10") "T" else bestRank
        return IdentifiedCard(
            rank = rankDisplay,
            suit = suit,
            suitSymbol = suitSym,
            fullKey = if (suit != "?") rankDisplay + suit else "",
            confidence = confidence,
            position = -1
        )
    }

    // ============ V2.9.208: 花色识别 ============

    /**
     * 检测牌的花色（颜色+形状双重分析）
     * @param pixels 原始ARGB像素
     * @param w 裁剪区域宽
     * @param h 裁剪区域高
     * @param isHand 是否手牌（影响suit symbol位置）
     * @return Pair<suit, suitSymbol>，如 ("h","♥")；失败返回 ("?","?")
     */
    private fun detectSuit(pixels: IntArray, w: Int, h: Int, isHand: Boolean): Pair<String, String> {
        // suit symbol位于rank indicator下方
        val suitStartY: Int
        val suitEndY: Int
        if (isHand) {
            suitStartY = (h * 0.45).toInt()  // rank占上45%，suit在下半部
            suitEndY = minOf(h, (h * 0.75).toInt())  // y=95%会扫到玩家名字区域，改为75%截断
        } else {
            suitStartY = (h * 0.35).toInt()
            suitEndY = minOf(h, (h * 0.75).toInt())  // 公共牌suit在牌面上部，y=92%会扫到牌下方的筹码/背景
        }
        val suitW = (w * 0.65).toInt()  // suit symbol在左侧

        // Step 1: 颜色分析 — 统计红/黑色像素
        var redPixels = 0
        var blackPixels = 0
        for (y in suitStartY until suitEndY) {
            for (x in 0 until suitW) {
                val idx = y * w + x
                if (idx >= pixels.size) continue
                val p = pixels[idx]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                // 红色: R高且明显高于G和B
                if (r > 130 && r - g > 50 && r - b > 50) redPixels++
                // 黑色: 所有通道都低（但不是白色背景）
                else if (r < 90 && g < 90 && b < 90) blackPixels++
            }
        }
        val total = redPixels + blackPixels
        if (total < 15) {
            // 彩色像素太少，可能是公共牌区域或背景干扰
            return "?" to "?"
        }

        val color: String
        val symbol: String
        if (redPixels > 50 && redPixels > blackPixels) {
            color = "red"
        } else if (blackPixels > 50 && blackPixels > redPixels * 2) {
            color = "black"
        } else if (redPixels > blackPixels) {
            color = "red"
        } else if (blackPixels > redPixels) {
            color = "black"
        } else {
            color = "unknown"
        }

        // Step 2: 形状分析 — 区分同色花色
        val shapeResult = analyzeSuitShape(pixels, w, h, suitStartY, suitEndY, suitW, color)
        if (shapeResult.first != "?") {
            return shapeResult
        }

        // Step 3: 颜色兜底（形状分析不确定时）
        return when (color) {
            "red" -> "h" to "♥"
            "black" -> "s" to "♠"
            else -> "?" to "?"
        }
    }

    /**
     * 形状分析区分同色花色 — V2.9.210 自适应 rank-end 检测
     *
     * 核心改进：先定位 rank 字符结束行（rankEndRow），再对下方 suit 区域做精确分析。
     * rankEndRow 检测：从第4行起扫描，连续3行横向跨度≤25%最大行宽 → 认为 rank 已结束。
     *
     * suit 区域分析指标（归一化到 suit 区域自身高度）：
     * - comY：y 质心，越小越靠上
     * - topEdgeRatio：顶部20%最大行宽 / 区域宽
     * - botEdgeRatio：底部20%最大行宽 / 区域宽
     * - symmetry：x 方向对称度
     *
     * 判定逻辑：
     * - 红(comY<0.40): ♥；红(comY≥0.40): ♦
     * - 黑(comY<0.45): ♣；黑(comY≥0.45): ♠
     *
     * @param pixels 原始ARGB像素
     * @param w 裁剪区域宽
     * @param h 裁剪区域高
     * @param startY suit分析起始行（rank indicator下方）
     * @param endY suit分析结束行
     * @param maxX suit分析最大列宽
     * @param knownColor "red" / "black" / "unknown"
     * @return Pair<suit, suitSymbol>，如 ("h","♥")；失败返回 ("?","?")
     */
    private fun analyzeSuitShape(
        pixels: IntArray, w: Int, h: Int,
        startY: Int, endY: Int, maxX: Int, knownColor: String
    ): Pair<String, String> {
        val regW = maxX
        val regH = endY - startY
        if (regH < 4 || regW < 4) return "?" to "?"

        // --- Step 1: 建立整块区域的二值 mask ---
        val fullMask = BooleanArray(regH * regW)
        for (y in 0 until regH) {
            for (x in 0 until regW) {
                val idx = (startY + y) * w + x
                if (idx >= pixels.size) continue
                val p = pixels[idx]
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                fullMask[y * regW + x] = when (knownColor) {
                    "red" -> r > 130 && r - g > 50 && r - b > 50
                    "black" -> r < 90 && g < 90 && b < 90
                    else -> (r > 130 && r - g > 50 && r - b > 50) || (r < 90 && g < 90 && b < 90)
                }
            }
        }

        // --- Step 2: 自适应 rank-end 检测 ---
        // 逐行统计横向跨度（最左到最右彩色像素距离）
        val rowSpans = IntArray(regH)
        for (y in 0 until regH) {
            var left = -1; var right = -1
            for (x in 0 until regW) {
                if (fullMask[y * regW + x]) {
                    if (left < 0) left = x
                    right = x
                }
            }
            rowSpans[y] = if (left >= 0) right - left + 1 else 0
        }

        // 找最大行宽作为基准
        val maxSpan = rowSpans.maxOrNull() ?: 0
        val narrowThreshold = maxSpan * 0.25

        // 从第4行起，连续3行窄 → rankEndRow
        var rankEndRow = (regH * 0.40).toInt().coerceIn(3, regH - 1)  // 默认回退
        for (y in 3 until regH - 2) {
            if (rowSpans[y] > 0 && rowSpans[y] <= narrowThreshold
                && rowSpans[y + 1] > 0 && rowSpans[y + 1] <= narrowThreshold
                && rowSpans[y + 2] > 0 && rowSpans[y + 2] <= narrowThreshold
            ) {
                rankEndRow = y + 3  // 从窄行之后开始是 suit 区域
                break
            }
        }

        // --- Step 3: 在 suit 子区域做形状分析 ---
        val suitStartY = rankEndRow
        val suitH = regH - suitStartY
        if (suitH < 3) return "?" to "?"

        // 收集 suit 区域的彩色像素统计
        var sumY = 0L; var sumX = 0L; var count = 0
        var topHalf = 0; var bottomHalf = 0
        val halfH = suitH / 2

        // 边缘宽度
        var topEdgeW = 0
        val topEdgeEnd = (suitH * 0.2).toInt().coerceAtLeast(1)
        val botEdgeStart = (suitH * 0.8).toInt()
        var botEdgeW = 0

        for (y in 0 until suitH) {
            var rowCnt = 0; var rowLeft = -1; var rowRight = -1
            for (x in 0 until regW) {
                if (!fullMask[(suitStartY + y) * regW + x]) continue
                rowCnt++
                if (rowLeft < 0) rowLeft = x
                rowRight = x
                sumX += x
            }
            if (rowCnt == 0) continue
            count += rowCnt
            sumY += y.toLong() * rowCnt
            if (y < halfH) topHalf += rowCnt else bottomHalf += rowCnt

            val span = rowRight - rowLeft + 1
            if (y < topEdgeEnd && span > topEdgeW) topEdgeW = span
            if (y >= botEdgeStart && span > botEdgeW) botEdgeW = span
        }

        if (count < 8) return "?" to "?"

        val comY = sumY.toDouble() / count / suitH  // 0~1，越小越靠上
        val topEdgeRatio = topEdgeW.toDouble() / (regW * 0.5 + 1)
        val botEdgeRatio = botEdgeW.toDouble() / (regW * 0.5 + 1)
        val centerX = regW / 2.0
        val symmetry = 1.0 - Math.abs(sumX.toDouble() / count - centerX) / centerX

        // --- Step 4: 判定花色 ---
        // 红：♥ comY < 0.40；♦ comY ≥ 0.40
        // 黑：♣ comY < 0.45；♠ comY ≥ 0.45
        return when (knownColor) {
            "red" -> if (comY < 0.40) "h" to "♥" else "d" to "♦"
            "black" -> if (comY < 0.45) "c" to "♣" else "s" to "♠"
            else -> {
                // 颜色未知，用 comY + topEdgeRatio 综合判断
                when {
                    comY < 0.35 && topEdgeRatio > 0.3 -> "h" to "♥"
                    comY < 0.45 && topEdgeRatio > 0.3 -> "c" to "♣"
                    comY < 0.50 && topEdgeRatio <= 0.3 -> "d" to "♦"
                    else -> "s" to "♠"
                }
            }
        }
    }

    /**
     * 从裁剪区域提取rank indicator子区域
     * 手牌: 左上角约95×100区域（rank indicator在牌面左上）
     * 公共牌: 左上角50%宽×50%高区域
     */
    private fun extractRankIndicator(pixels: IntArray, w: Int, h: Int, isHand: Boolean): RankTemplate? {
        val rankW: Int
        val rankH: Int
        if (isHand) {
            // 手牌裁剪区域约95×160，rank indicator在左上角 ~90×100
            rankW = minOf(w, (w * 0.95).toInt())
            rankH = minOf(h, (h * 0.62).toInt())
        } else {
            // 公共牌裁剪区域约160×150，rank indicator在左上角 ~80×75 (50%×50%)
            rankW = (w * 0.50).toInt()
            rankH = (h * 0.50).toInt()
        }

        if (rankW <= 0 || rankH <= 0) return null

        val result = DoubleArray(rankW * rankH)
        for (y in 0 until rankH) {
            for (x in 0 until rankW) {
                val idx = y * w + x
                if (idx < pixels.size) {
                    val p = pixels[idx]
                    result[y * rankW + x] = 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
                }
            }
        }
        return RankTemplate(result, rankW, rankH)
    }

    /**
     * NCC匹配 - 归一化互相关，处理尺寸不一致的情况
     */
    private fun nccMatch(image: DoubleArray, template: DoubleArray): Double {
        val n = minOf(image.size, template.size)
        if (n == 0) return 0.0

        var sumA = 0.0; var sumB = 0.0
        for (i in 0 until n) { sumA += image[i]; sumB += template[i] }
        val meanA = sumA / n; val meanB = sumB / n

        var num = 0.0; var denA = 0.0; var denB = 0.0
        for (i in 0 until n) {
            val a = image[i] - meanA
            val b = template[i] - meanB
            num += a * b
            denA += a * a
            denB += b * b
        }
        val den = Math.sqrt(denA * denB)
        return if (den > 0) num / den else 0.0
    }

    /**
     * 缩放DoubleArray到目标尺寸（最近邻插值，快速）
     */
    private fun resizeDoubleArray(src: DoubleArray, targetW: Int, targetH: Int): DoubleArray {
        // 推断源尺寸（假设src是矩形的）
        val srcSize = src.size
        if (srcSize == 0) return DoubleArray(targetW * targetH)
        val srcW = Math.sqrt(srcSize.toDouble() * targetW / targetH).toInt().coerceAtLeast(1)
        val srcH = if (srcW > 0) srcSize / srcW else 1

        val result = DoubleArray(targetW * targetH)
        for (y in 0 until targetH) {
            for (x in 0 until targetW) {
                val srcX = (x * srcW / targetW).coerceIn(0, srcW - 1)
                val srcY = (y * srcH / targetH).coerceIn(0, srcH - 1)
                val srcIdx = srcY * srcW + srcX
                result[y * targetW + x] = if (srcIdx < src.size) src[srcIdx] else 0.0
            }
        }
        return result
    }

    private fun bitmapToGrayDouble(bmp: Bitmap): DoubleArray {
        val w = bmp.width; val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        return pixelsToGrayDouble(pixels)
    }

    private fun pixelsToGrayDouble(pixels: IntArray): DoubleArray {
        val result = DoubleArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            result[i] = 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
        }
        return result
    }

    // ============ V2.9.208: 底池OCR（ML Kit） ============

    /**
     * 从截图指定区域读取底池大小
     * @param screenshot 全屏截图
     * @param x1, y1, x2, y2 底池区域像素坐标
     * @return 底池数值（整数），失败返回 -1
     */
    fun readPotSize(screenshot: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int): Int {
        val safeX1 = x1.coerceIn(0, screenshot.width - 1)
        val safeY1 = y1.coerceIn(0, screenshot.height - 1)
        val safeX2 = x2.coerceIn(safeX1 + 1, screenshot.width)
        val safeY2 = y2.coerceIn(safeY1 + 1, screenshot.height)

        val regionBmp = try {
            Bitmap.createBitmap(screenshot, safeX1, safeY1, safeX2 - safeX1, safeY2 - safeY1)
        } catch (e: Exception) {
            Log.e(TAG, "readPotSize: 裁剪失败", e)
            return -1
        }

        val latch = CountDownLatch(1)
        var potSize = -1

        try {
            val image = InputImage.fromBitmap(regionBmp, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    potSize = parsePotFromText(visionText.text)
                    latch.countDown()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "readPotSize OCR失败: ${e.message}")
                    latch.countDown()
                }
            latch.await(2, TimeUnit.SECONDS)
            recognizer.close()
        } catch (e: Exception) {
            Log.e(TAG, "readPotSize异常", e)
        } finally {
            regionBmp.recycle()
        }

        if (potSize > 0) {
            Log.d(TAG, "★ 底池OCR: $potSize")
        }
        return potSize
    }

    /**
     * 从OCR文本中解析底池数值
     * 支持格式: "Pot: 1,234" / "底池: 1234" / "$1,234" / "1234"
     */
    private fun parsePotFromText(text: String): Int {
        // 去除常见前缀
        val cleaned = text
            .replace(Regex("(?i)(pot|底池|prize|pool)\\s*[:：]?\\s*"), "")
            .replace(Regex("[\\$€£]"), "")
            .replace(",", "")
            .replace(" ", "")
            .trim()

        // 提取所有数字序列，取最大的作为底池
        val numbers = Regex("\\d+").findAll(cleaned).map { it.value.toIntOrNull() ?: 0 }.filter { it > 0 }.toList()
        return numbers.maxOrNull() ?: -1
    }

    // ============ V2.9.208: 按钮状态推断 ============

    /**
     * 根据toCall推断当前可用按钮文本
     * GG扑克：toCall>0 → Fold/Call/Raise；toCall=0 → Check/Bet
     * @param toCall 当前需要跟注的金额
     * @param isGG 是否GG平台
     * @return 按钮文本列表
     */
    fun inferButtons(toCall: Int, isGG: Boolean = true): List<String> {
        return if (isGG) {
            if (toCall > 0) listOf("Fold", "Call", "Raise")
            else listOf("Check", "Bet")
        } else {
            if (toCall > 0) listOf("弃牌", "跟注", "加注")
            else listOf("过牌", "下注")
        }
    }

    fun release() {
        handRankTemplates.clear()
        commRankTemplates.clear()
        isInitialized = false
    }
}

// === 数据类 ===

data class IdentifiedCard(
    val rank: String,
    val suit: String,
    val suitSymbol: String,
    val fullKey: String,
    val confidence: Float,
    val position: Int
) {
    fun toEngineFormat(): String = "$rank$suit"
}

// V2.9.197: 混合方案识别结果 — 包含置信度
data class HybridRecognitionResult(
    val communityCards: List<IdentifiedCard>,
    val handCards: List<IdentifiedCard>,
    val minConfidence: Float,    // 所有识别到的牌的最低置信度
    val elapsedMs: Long         // 本地CV耗时
) {
    fun isValid(): Boolean = handCards.size == 2 && communityCards.size in 0..5

    /** 是否所有牌都是高置信度 */
    fun isAllHighConfidence(threshold: Float = 0.85f): Boolean =
        handCards.size == 2 && minConfidence >= threshold

    /** 获取手牌rank列表（用于传递给API做rank锁定） */
    fun getHandRanks(): List<String> =
        handCards.sortedBy { it.position }.map { it.rank }

    /** 根据公共牌数量推断street */
    fun inferStreet(): String? = when (communityCards.size) {
        0 -> "preflop"
        3 -> "flop"
        4 -> "turn"
        5 -> "river"
        else -> null
    }
}

data class RecognitionResult(
    val communityCards: List<IdentifiedCard>,
    val handCards: List<IdentifiedCard>,
    val timestamp: Long
) {
    fun toEngineInput(): Map<String, List<String>> = mapOf(
        "hand" to handCards.sortedBy { it.position }.map { it.toEngineFormat() },
        "board" to communityCards.sortedBy { it.position }.map { it.toEngineFormat() }
    )

    fun isValid(): Boolean = handCards.size == 2 && communityCards.size in 0..5
}
