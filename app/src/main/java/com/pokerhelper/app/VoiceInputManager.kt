package com.pokerhelper.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import org.json.JSONObject

/**
 * 语音输入管理器 - 解析语音→扑克牌面
 * 
 * 支持的语音格式：
 * - "红心A黑桃K" → ♥A ♠K
 * - "红心10黑桃J 6人桌" → ♥T ♠J, 6 players
 * - "梅花5方块3红心7 公共牌" → ♣5 ♦3 ♥7 (community)
 * - "坐庄家" → dealer position
 * - "跟注5000" → call 5000
 */
object VoiceInputManager {

    private const val TAG = "VoiceInput"
    
    // 花色映射
    private val suitMap = mapOf(
        "红心" to "h", "红桃" to "h", "红" to "h", "heart" to "h", "hearts" to "h",
        "黑桃" to "s", "黑" to "s", "spade" to "s", "spades" to "s",
        "梅花" to "c", "梅" to "c", "club" to "c", "clubs" to "c", "草花" to "c",
        "方块" to "d", "方" to "d", "diamond" to "d", "diamonds" to "d", "钻" to "d", "钻石" to "d"
    )
    
    // 牌面映射
    private val rankMap = mapOf(
        "A" to "A", "a" to "A", "爱" to "A", "1" to "A", "ace" to "A",
        "K" to "K", "k" to "K", "凯" to "K", "king" to "K", "老K" to "K",
        "Q" to "Q", "q" to "Q", "圈" to "Q", "queen" to "Q", "皮蛋" to "Q",
        "J" to "J", "j" to "J", "勾" to "J", "jack" to "J", "丁" to "J",
        "10" to "T", "十" to "T", "ten" to "T",
        "9" to "9", "九" to "9", "nine" to "9",
        "8" to "8", "八" to "8", "eight" to "8",
        "7" to "7", "七" to "7", "seven" to "7",
        "6" to "6", "六" to "6", "six" to "6",
        "5" to "5", "五" to "5", "five" to "5",
        "4" to "4", "四" to "4", "four" to "4",
        "3" to "3", "三" to "3", "three" to "3",
        "2" to "2", "二" to "2", "two" to "2"
    )
    
    // 位置映射
    private val positionMap = mapOf(
        "庄家" to "D", "庄" to "D", "按钮" to "D", "button" to "D", "dealer" to "D",
        "小盲" to "SB", "小" to "SB", "small blind" to "SB",
        "大盲" to "BB", "大" to "BB", "big blind" to "BB",
        "枪口" to "UTG", "utg" to "UTG",
        "关位" to "CO", "_cutoff" to "CO", "cutoff" to "CO",
        "劫位" to "HJ", "hijack" to "HJ"
    )
    
    // 桌型映射
    private val tableSizeMap = mapOf(
        "2人" to 2, "两人" to 2, "heads up" to 2,
        "3人" to 3, "三人" to 3,
        "4人" to 4, "四人" to 4,
        "5人" to 5, "五人" to 5,
        "6人" to 6, "六人" to 6,
        "7人" to 7, "七人" to 7,
        "8人" to 8, "八人" to 8,
        "9人" to 9, "九人" to 9,
        "10人" to 10, "十人" to 10,
        "满员" to 9, "满桌" to 9
    )
    
    data class VoiceResult(
        val holeCards: List<String>,    // ["hA", "sK"] 手牌
        val communityCards: List<String>, // ["c5", "d3", "h7"] 公共牌
        val position: String?,          // "D", "SB", "BB" 等
        val tableSize: Int?,            // 6, 9 等
        val rawText: String             // 原始语音文本
    )

    /**
     * 解析语音文本为结构化数据
     */
    fun parseVoiceText(text: String): VoiceResult {
        val normalized = text.trim().lowercase()
            .replace(" ", "")
            .replace("，", "")
            .replace(",", "")
        
        val holeCards = mutableListOf<String>()
        val communityCards = mutableListOf<String>()
        var position: String? = null
        var tableSize: Int? = null
        
        // 提取桌型
        for ((key, value) in tableSizeMap) {
            if (normalized.contains(key)) {
                tableSize = value
                break
            }
        }
        
        // 提取位置
        for ((key, value) in positionMap) {
            if (normalized.contains(key.lowercase())) {
                position = value
                break
            }
        }
        
        // 提取牌面 - 从语音文本中找 花色+数字 的组合
        val cardPattern = Regex("(红心|红桃|黑桃|梅花|方块|草花|heart|spade|club|diamond)[\\s]?(A|K|Q|J|10|2|3|4|5|6|7|8|9|a|k|q|j|十|一|二|三|四|五|六|七|八|九)", RegexOption.IGNORE_CASE)
        
        val matches = cardPattern.findAll(text)
        for (match in matches) {
            val suitStr = match.groupValues[1]
            val rankStr = match.groupValues[2]
            
            val suit = suitMap.entries.find { normalized.contains(it.key.lowercase()) }?.value
            val rank = rankMap[rankStr] ?: rankMap[rankStr.lowercase()]
            
            if (suit != null && rank != null) {
                val card = "$suit$rank"
                // 简单判断：前2张是手牌，后面是公共牌
                if (holeCards.size < 2) {
                    holeCards.add(card)
                } else {
                    communityCards.add(card)
                }
            }
        }
        
        // 备用方案：直接识别单独的牌面文字
        if (holeCards.isEmpty()) {
            // 尝试匹配 "A黑桃K" 这类简写
            val simplePattern = Regex("([AKQJTakqjt2-9])\\s*(红心|红桃|黑桃|梅花|方块|草花)\\s*([AKQJTakqjt2-9])")
            val simpleMatch = simplePattern.find(text)
            if (simpleMatch != null) {
                val r1 = rankMap[simpleMatch.groupValues[1].uppercase()]
                val suitStr = simpleMatch.groupValues[2]
                val r2 = rankMap[simpleMatch.groupValues[3].uppercase()]
                val suit = suitMap.entries.find { normalized.contains(it.key.lowercase()) }?.value
                
                if (r1 != null && suit != null && r2 != null) {
                    holeCards.add("$suit$r1")
                    holeCards.add("$suit$r2")
                }
            }
        }
        
        return VoiceResult(
            holeCards = holeCards,
            communityCards = communityCards,
            position = position,
            tableSize = tableSize,
            rawText = text
        )
    }

    /**
     * 转为JSON（供WebView使用）
     */
    fun toJson(result: VoiceResult): String {
        return JSONObject().apply {
            put("holeCards", org.json.JSONArray(result.holeCards))
            put("communityCards", org.json.JSONArray(result.communityCards))
            put("position", result.position ?: JSONObject.NULL)
            put("tableSize", result.tableSize ?: JSONObject.NULL)
            put("rawText", result.rawText)
        }.toString()
    }
}
