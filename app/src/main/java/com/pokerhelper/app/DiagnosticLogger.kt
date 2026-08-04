package com.pokerhelper.app

import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

/**
 * 诊断日志记录器 - 记录每次识别的完整信息
 * 用于分析识别准确性、筹码变化、性能指标等
 */
object DiagnosticLogger {
    
    private const val MAX_LOGS = 100  // 最多保留100次识别记录
    private val recognitionLogs = mutableListOf<RecognitionLog>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logFileLock = Any()
    
    // V2.9.182: 日志文件持久化——每次识别自动追加，滚动保留最近5MB
    private fun getLogFile(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloadsDir, "poker_log.txt")
    }
    
    private fun autoFlushToFile(log: RecognitionLog) {
        try {
            synchronized(logFileLock) {
                val file = getLogFile()
                val entry = logToJson(log).toString(2) + ",\n"
                file.appendText(entry, Charsets.UTF_8)
                
                // 滚动保留最近5MB
                if (file.length() > 5 * 1024 * 1024) {
                    val content = file.readText(Charsets.UTF_8)
                    val keepLength = content.length / 2
                    file.writeText(content.substring(content.length - keepLength), Charsets.UTF_8)
                }
            }
        } catch (_: Exception) {
            // 文件日志失败不影响主流程
        }
    }
    
    /**
     * V2.9.182: 接收JS端console.log日志
     */
    fun logJsConsole(message: String) {
        try {
            synchronized(logFileLock) {
                val file = getLogFile()
                val timeStr = timeFormat.format(Date())
                file.appendText("[$timeStr] [JS] $message\n", Charsets.UTF_8)
            }
        } catch (_: Exception) {}
    }
    
    /**
     * V2.9.182: 崩溃时紧急写入日志
     */
    fun flushCrashLog(throwable: Throwable) {
        try {
            synchronized(logFileLock) {
                val file = getLogFile()
                val timeStr = dateFormat.format(Date())
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                file.appendText("\n=== CRASH $timeStr ===\n${sw.toString()}\n", Charsets.UTF_8)
                
                // 同步写入内存中的最后5条日志
                synchronized(recognitionLogs) {
                    val recent = recognitionLogs.takeLast(5)
                    file.appendText("=== 崩溃前最后5条识别日志 ===\n", Charsets.UTF_8)
                    for (log in recent) {
                        file.appendText(logToJson(log).toString(2) + ",\n", Charsets.UTF_8)
                    }
                }
            }
        } catch (_: Exception) {}
    }
    
    /**
     * 单次识别记录的完整数据
     */
    data class RecognitionLog(
        val timestamp: Long,
        val timeStr: String,
        
        // 本地CV识别结果
        val localCVEnabled: Boolean,
        val localCVTimeMs: Long,
        val localHandCards: String,  // 如 "Ah,Kd"
        val localCommunityCards: String,  // 如 "Ts,9h,2c"
        val localStreet: String?,  // 本地CV推断的street
        
        // 本地CV锁定的信息
        val streetLocked: String?,  // 实际锁定的street
        val holeCardsLocked: Boolean,  // 是否锁定了手牌
        
        // VisionAPI识别结果
        val vlmTimeMs: Long,
        val vlmHandCards: String,
        val vlmCommunityCards: String,
        val vlmStreet: String,
        val vlmPot: Int,
        val vlmMyChips: Int,
        val vlmBetToCall: Int,
        val vlmButtons: List<String>,
        val vlmDButtonPos: String,
        val vlmTotalPlayers: Int,
        val vlmActivePlayers: Int,
        val vlmBlinds: String,
        
        // 最终结果（经过纠正后）
        val finalStreet: String,
        val finalHandCards: String,
        val finalCommunityCards: String,
        
        // 筹码追踪
        val chipDelta: Long?,  // 筹码变化量
        val chipStatus: String,  // 筹码状态：betting/won/active/folded
        val potDelta: Int,  // 底池变化量
        
        // 性能指标
        val totalTimeMs: Long,  // 总耗时
        
        // 错误信息
        val hasError: Boolean,
        val errorMessage: String?,
        
        // 是否成功发送策略引擎
        val strategySent: Boolean,
        
        // V2.9.193: API原始响应——诊断识别失败根因
        val rawResponse: String?
    )
    
    /**
     * 记录一次识别的完整信息
     */
    fun logRecognition(
        localCVEnabled: Boolean,
        localCVTimeMs: Long,
        localHandCards: List<VisionApiClient.CardInfo>,
        localCommunityCards: List<VisionApiClient.CardInfo>,
        localStreet: String?,
        streetLocked: String?,
        holeCardsLocked: Boolean,
        vlmTimeMs: Long,
        vlmResult: VisionApiClient.VisionResult?,
        totalTimeMs: Long,
        hasError: Boolean,
        errorMessage: String?,
        strategySent: Boolean,
        rawResponse: String? = null  // V2.9.193: API原始响应
    ) {
        val now = System.currentTimeMillis()
        val timeStr = timeFormat.format(Date(now))
        
        // 计算筹码变化
        val chipDelta = vlmResult?.let { calcChipDelta(it.playerChips) }
        val chipStatus = determineChipStatus(chipDelta)
        val potDelta = vlmResult?.let { calcPotDelta(it.potSize) } ?: 0
        
        val log = RecognitionLog(
            timestamp = now,
            timeStr = timeStr,
            localCVEnabled = localCVEnabled,
            localCVTimeMs = localCVTimeMs,
            localHandCards = localHandCards.joinToString(",") { "${it.rank}${it.suit}" },
            localCommunityCards = localCommunityCards.joinToString(",") { "${it.rank}${it.suit}" },
            localStreet = localStreet,
            streetLocked = streetLocked,
            holeCardsLocked = holeCardsLocked,
            vlmTimeMs = vlmTimeMs,
            vlmHandCards = vlmResult?.holeCards?.joinToString(",") { "${it.rank}${it.suit}" } ?: "",
            vlmCommunityCards = vlmResult?.communityCards?.joinToString(",") { "${it.rank}${it.suit}" } ?: "",
            vlmStreet = vlmResult?.street ?: "",
            vlmPot = vlmResult?.potSize ?: 0,
            vlmMyChips = vlmResult?.playerChips ?: 0,
            vlmBetToCall = vlmResult?.toCall ?: 0,
            vlmButtons = vlmResult?.buttons ?: emptyList(),
            vlmDButtonPos = vlmResult?.dButtonPosition ?: "",
            vlmTotalPlayers = vlmResult?.totalPlayers ?: 0,
            vlmActivePlayers = vlmResult?.activePlayers ?: 0,
            vlmBlinds = if (vlmResult != null) "${vlmResult.blindSB}/${vlmResult.blindBB}" else "",
            finalStreet = vlmResult?.street ?: "",
            finalHandCards = vlmResult?.holeCards?.joinToString(",") { "${it.rank}${it.suit}" } ?: "",
            finalCommunityCards = vlmResult?.communityCards?.joinToString(",") { "${it.rank}${it.suit}" } ?: "",
            chipDelta = chipDelta,
            chipStatus = chipStatus,
            potDelta = potDelta,
            totalTimeMs = totalTimeMs,
            hasError = hasError,
            errorMessage = errorMessage,
            strategySent = strategySent,
            rawResponse = rawResponse  // V2.9.193
        )
        
        synchronized(recognitionLogs) {
            recognitionLogs.add(log)
            if (recognitionLogs.size > MAX_LOGS) {
                recognitionLogs.removeAt(0)
            }
        }
        
        // V2.9.182: 自动持久化到文件
        autoFlushToFile(log)
    }
    
    private var lastChips = 0
    private var lastPot = 0
    
    private fun calcChipDelta(currentChips: Int): Long {
        val delta = if (lastChips > 0) (currentChips - lastChips).toLong() else null
        lastChips = currentChips
        return delta ?: 0L
    }
    
    private fun calcPotDelta(currentPot: Int): Int {
        val delta = if (lastPot > 0) currentPot - lastPot else 0
        lastPot = currentPot
        return delta
    }
    
    private fun determineChipStatus(delta: Long?): String {
        return when {
            delta == null -> "unknown"
            delta == 0L -> "active"
            delta < -100 -> "betting"  // 筹码减少超过100，认为在下注
            delta > 100 -> "won"  // 筹码增加超过100，认为赢了
            else -> "active"  // 小额变化，可能是盲注等
        }
    }
    
    /**
     * 重置筹码追踪（新一手牌时调用）
     */
    fun resetChipTracking() {
        lastChips = 0
        lastPot = 0
    }
    
    /**
     * 导出所有诊断日志为JSON格式
     */
    fun exportAsJson(): String {
        val json = JSONObject()
        json.put("exportTime", dateFormat.format(Date()))
        json.put("version", "2.9.184")
        json.put("totalLogs", recognitionLogs.size)
        
        val logsArray = JSONArray()
        synchronized(recognitionLogs) {
            for (log in recognitionLogs) {
                logsArray.put(logToJson(log))
            }
        }
        json.put("logs", logsArray)
        
        // 统计信息
        json.put("stats", generateStats())
        
        return json.toString(2)
    }
    
    private fun logToJson(log: RecognitionLog): JSONObject {
        return JSONObject().apply {
            put("time", log.timeStr)
            put("timestamp", log.timestamp)
            
            // 本地CV信息
            val localCV = JSONObject().apply {
                put("enabled", log.localCVEnabled)
                put("timeMs", log.localCVTimeMs)
                put("handCards", log.localHandCards)
                put("communityCards", log.localCommunityCards)
                put("street", log.localStreet ?: JSONObject.NULL)
            }
            put("localCV", localCV)
            
            // 锁定状态
            val locking = JSONObject().apply {
                put("streetLocked", log.streetLocked ?: JSONObject.NULL)
                put("holeCardsLocked", log.holeCardsLocked)
            }
            put("locking", locking)
            
            // VLM识别结果
            val vlm = JSONObject().apply {
                put("timeMs", log.vlmTimeMs)
                put("handCards", log.vlmHandCards)
                put("communityCards", log.vlmCommunityCards)
                put("street", log.vlmStreet)
                put("pot", log.vlmPot)
                put("myChips", log.vlmMyChips)
                put("betToCall", log.vlmBetToCall)
                put("buttons", JSONArray(log.vlmButtons))
                put("dButtonPos", log.vlmDButtonPos)
                put("totalPlayers", log.vlmTotalPlayers)
                put("activePlayers", log.vlmActivePlayers)
                put("blinds", log.vlmBlinds)
            }
            put("vlm", vlm)
            
            // 最终结果
            val final = JSONObject().apply {
                put("street", log.finalStreet)
                put("handCards", log.finalHandCards)
                put("communityCards", log.finalCommunityCards)
            }
            put("final", final)
            
            // 筹码追踪
            val chips = JSONObject().apply {
                put("delta", log.chipDelta ?: JSONObject.NULL)
                put("status", log.chipStatus)
                put("potDelta", log.potDelta)
            }
            put("chips", chips)
            
            // 性能和错误
            put("totalTimeMs", log.totalTimeMs)
            put("hasError", log.hasError)
            if (log.errorMessage != null) {
                put("error", log.errorMessage)
            }
            put("strategySent", log.strategySent)
            // V2.9.193: 导出API原始响应——仅失败时记录，截取前500字符避免日志过大
            if (log.rawResponse != null && log.hasError) {
                put("rawApiResponse", log.rawResponse.take(500))
            }
        }
    }
    
    private fun generateStats(): JSONObject {
        return JSONObject().apply {
            val total = recognitionLogs.size
            val errors = recognitionLogs.count { it.hasError }
            val successRate = if (total > 0) (total - errors) * 100 / total else 0
            
            put("totalRecognitions", total)
            put("errorCount", errors)
            put("successRate", "$successRate%")
            
            // 本地CV使用情况
            val cvUsed = recognitionLogs.count { it.localCVEnabled && it.localCVTimeMs > 0 }
            put("localCVUsedCount", cvUsed)
            
            // 平均耗时
            val avgLocalCV = if (cvUsed > 0) {
                recognitionLogs.filter { it.localCVEnabled && it.localCVTimeMs > 0 }
                    .map { it.localCVTimeMs }.average().toLong()
            } else 0L
            val avgVLM = if (total > 0) {
                recognitionLogs.filter { it.vlmTimeMs > 0 }
                    .map { it.vlmTimeMs }.average().toLong()
            } else 0L
            
            put("avgLocalCVTimeMs", avgLocalCV)
            put("avgVLMTimeMs", avgVLM)
            
            // 筹码变化统计
            val chipIncreases = recognitionLogs.count { (it.chipDelta ?: 0) > 100 }
            val chipDecreases = recognitionLogs.count { (it.chipDelta ?: 0) < -100 }
            put("chipIncreaseCount", chipIncreases)
            put("chipDecreaseCount", chipDecreases)
        }
    }
    
    /**
     * 清空日志
     */
    fun clear() {
        synchronized(recognitionLogs) {
            recognitionLogs.clear()
        }
        resetChipTracking()
    }
    
    /**
     * 获取最近的错误日志（用于通知栏显示）
     */
    fun getRecentErrors(count: Int = 5): List<String> {
        return synchronized(recognitionLogs) {
            recognitionLogs.filter { it.hasError && it.errorMessage != null }
                .takeLast(count)
                .map { "${it.timeStr}: ${it.errorMessage}" }
        }
    }
}
