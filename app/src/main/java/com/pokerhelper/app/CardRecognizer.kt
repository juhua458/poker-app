package com.pokerhelper.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import java.io.InputStream

/**
 * 本地牌面识别器 V2 - Rank-only NCC匹配
 * V2.9.197: 混合方案核心
 * - 分离手牌/公共牌rank indicator模板池（解决尺寸差异导致的识别失败）
 * - 只识别rank，suit由云端API补充（混合方案）
 * - 返回置信度分数，用于自适应API调用策略
 * - <300ms完成，用于快速锁定+减少API依赖
 *
 * 坐标基于 GG Poker 竖屏 1080×2344 分辨率
 */
class CardRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "CardRecognizer"

        // === GG Poker 竖屏坐标 (基于1080×2344，运行时按屏幕比例缩放) ===
        private const val BASE_WIDTH = 1080
        private const val BASE_HEIGHT = 2344

        private val COMMUNITY_Y_BASE = 1060 to 1210
        private val COMMUNITY_CARDS_BASE = listOf(
            155 to 315, 305 to 465, 455 to 615, 605 to 765, 755 to 915
        )
        private val HAND_Y_BASE = 1780 to 1940
        private val HAND_CARDS_BASE = listOf(85 to 180, 180 to 295)

        private const val RANK_MATCH_THRESHOLD = 0.65  // V2.9.197: 降低阈值让低置信度走API兜底

        // V2.9.184: 运行时缩放因子
        private var scaleX = 1.0f
        private var scaleY = 1.0f
        private var COMMUNITY_Y = 1060 to 1210
        private var COMMUNITY_CARDS = COMMUNITY_CARDS_BASE
        private var HAND_Y = 1780 to 1940
        private var HAND_CARDS = HAND_CARDS_BASE

        fun updateScreenSize(width: Int, height: Int) {
            scaleX = width.toFloat() / BASE_WIDTH
            scaleY = height.toFloat() / BASE_HEIGHT
            COMMUNITY_Y = (COMMUNITY_Y_BASE.first * scaleY).toInt() to (COMMUNITY_Y_BASE.second * scaleY).toInt()
            COMMUNITY_CARDS = COMMUNITY_CARDS_BASE.map { (x1, x2) -> (x1 * scaleX).toInt() to (x2 * scaleX).toInt() }
            HAND_Y = (HAND_Y_BASE.first * scaleY).toInt() to (HAND_Y_BASE.second * scaleY).toInt()
            HAND_CARDS = HAND_CARDS_BASE.map { (x1, x2) -> (x1 * scaleX).toInt() to (x2 * scaleX).toInt() }
            Log.i(TAG, "CardRecognizer坐标缩放: ${width}x${height} scaleX=$scaleX scaleY=$scaleY")
        }
    }

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
        // ground truth映射 — 24个截图(idx 0-23)
        // idx 0-8: v2.9.197原有9张截图
        // idx 9-23: 新增15张截图（2026-06-12~13）
        val groundTruthHands = arrayOf(
            // idx 0-8 (v2.9.197原有)
            arrayOf("3","2"),   // 00: 06-03-02-56-27
            arrayOf("A","4"),   // 01: 06-03-02-56-39
            arrayOf("A","2"),   // 02
            arrayOf("K","Q"),   // 03
            arrayOf("7","4"),   // 04
            arrayOf("K","9"),   // 05
            arrayOf("Q","5"),   // 06
            arrayOf("7","7"),   // 07
            arrayOf("A","8"),   // 08
            // idx 9-23 (新增15张)
            arrayOf("J","5"),   // 09: 06-13-16-12-04 J♠ 5♠
            arrayOf("3","2"),   // 10: 06-13-12-08-56 3♠ 2♣
            arrayOf("A","4"),   // 11: 06-13-12-05-28 A♠ 4♦
            arrayOf("A","2"),   // 12: 06-13-03-07-43 A♥ 2♦
            arrayOf("K","Q"),   // 13: 06-13-02-33-09 K♣ Q♦
            arrayOf("7","4"),   // 14: 06-13-02-28-21 7♥ 4♥
            arrayOf("K","9"),   // 15: 06-13-02-26-11 K♦ 9♦
            arrayOf("Q","5"),   // 16: 06-13-02-24-21 Q♣ 5♦
            arrayOf("7","7"),   // 17: 06-13-02-20-54 7♠ 7♦
            arrayOf("A","8"),   // 18: 06-12-22-45-58 A♠ 8♠
            arrayOf("A","7"),   // 19: 06-12-02-29-08 A♦ 7♥
            arrayOf("K","K"),   // 20: 06-12-03-04-45 K♠ K♥
            arrayOf("K","J"),   // 21: 06-12-03-49-30 K♥ J♠
            arrayOf("K","J"),   // 22: 06-12-03-49-50 K♥ J♠
            arrayOf("A","6")    // 23: 06-12-22-42-10 A♦ 6♠
        )
        val groundTruthBoards = arrayOf(
            // idx 0-8 (v2.9.197原有)
            arrayOf("8","10","6"),              // 00
            arrayOf("2","J","2","10"),          // 01
            arrayOf("5","K","J"),               // 02
            arrayOf("8","5","7","5","7"),       // 03
            arrayOf("9","4","10"),              // 04
            arrayOf("10","A","9","8","7"),      // 05
            arrayOf("9","6","9"),               // 06
            arrayOf("8","8","A","3","9"),       // 07
            arrayOf("Q","A","Q","K"),           // 08
            // idx 9-23 (新增15张)
            arrayOf("3","8","K"),               // 09: 06-13-16-12-04 3♥ 8♦ K♣
            arrayOf("8","10","6"),              // 10: 06-13-12-08-56 8♦ 10♣ 6♣
            arrayOf("2","J","2","10"),          // 11: 06-13-12-05-28 2♥ J♣ 2♠ 10♥
            arrayOf("5","K","J"),               // 12: 06-13-03-07-43 5♠ K♦ J♦
            arrayOf("8","5","7","5","7"),       // 13: 06-13-02-33-09 8♣ 5♠ 7♦ 5♣ 7♥
            arrayOf("9","4","10"),              // 14: 06-13-02-28-21 9♠ 4♠ 10♦
            arrayOf("10","A","9","8","7"),      // 15: 06-13-02-26-11 10♥ A♥ 9♥ 8♦ 7♣
            arrayOf("9","6","9"),               // 16: 06-13-02-24-21 9♦ 6♦ 9♣
            arrayOf("8","8","A","3","9"),       // 17: 06-13-02-20-54 8♣ 8♥ A♠ 3♦ 9♠
            arrayOf("Q","A","Q","K"),           // 18: 06-12-22-45-58 Q♥ A♥ Q♠ K
            arrayOf("9","6","K","Q","4"),       // 19: 06-12-02-29-08 9♥ 6♦ K♠ Q♠ 4♥
            arrayOf("J","Q","7","9"),           // 20: 06-12-03-04-45 J♣ Q♥ 7♠ 9♦
            arrayOf("5","8","A","6"),           // 21: 06-12-03-49-30 5♥ 8♥ A♣ 6♣
            arrayOf("5","8","A","6","7"),       // 22: 06-12-03-49-50 5♥ 8♥ A♣ 6♣ 7♠
            arrayOf("7","A","10")               // 23: 06-12-22-42-10 7♠ A♠ 10♦
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

        Log.d(TAG, "本地CV: ${elapsed}ms hand=${handCards.map{"${it.rank}(${String.format("%.2f",it.confidence)})"}} board=${communityCards.map{"${it.rank}(${String.format("%.2f",it.confidence)})"}} minConf=$minConfidence")

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
            Log.w(TAG, "${if(isHand)"手牌":"公共牌"}模板池为空")
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
                    resizeDoubleArray(rankGray, tpl.width, tpl.height)
                } else {
                    rankGray
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

        if (bestScore < RANK_MATCH_THRESHOLD) {
            Log.w(TAG, "Rank匹配分数过低: ${String.format("%.3f", bestScore)} ($bestRank)")
            // 仍然返回结果，但置信度低 → 混合方案会用API兜底
            return IdentifiedCard(
                rank = if (bestRank == "10") "T" else if (bestRank.isNotEmpty()) bestRank else "?",
                suit = "?",
                suitSymbol = "?",
                fullKey = "",
                confidence = confidence,
                position = -1
            )
        }

        val rankDisplay = if (bestRank == "10") "T" else bestRank
        return IdentifiedCard(
            rank = rankDisplay,
            suit = "?",  // suit由API补充
            suitSymbol = "?",
            fullKey = "",
            confidence = confidence,
            position = -1
        )
    }

    /**
     * 从裁剪区域提取rank indicator子区域
     * 手牌: 左上角约95×100区域（rank indicator在牌面左上）
     * 公共牌: 左上角50%宽×50%高区域
     */
    private fun extractRankIndicator(pixels: IntArray, w: Int, h: Int, isHand: Boolean): DoubleArray? {
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
        return result
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
