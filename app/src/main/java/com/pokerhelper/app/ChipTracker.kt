package com.pokerhelper.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 筹码追踪器 - V2.9.210 固定坐标版
 * 
 * 核心逻辑（基于GG固定坐标）：
 * 1. 从GameModeConfig获取6个座位的筹码区域坐标
 * 2. 对每个区域裁剪→ML Kit OCR→直接映射到座位号
 * 3. 同时OCR底池金额
 * 4. 调用detectDealerButton定位D按钮→推算SB/BB
 * 5. 调用detectActivePlayer检测白色光圈→确定当前行动者
 * 6. 前后帧对比→筹码变化=下注额
 * 
 * 旧版全屏OCR+聚类方式保留为fallback
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
        val id: Int,          // 座位号 1-6
        val x: Int,
        val y: Int,
        val currentChips: Long,
        val previousChips: Long?,
        val delta: Long?,
        val status: String,   // "active" / "betting" / "won" / "folded" / "allin" / "empty"
        val rawText: String,
        val seatLabel: String = ""  // 玩家名或座位标识
    )

    data class FrameData(
        val timestamp: Long,
        val players: List<PlayerState>,
        val tablePlayerCount: Int,
        val activePlayerCount: Int,
        val totalBetAmount: Long,
        val potAmount: Long = 0L,
        val dealerSeatIndex: Int = -1,     // D按钮所在座位号(1-6)，-1=未检测到
        val activeSeatIndex: Int = -1,     // 当前行动者座位号(1-6)，-1=未检测到
        val sbSeatIndex: Int = -1,         // 推算小盲座位号
        val bbSeatIndex: Int = -1          // 推算大盲座位号
    )

    /**
     * ★ V2.9.210 核心方法：基于固定坐标的定点OCR分析 ★
     * 
     * 直接按GameModeConfig坐标裁剪各座位筹码区域，逐一OCR
     * 比全屏OCR+聚类更精准、更快、座位映射明确
     */
    fun analyzeWithFixedCoords(bitmap: Bitmap): FrameData? {
        try {
            val config = GameModeConfig.getCoordinateConfig()
            val chipRegions = config.playerChips
            val nameRegions = config.playerNames
            
            if (chipRegions.isEmpty()) {
                Log.w(TAG, "固定坐标未配置，fallback到全屏OCR")
                val jpegData = ByteArrayOutputStream().also { 
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) 
                }.toByteArray()
                return analyzeScreenshot(jpegData)
            }
            
            val prevFrame = if (playerHistory.isNotEmpty()) playerHistory.last() else null
            val players = mutableListOf<PlayerState>()
            var totalBet = 0L
            var occupiedCount = 0
            
            // 逐座位定点OCR
            for (i in chipRegions.indices) {
                val seatIndex = i + 1  // 座位号1-6
                val region = chipRegions[i]  // [x1, y1, x2, y2]
                
                // 裁剪筹码区域
                val cropX = region[0].coerceIn(0, bitmap.width - 1)
                val cropY = region[1].coerceIn(0, bitmap.height - 1)
                val cropW = (region[2] - region[0]).coerceIn(1, bitmap.width - cropX)
                val cropH = (region[3] - region[1]).coerceIn(1, bitmap.height - cropY)
                
                val chipBitmap = try {
                    Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
                } catch (e: Exception) {
                    Log.w(TAG, "座位$seatIndex 裁剪失败: ${e.message}")
                    null
                }
                
                val chipAmount = if (chipBitmap != null) ocrRegion(chipBitmap) else null
                val playerName = if (i < nameRegions.size) {
                    val nr = nameRegions[i]
                    val nameBitmap = try {
                        Bitmap.createBitmap(bitmap, 
                            nr[0].coerceIn(0, bitmap.width - 1),
                            nr[1].coerceIn(0, bitmap.height - 1),
                            (nr[2] - nr[0]).coerceIn(1, bitmap.width - nr[0]),
                            (nr[3] - nr[1]).coerceIn(1, bitmap.height - nr[1]))
                    } catch (e: Exception) { null }
                    if (nameBitmap != null) ocrRegionText(nameBitmap) else ""
                } else ""
                
                // 判断座位状态
                val isEmpty = chipAmount == null || chipAmount == 0L
                val isAllIn = playerName.contains("全押", ignoreCase = true) ||
                              (chipAmount != null && chipAmount <= 500L && chipAmount > 0L)
                
                val status = when {
                    isEmpty -> "empty"
                    isAllIn -> "allin"
                    else -> "active"
                }
                
                if (!isEmpty) occupiedCount++
                
                // 计算筹码变化
                val prevPlayer = prevFrame?.players?.find { it.id == seatIndex }
                val delta = if (prevPlayer != null && !isEmpty && prevPlayer.status != "empty") {
                    chipAmount!! - prevPlayer.currentChips
                } else null
                
                if (delta != null && delta < 0) {
                    totalBet += Math.abs(delta)
                }
                
                val centerX = cropX + cropW / 2
                val centerY = cropY + cropH / 2
                
                players.add(PlayerState(
                    id = seatIndex,
                    x = centerX,
                    y = centerY,
                    currentChips = chipAmount ?: 0L,
                    previousChips = prevPlayer?.currentChips,
                    delta = delta,
                    status = status,
                    rawText = chipAmount?.toString() ?: "",
                    seatLabel = playerName
                ))
                
                chipBitmap?.recycle()
            }
            
            // OCR底池金额
            val potAmount = try {
                val potRegion = config.potAmount
                val potBitmap = Bitmap.createBitmap(bitmap,
                    potRegion[0].coerceIn(0, bitmap.width - 1),
                    potRegion[1].coerceIn(0, bitmap.height - 1),
                    (potRegion[2] - potRegion[0]).coerceIn(1, bitmap.width - potRegion[0]),
                    (potRegion[3] - potRegion[1]).coerceIn(1, bitmap.height - potRegion[1]))
                val potText = ocrRegionText(potBitmap)
                potBitmap.recycle()
                parseChipText(potText) ?: 0L
            } catch (e: Exception) {
                Log.w(TAG, "底池OCR失败: ${e.message}")
                0L
            }
            
            // D按钮检测（返回0-based索引，转为1-6座位号）
            val dealerSeatIndex = try {
                val searchAreas = config.dealerSearchAreas
                if (searchAreas.isNotEmpty()) {
                    val idx0 = CardRecognizer.detectDealerButton(bitmap, searchAreas)
                    if (idx0 >= 0) idx0 + 1 else -1
                } else -1
            } catch (e: Exception) {
                Log.w(TAG, "D按钮检测失败: ${e.message}")
                -1
            }
            
            // 行动者检测（返回0-based索引，转为1-6座位号）
            val activeSeatIndex = try {
                val nameRegionsForActive = config.playerNames
                val chipRegionsForActive = config.playerChips
                val idx0 = CardRecognizer.detectActivePlayer(bitmap, nameRegionsForActive, chipRegionsForActive)
                if (idx0 >= 0) idx0 + 1 else -1
            } catch (e: Exception) {
                Log.w(TAG, "行动者检测失败: ${e.message}")
                -1
            }
            
            // 推算SB/BB位置
            val sbSeat = if (dealerSeatIndex > 0) {
                nextSeat(dealerSeatIndex, players.size, players)
            } else -1
            val bbSeat = if (sbSeat > 0) {
                nextSeat(sbSeat, players.size, players)
            } else -1
            
            val activeCount = players.count { it.status != "empty" && it.status != "folded" }
            
            val frameData = FrameData(
                timestamp = System.currentTimeMillis(),
                players = players,
                tablePlayerCount = occupiedCount,
                activePlayerCount = activeCount,
                totalBetAmount = totalBet,
                potAmount = potAmount,
                dealerSeatIndex = dealerSeatIndex,
                activeSeatIndex = activeSeatIndex,
                sbSeatIndex = sbSeat,
                bbSeatIndex = bbSeat
            )
            
            synchronized(playerHistory) {
                playerHistory.add(frameData)
                if (playerHistory.size > MAX_HISTORY) {
                    playerHistory.removeAt(0)
                }
            }
            currentFrame = frameData
            
            return frameData
        } catch (e: Exception) {
            Log.e(TAG, "定点OCR分析失败: ${e.message}")
            return null
        }
    }
    
    /** 顺时针找下一个有人的座位 */
    private fun nextSeat(fromSeat: Int, totalSeats: Int, players: List<PlayerState>): Int {
        for (offset in 1..totalSeats) {
            val next = ((fromSeat - 1 + offset) % totalSeats) + 1
            val p = players.find { it.id == next }
            if (p != null && p.status != "empty" && p.status != "folded") return next
        }
        return -1
    }
    
    /** 定点OCR：返回数字金额 */
    private fun ocrRegion(cropBitmap: Bitmap): Long? {
        val latch = CountDownLatch(1)
        var resultText = ""
        
        val image = InputImage.fromBitmap(cropBitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                resultText = visionText.text
                latch.countDown()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "区域OCR失败: ${e.message}")
                latch.countDown()
            }
        
        latch.await(2, TimeUnit.SECONDS)
        return parseChipText(resultText)
    }
    
    /** 定点OCR：返回原始文本 */
    private fun ocrRegionText(cropBitmap: Bitmap): String {
        val latch = CountDownLatch(1)
        var resultText = ""
        
        val image = InputImage.fromBitmap(cropBitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                resultText = visionText.text.trim()
                latch.countDown()
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "区域文本OCR失败: ${e.message}")
                latch.countDown()
            }
        
        latch.await(2, TimeUnit.SECONDS)
        return resultText
    }
    
    // ========== 旧版全屏OCR（fallback） ==========

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
            val frameData = buildFrameDataLegacy(clusteredChips, System.currentTimeMillis())
            
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

    private fun buildFrameDataLegacy(chips: List<PlayerChip>, timestamp: Long): FrameData {
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
                id = index + 1,
                x = chip.x,
                y = chip.y,
                currentChips = chip.amount,
                previousChips = prevPlayer?.currentChips,
                delta = delta,
                status = status,
                rawText = chip.rawText
            ))
        }
        
        val activeCount = players.count { it.status != "folded" && it.status != "empty" }
        
        return FrameData(
            timestamp = timestamp,
            players = players.sortedBy { it.y },
            tablePlayerCount = chips.size,
            activePlayerCount = activeCount,
            totalBetAmount = totalBet
        )
    }

    // ========== JSON输出 ==========

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
            put("potAmount", frame.potAmount)
            put("dealerSeat", frame.dealerSeatIndex)
            put("activeSeat", frame.activeSeatIndex)
            put("sbSeat", frame.sbSeatIndex)
            put("bbSeat", frame.bbSeatIndex)
            
            val playersArr = JSONArray()
            for (p in frame.players) {
                playersArr.put(JSONObject().apply {
                    put("id", p.id)
                    put("seat", p.id)
                    put("x", p.x)
                    put("y", p.y)
                    put("chips", p.currentChips)
                    put("prevChips", p.previousChips ?: JSONObject.NULL)
                    put("delta", p.delta ?: JSONObject.NULL)
                    put("status", p.status)
                    put("raw", p.rawText)
                    put("name", p.seatLabel)
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
