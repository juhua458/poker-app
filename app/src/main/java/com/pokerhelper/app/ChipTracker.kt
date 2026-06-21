package com.pokerhelper.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 筹码追踪器 - ML Kit OCR + 筹码变化检测
 * 
 * 核心逻辑：
 * 1. ML Kit识别截图中所有文字
 * 2. 过滤出筹码数字（>1000的纯数字）
 * 3. 按位置聚类→每个位置=一个玩家
 * 4. 前后帧对比→筹码变化=下注额
 * 5. 筹码不变=弃牌/过牌，减少=下注了，增加=赢了
 */
object ChipTracker {

    private const val TAG = "ChipTracker"
    private const val MIN_CHIP_AMOUNT = 1000L
    private const val CLUSTER_Y_THRESHOLD = 80
    private const val CLUSTER_X_THRESHOLD = 200
    private const val MAX_HISTORY = 30

    private val playerHistory = mutableListOf<FrameData>()
    
    @Volatile
    var currentFrame: FrameData? = null
        private set

    data class PlayerChip(
        val x: Int,
        val y: Int,
        val amount: Long,
        val rawText: String,
        val width: Int,
        val height: Int
    )

    data class PlayerState(
        val id: Int,
        val x: Int,
        val y: Int,
        val currentChips: Long,
        val previousChips: Long?,
        val delta: Long?,
        val status: String,
        val rawText: String
    )

    data class FrameData(
        val timestamp: Long,
        val players: List<PlayerState>,
        val tablePlayerCount: Int,
        val activePlayerCount: Int,
        val totalBetAmount: Long
    )

