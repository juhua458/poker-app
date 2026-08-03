package com.pokerhelper.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import java.io.InputStream

/**
 * 本地牌面识别器 - 纯Android Bitmap实现，零外部依赖
 * 52张模板库 + NCC匹配 + 颜色分割花色识别
 *
 * 坐标基于 GG Poker 竖屏 1080×2344 分辨率
 */
class CardRecognizer(private val context: Context) {

    companion object {
        private const val TAG = "CardRecognizer"

        // === GG Poker 竖屏坐标 (1080×2344) ===
        private val COMMUNITY_Y = 1060 to 1210
        private val COMMUNITY_CARDS = listOf(
            155 to 315,
            305 to 465,
            455 to 615,
            605 to 765,
            755 to 915
        )

        private val HAND_Y = 1780 to 1940
        private val HAND_CARDS = listOf(
            85 to 180,
            180 to 295
        )

        private const val RANK_MATCH_THRESHOLD = 0.70
        private const val SUIT_COLOR_THRESHOLD = 50
    }

    // 模板数据: key -> (灰度像素数组, 宽, 高)
    private data class Template(val grayPixels: DoubleArray, val width: Int, val height: Int, val rank: String, val suit: String)
    
    private val templates = mutableMapOf<String, Template>()
    private val rankTemplates = mutableMapOf<String, MutableList<Template>>()
    private var isInitialized = false

    fun init() {
        if (isInitialized) return

        val suits = listOf("c", "d", "h", "s")
        val ranks = listOf("A", "K", "Q", "J", "10", "9", "8", "7", "6", "5", "4", "3", "2")
        var loaded = 0

        for (rank in ranks) {
            for (suit in suits) {
                val filename = "${rank}_${suit}.jpg"
                val key = "${rank}${suit}"
                try {
                    val inputStream: InputStream = context.assets.open("card_templates/raw/$filename")
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    inputStream.close()

                    if (bitmap != null) {
                        val grayPixels = bitmapToGrayDouble(bitmap)
                        val tpl = Template(grayPixels, bitmap.width, bitmap.height, rank, suit)
                        templates[key] = tpl
                        rankTemplates.getOrPut(rank) { mutableListOf() }.add(tpl)
                        bitmap.recycle()
                        loaded++
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load: $filename", e)
                }
            }
        }

        isInitialized = true
        Log.i(TAG, "Loaded $loaded templates, ${rankTemplates.size} ranks")
    }

    /**
     * 识别整屏截图中的所有牌
     */
    fun recognizeAll(screenshot: Bitmap): RecognitionResult {
        if (!isInitialized) init()

        // 识别公共牌
        val communityCards = mutableListOf<IdentifiedCard>()
        for ((index, xRange) in COMMUNITY_CARDS.withIndex()) {
            val (x1, x2) = xRange
            val (y1, y2) = COMMUNITY_Y
            if (hasCardAt(screenshot, x1, y1, x2, y2)) {
                val card = recognizeCard(screenshot, x1, y1, x2, y2)
                if (card != null) {
                    communityCards.add(card.copy(position = index))
                }
            }
        }

        // 识别手牌
        val handCards = mutableListOf<IdentifiedCard>()
        for ((index, xRange) in HAND_CARDS.withIndex()) {
            val (x1, x2) = xRange
            val (y1, y2) = HAND_Y
            if (hasCardAt(screenshot, x1, y1, x2, y2)) {
                val card = recognizeCard(screenshot, x1, y1, x2, y2)
                if (card != null) {
                    handCards.add(card.copy(position = index))
                }
            }
        }

        return RecognitionResult(communityCards, handCards, System.currentTimeMillis())
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
     * 识别单张牌
     */
    private fun recognizeCard(bmp: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int): IdentifiedCard? {
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

        // === Step 1: Rank匹配 (NCC) ===
        val roiGray = pixelsToGrayDouble(pixels)
        var bestRank = ""
        var bestScore = 0.0

        for ((rank, tplList) in rankTemplates) {
            var rankBest = 0.0
            for (tpl in tplList) {
                val score = nccMatch(roiGray, tpl.grayPixels)
                if (score > rankBest) rankBest = score
            }
            if (rankBest > bestScore) {
                bestScore = rankBest
                bestRank = rank
            }
        }

        if (bestScore < RANK_MATCH_THRESHOLD) {
            Log.w(TAG, "Rank match too low: $bestScore for $bestRank")
            return null
        }

        // === Step 2: Suit识别 (颜色分割+形态分析) ===
        val suit = identifySuit(pixels, w)
        if (suit == null) {
            Log.w(TAG, "Suit identification failed for $bestRank")
            return null
        }

        val rankDisplay = if (bestRank == "10") "T" else bestRank
        val suitSymbol = when (suit) {
            "h" -> "♥"; "d" -> "♦"; "c" -> "♣"; "s" -> "♠"; else -> "?"
        }

        return IdentifiedCard(
            rank = rankDisplay,
            suit = suit,
            suitSymbol = suitSymbol,
            fullKey = "$bestRank$suit",
            confidence = bestScore.toFloat(),
            position = -1
        )
    }

    /**
     * NCC匹配 - 处理尺寸不一致的情况
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
     * 花色识别：颜色分割 + 形态分析
     */
    private fun identifySuit(pixels: IntArray, width: Int): String? {
        var redCount = 0
        var blackCount = 0

        for (p in pixels) {
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)

            // 红色像素
            if (r > 150 && g < 100 && b < 100) redCount++
            // 黑色像素
            if (r < 80 && g < 80 && b < 80) blackCount++
        }

        val isRed = redCount > SUIT_COLOR_THRESHOLD && redCount > blackCount

        if (isRed) {
            // ♥心形 vs ♦菱形：通过红色像素的宽高比区分
            val ratio = analyzeRedRegionRatio(pixels, width)
            return if (ratio > 0.85) "h" else "d"
        } else {
            // ♣梅花 vs ♠黑桃：通过黑色像素分布区分
            val ratio = analyzeBlackRegionRatio(pixels, width)
            return if (ratio < 0.65) "c" else "s"
        }
    }

    /**
     * 分析红色区域宽高比（区分♥和♦）
     * ♥心形：较宽，宽高比一般 > 0.85
     * ♦菱形：较窄，宽高比一般 < 0.85
     */
    private fun analyzeRedRegionRatio(pixels: IntArray, width: Int): Double {
        val height = pixels.size / width
        if (height <= 0) return 0.9

        var minX = width; var maxX = 0; var minY = height; var maxY = 0
        var redCount = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                if (r > 150 && g < 100 && b < 100) {
                    redCount++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (redCount < SUIT_COLOR_THRESHOLD) return 0.9

        val regionW = (maxX - minX + 1).toDouble()
        val regionH = (maxY - minY + 1).toDouble()
        if (regionH <= 0.0) return 0.9

        return regionW / regionH
    }

    /**
     * 分析黑色区域分布（区分♣和♠）
     * ♣梅花：三叶圆瓣，分布更分散，宽高比偏高
     * ♠黑桃：单尖头朝上，较集中，宽高比偏低
     */
    private fun analyzeBlackRegionRatio(pixels: IntArray, width: Int): Double {
        val height = pixels.size / width
        if (height <= 0) return 0.7

        var minX = width; var maxX = 0; var minY = height; var maxY = 0
        var blackCount = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                if (r < 80 && g < 80 && b < 80) {
                    blackCount++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (blackCount < SUIT_COLOR_THRESHOLD) return 0.7

        val regionW = (maxX - minX + 1).toDouble()
        val regionH = (maxY - minY + 1).toDouble()
        if (regionH <= 0.0) return 0.7

        return regionW / regionH
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
        templates.clear()
        rankTemplates.clear()
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