    fun analyzeScreenshot(jpegData: ByteArray): FrameData? {
        try {
            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size) ?: return null
            
            val latch = CountDownLatch(1)
            var result: List<PlayerChip>? = null
            
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    result = extractChipsFromText(visionText.textBlocks)
                    latch.countDown()
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "ML Kit OCR失败: ${e.message}")
                    latch.countDown()
                }
            
            latch.await(3, TimeUnit.SECONDS)
            val chips = result ?: return null
            
            val clusteredChips = clusterChips(chips)
            val frameData = buildFrameData(clusteredChips, System.currentTimeMillis())
            
            synchronized(playerHistory) {
                playerHistory.add(frameData)
                if (playerHistory.size > MAX_HISTORY) {
                    playerHistory.removeAt(0)
                }
            }
            currentFrame = frameData
            
            return frameData
        } catch (e: Exception) {
            Log.e(TAG, "分析截图失败: ${e.message}")
            return null
        }
    }

    private fun extractChipsFromText(
        textBlocks: List<com.google.mlkit.vision.text.Text.TextBlock>
    ): List<PlayerChip> {
        val chips = mutableListOf<PlayerChip>()
        
        for (block in textBlocks) {
            for (line in block.lines) {
                val text = line.text.trim()
                val bounds = line.boundingBox ?: continue
                
                val amount = parseChipText(text)
                if (amount != null && amount >= MIN_CHIP_AMOUNT) {
                    chips.add(PlayerChip(
                        x = bounds.centerX(),
                        y = bounds.centerY(),
                        amount = amount,
                        rawText = text,
                        width = bounds.width(),
                        height = bounds.height()
                    ))
                }
                
                for (element in line.elements) {
                    val elemText = element.text.trim()
                    val elemBounds = element.boundingBox ?: continue
                    val elemAmount = parseChipText(elemText)
                    if (elemAmount != null && elemAmount >= MIN_CHIP_AMOUNT) {
                        val isDup = chips.any { 
                            Math.abs(it.x - elemBounds.centerX()) < 50 && 
                            Math.abs(it.y - elemBounds.centerY()) < 30 
                        }
                        if (!isDup) {
                            chips.add(PlayerChip(
                                x = elemBounds.centerX(),
                                y = elemBounds.centerY(),
                                amount = elemAmount,
                                rawText = elemText,
                                width = elemBounds.width(),
                                height = elemBounds.height()
                            ))
                        }
                    }
                }
            }
        }
        return chips
    }

    private fun parseChipText(text: String): Long? {
        var clean = text.replace(Regex("[^0-9,.]"), "")
        if (clean.isEmpty()) return null
        clean = clean.replace(",", "")
        if (clean.contains(".")) {
            val parts = clean.split(".")
            if (parts.size == 2 && parts[1].length <= 1) {
                clean = parts[0]
            } else {
                return null
            }
        }
        return try {
            val num = clean.toLong()
            if (num in 1000..100_000_000_000L) num else null
        } catch (e: Exception) {
            null
        }
    }

    private fun clusterChips(chips: List<PlayerChip>): List<PlayerChip> {
        if (chips.isEmpty()) return emptyList()
        val sorted = chips.sortedBy { it.y }
        val clusters = mutableListOf<MutableList<PlayerChip>>()
        
        for (chip in sorted) {
            var merged = false
            for (cluster in clusters) {
                val rep = cluster.first()
                if (Math.abs(chip.y - rep.y) < CLUSTER_Y_THRESHOLD && 
                    Math.abs(chip.x - rep.x) < CLUSTER_X_THRESHOLD) {
                    cluster.add(chip)
                    merged = true
                    break
                }
            }
            if (!merged) {
                clusters.add(mutableListOf(chip))
            }
        }
        
        return clusters.map { cluster ->
            val reasonable = cluster.filter { it.amount in 10_000..100_000_000L }
            if (reasonable.isNotEmpty()) {
                reasonable.maxByOrNull { it.amount } ?: cluster.first()
            } else {
                cluster.maxByOrNull { it.amount } ?: cluster.first()
            }
        }
    }

    private fun buildFrameData(chips: List<PlayerChip>, timestamp: Long): FrameData {
        val prevFrame = if (playerHistory.isNotEmpty()) playerHistory.last() else null
        val players = mutableListOf<PlayerState>()
        var totalBet = 0L
        
        for ((index, chip) in chips.sortedBy { it.y }.withIndex()) {
            val prevPlayer = prevFrame?.players?.find {
                Math.abs(it.x - chip.x) < CLUSTER_X_THRESHOLD &&
                Math.abs(it.y - chip.y) < CLUSTER_Y_THRESHOLD
            }
            
            val delta = if (prevPlayer != null) chip.amount - prevPlayer.currentChips else null
            val status = when {
                prevPlayer == null -> "new"
                delta == null -> "active"
                delta == 0L -> "active"
                delta < 0 -> "betting"
                delta > 0 -> "won"
                else -> "active"
            }
            
            if (delta != null && delta < 0) {
                totalBet += Math.abs(delta)
            }
            
            players.add(PlayerState(
                id = index,
                x = chip.x,
                y = chip.y,
                currentChips = chip.amount,
                previousChips = prevPlayer?.currentChips,
                delta = delta,
                status = status,
                rawText = chip.rawText
            ))
        }
        
        if (prevFrame != null) {
            for (prev in prevFrame.players) {
                val stillHere = players.any { 
                    Math.abs(it.x - prev.x) < CLUSTER_X_THRESHOLD &&
                    Math.abs(it.y - prev.y) < CLUSTER_Y_THRESHOLD
                }
                if (!stillHere) {
                    players.add(PlayerState(
                        id = prev.id,
                        x = prev.x,
                        y = prev.y,
                        currentChips = 0,
                        previousChips = prev.currentChips,
                        delta = -prev.currentChips,
                        status = "folded",
                        rawText = "FOLDED"
                    ))
                }
            }
        }
        
        val activeCount = players.count { it.status != "folded" }
        
        return FrameData(
            timestamp = timestamp,
            players = players.sortedBy { it.y },
            tablePlayerCount = chips.size,
            activePlayerCount = activeCount,
            totalBetAmount = totalBet
        )
    }

    fun getStatusJson(): String {
        val frame = currentFrame ?: return JSONObject().apply {
            put("available", false)
            put("message", "等待识别...")
        }.toString()
        
        val json = JSONObject().apply {
            put("available", true)
            put("timestamp", frame.timestamp)
            put("tablePlayerCount", frame.tablePlayerCount)
            put("activePlayerCount", frame.activePlayerCount)
            put("totalBetAmount", frame.totalBetAmount)
            
            val playersArr = JSONArray()
            for (p in frame.players) {
                playersArr.put(JSONObject().apply {
                    put("id", p.id)
                    put("x", p.x)
                    put("y", p.y)
                    put("chips", p.currentChips)
                    put("prevChips", p.previousChips ?: JSONObject.NULL)
                    put("delta", p.delta ?: JSONObject.NULL)
                    put("status", p.status)
                    put("raw", p.rawText)
                })
            }
            put("players", playersArr)
        }
        return json.toString()
    }

    fun reset() {
        synchronized(playerHistory) {
            playerHistory.clear()
        }
        currentFrame = null
    }
}
