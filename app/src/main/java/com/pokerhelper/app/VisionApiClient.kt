package com.pokerhelper.app

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * V1.3: 视觉API客户端 - 截屏→GPT-4o-mini识别牌面
 * 支持OpenAI兼容API（GPT-4o-mini / 通义千问VL / DeepSeek等）
 */
object VisionApiClient {

    private const val TAG = "VisionAPI"
    
    // 默认配置（可在App内修改）
    var apiProvider = "openai"  // openai / dashscope / custom
    var apiKey = ""
    var apiUrl = "https://api.openai.com/v1/chat/completions"
    var modelName = "gpt-4o-mini"
    var lastError = ""
    var lastResult: VisionResult? = null
        private set
    var lastResultTime: Long = 0
        private set

    data class VisionResult(
        val holeCards: List<CardInfo>,    // 手牌
        val communityCards: List<CardInfo>, // 公共牌
        val potSize: Int,                   // 底池
        val playerChips: Int,               // 自己筹码
        val totalPlayers: Int,              // 总人数
        val activePlayers: Int,             // 活跃人数
        val myPosition: String,             // 我的位置(SB/BB/UTG/MP/CO/BTN)
        val street: String,                 // 翻前/翻牌/转牌/河牌
        val toCall: Int,                    // 需要跟注金额
        val minRaise: Int,                  // 最小加注
        val rawResponse: String             // 原始API返回
    )

    data class CardInfo(
        val rank: String,  // A K Q J T 9 8 7 6 5 4 3 2
        val suit: String   // s h d c (spade/heart/diamond/club)
    )

    /**
     * 分析截图 - 返回识别结果
     * @param jpegData JPEG截图数据
     * @return VisionResult? 识别结果，失败返回null
     */
    fun analyzeScreenshot(jpegData: ByteArray): VisionResult? {
        if (apiKey.isEmpty()) {
            lastError = "未设置API Key"
            return null
        }

        return try {
            // 1. 压缩图片到1920宽度（保持宽高比，高分辨率提升识别精度）
            val compressedJpeg = compressImage(jpegData, maxWidth = 1920)
            val base64Image = Base64.encodeToString(compressedJpeg, Base64.NO_WRAP)
            val dataUri = "data:image/jpeg;base64,$base64Image"

            Log.d(TAG, "Image compressed: ${jpegData.size / 1024}KB -> ${compressedJpeg.size / 1024}KB")

            // 2. 构建API请求
            val requestJson = buildRequest(dataUri)

            // 3. 发送请求
            val response = sendRequest(requestJson)

            // 4. 解析响应
            val result = parseResponse(response)

            var correctedResult: VisionResult? = null

            if (result != null) {
                lastResult = result
                lastResultTime = System.currentTimeMillis()
                lastError = ""
                // V2.2: 校验识别结果合理性，并自动纠正street
                val warnings = validateResult(result)
                // V2.2: street和公共牌数矛盾时，以公共牌数为准纠正street
                correctedResult = result
                val commCount = result.communityCards.size
                val correctStreet = when {
                    commCount == 0 -> "preflop"
                    commCount == 3 -> "flop"
                    commCount == 4 -> "turn"
                    commCount == 5 -> "river"
                    else -> null // 1,2张公共牌不合理，不纠正
                }
                if (correctStreet != null && result.street.lowercase() != correctStreet) {
                    Log.w(TAG, "street纠正: ${result.street}→$correctStreet (公共牌数=$commCount)")
                    correctedResult = result.copy(street = correctStreet)
                    lastResult = correctedResult
                }
                if (warnings.isNotEmpty()) {
                    lastError = warnings.joinToString("; ")
                    Log.w(TAG, "识别结果有疑问: $lastError")
                }
                Log.d(TAG, "识别成功: ${correctedResult.holeCards.joinToString()} | ${correctedResult.communityCards.joinToString()} | 底池${correctedResult.potSize} | ${correctedResult.totalPlayers}人${if(warnings.isNotEmpty()) " ⚠️$lastError" else ""}")
            }

            correctedResult
        } catch (e: Exception) {
            lastError = "API错误: ${e.message}"
            Log.e(TAG, "analyzeScreenshot failed", e)
            null
        }
    }

    private fun compressImage(jpegData: ByteArray, maxWidth: Int): ByteArray {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size) ?: return jpegData

        // 计算缩放比例
        val scale = if (bitmap.width > maxWidth) maxWidth.toFloat() / bitmap.width else 1f
        if (scale >= 1f) {
            // 只压缩质量
            val stream = ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, stream)
            bitmap.recycle()
            return stream.toByteArray()
        }

        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()
        val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        bitmap.recycle()

        val stream = ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, stream)
        scaled.recycle()
        return stream.toByteArray()
    }

    private fun buildRequest(base64Image: String): String {
        val prompt = """你是一个德州扑克牌桌识别专家。请仔细观察这张游戏截图，按以下步骤识别：

★第一步：先数公共牌数量！0张=preflop, 3张=flop, 4张=turn, 5张=river。street必须和公共牌数量一致！
第二步：找到你的手牌(屏幕下方中间位置，2张牌)
第三步：数出实际在场的玩家数(total_players)——只数有筹码显示的头像/座位，空座(灰色/暗色/无筹码)不算！你自己也算一个。注意：桌面上可能有6个座位框，但只有5个有人，那就报5
第四步：识别底池筹码数和你的筹码数

返回JSON格式：
{"hole_cards":[{"rank":"A","suit":"s"}],"community_cards":[],"pot_size":0,"my_chips":0,"total_players":6,"active_players":4,"my_position":"BTN","street":"preflop","to_call":0,"min_raise":0}

严格规则：
- rank只能是: A K Q J T 9 8 7 6 5 4 3 2（用T表示10，不要用10）
- suit: s=黑桃♠(黑色尖顶) h=红心♥(红色心形) d=方块♦(红色菱形) c=梅花♣(黑色三叶)
- 花色区分重点：♠和♣都是黑色，但♠顶部尖、♣像三叶草；♥和♦都是红色，但♥是心形、♦是菱形
- my_position: SB BB UTG MP CO BTN
- street必须与community_cards数量一致：0张→preflop, 3张→flop, 4张→turn, 5张→river
- total_players=在场有筹码的玩家数，空座位不算
- pot_size/to_call/min_raise/my_chips: 纯数字不含逗号
- 如果不是德州扑克游戏界面，所有字段返回默认值
- 如果某信息看不清，用默认值0或空数组，不要猜
- 只返回JSON，不要其他文字"""

        val json = JSONObject().apply {
            put("model", modelName)
            put("max_tokens", 500)
            put("temperature", 0.1)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", base64Image)
                                put("detail", "high")
                            })
                        })
                    })
                })
            })
        }
        return json.toString()
    }

    private fun sendRequest(requestJson: String): String {
        val url = URL(apiUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 30000
        }

        conn.outputStream.use { os ->
            os.write(requestJson.toByteArray(Charsets.UTF_8))
        }

        val responseCode = conn.responseCode
        val responseBody = if (responseCode == 200) {
            conn.inputStream.bufferedReader().readText()
        } else {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $responseCode"
            throw Exception("HTTP $responseCode: $err")
        }
        conn.disconnect()
        return responseBody
    }

    private fun parseResponse(responseBody: String): VisionResult? {
        val responseJson = JSONObject(responseBody)
        val content = responseJson
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")

        // 提取JSON部分（可能被markdown代码块包裹）
        val jsonStr = extractJson(content) ?: return null
        val data = JSONObject(jsonStr)

        return VisionResult(
            holeCards = parseCards(data.optJSONArray("hole_cards")),
            communityCards = parseCards(data.optJSONArray("community_cards")),
            potSize = data.optInt("pot_size", 0),
            playerChips = data.optInt("my_chips", 0),
            totalPlayers = data.optInt("total_players", 6),
            activePlayers = data.optInt("active_players", 2),
            myPosition = data.optString("my_position", ""),
            street = data.optString("street", "preflop"),
            toCall = data.optInt("to_call", 0),
            minRaise = data.optInt("min_raise", 0),
            rawResponse = content
        )
    }

    private fun extractJson(text: String): String? {
        // 尝试直接解析
        try { JSONObject(text); return text } catch (_: Exception) {}

        // 尝试提取markdown代码块中的JSON
        val regex = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```")
        val match = regex.find(text)
        if (match != null) {
            try { JSONObject(match.groupValues[1].trim()); return match.groupValues[1].trim() } catch (_: Exception) {}
        }

        // 尝试找花括号包围的内容
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val sub = text.substring(start, end + 1)
            try { JSONObject(sub); return sub } catch (_: Exception) {}
        }

        return null
    }

    private fun parseCards(arr: JSONArray?): List<CardInfo> {
        if (arr == null) return emptyList()
        val cards = mutableListOf<CardInfo>()
        val validRanks = setOf("A","K","Q","J","T","9","8","7","6","5","4","3","2")
        val rankNormalize = mapOf("10" to "T", "1" to "A") // API可能返回10而非T
        for (i in 0 until arr.length()) {
            try {
                val element = arr.get(i)
                when (element) {
                    is JSONObject -> {
                        var rank = element.optString("rank", "")
                        rank = rankNormalize[rank] ?: rank
                        val suit = element.optString("suit", "")
                        if (rank in validRanks && suit.isNotEmpty()) {
                            cards.add(CardInfo(rank = rank, suit = suit))
                        } else {
                            Log.w(TAG, "parseCards: 跳过无效对象牌 rank=$rank suit=$suit")
                        }
                    }
                    is String -> {
                        // 支持 "Jh" "As" "T♦" "10h" 等字符串格式
                        if (element.length >= 2) {
                            val suitChar = element.last().lowercaseChar()
                            val suitMap = mapOf(
                                's' to "s", 'h' to "h", 'd' to "d", 'c' to "c",
                                '♠' to "s", '♥' to "h", '♦' to "d", '♣' to "c"
                            )
                            val suit = suitMap[suitChar] ?: ""
                            var rank = element.substring(0, element.length - 1).trim().uppercase()
                            rank = rankNormalize[rank] ?: rank
                            if (rank in validRanks && suit.isNotEmpty()) {
                                cards.add(CardInfo(rank = rank, suit = suit))
                            } else {
                                Log.w(TAG, "parseCards: 跳过无效字符串牌 '$element' -> rank=$rank suit=$suit")
                            }
                        }
                    }
                    else -> {
                        Log.w(TAG, "parseCards: 跳过不支持的格式: ${element?.javaClass?.simpleName}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "parseCards: 第${i}张牌解析异常: ${e.message}")
            }
        }
        return cards
    }

    /**
     * V2.0: 校验识别结果合理性，返回警告列表
     */
    private fun validateResult(result: VisionResult): List<String> {
        val warnings = mutableListOf<String>()
        val validRanks = setOf("A","K","Q","J","T","9","8","7","6","5","4","3","2")
        val validSuits = setOf("s","h","d","c")

        // 检查手牌有效性
        for (card in result.holeCards) {
            if (card.rank !in validRanks) warnings.add("无效点数:${card.rank}")
            if (card.suit !in validSuits) warnings.add("无效花色:${card.suit}")
        }
        // 检查公共牌有效性
        for (card in result.communityCards) {
            if (card.rank !in validRanks) warnings.add("公共牌无效点数:${card.rank}")
            if (card.suit !in validSuits) warnings.add("公共牌无效花色:${card.suit}")
        }
        // 检查手牌数量
        if (result.holeCards.size != 2) warnings.add("手牌数${result.holeCards.size}≠2")
        // 检查公共牌数量合理性
        if (result.communityCards.size !in 0..5) warnings.add("公共牌数${result.communityCards.size}异常")
        // 检查street和公共牌数量一致性
        val commCount = result.communityCards.size
        val expectedComm = when(result.street.lowercase()) {
            "preflop", "pre" -> 0
            "flop" -> 3
            "turn" -> 4
            "river" -> 5
            else -> -1
        }
        if (expectedComm >= 0 && commCount != expectedComm) {
            warnings.add("${result.street}应有${expectedComm}张公共牌,识别到${commCount}张")
        }
        // 检查人数合理性
        if (result.totalPlayers < 2 || result.totalPlayers > 20) warnings.add("人数${result.totalPlayers}异常")
        // 检查重复牌
        val allCards = result.holeCards + result.communityCards
        val cardSet = mutableSetOf<String>()
        for (card in allCards) {
            val key = card.rank + card.suit
            if (!cardSet.add(key)) warnings.add("重复牌:${card.rank}${card.suit}")
        }

        return warnings
    }

    /**
     * 将VisionResult转为JSON，供WebView策略引擎使用
     */
    fun toJson(result: VisionResult): String {
        val warnings = validateResult(result)
        val json = JSONObject().apply {
            put("hole_cards", JSONArray(result.holeCards.map { 
                JSONObject().apply { put("rank", it.rank); put("suit", it.suit) }
            }))
            put("community_cards", JSONArray(result.communityCards.map {
                JSONObject().apply { put("rank", it.rank); put("suit", it.suit) }
            }))
            put("pot_size", result.potSize)
            put("my_chips", result.playerChips)
            put("total_players", result.totalPlayers)
            put("active_players", result.activePlayers)
            put("my_position", result.myPosition)
            put("street", result.street)
            put("to_call", result.toCall)
            put("min_raise", result.minRaise)
            // V2.0: 包含校验警告
            if (warnings.isNotEmpty()) {
                put("_warnings", JSONArray(warnings))
            }
        }
        return json.toString()
    }

    /**
     * 根据provider更新API配置
     */
    fun updateConfig(provider: String, key: String) {
        apiProvider = provider
        apiKey = key
        when (provider) {
            "openai" -> {
                apiUrl = "https://api.openai.com/v1/chat/completions"
                modelName = "gpt-4o-mini"
            }
            "dashscope" -> {
                apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
                modelName = "qwen-vl-plus"
            }
            "deepseek" -> {
                apiUrl = "https://api.deepseek.com/v1/chat/completions"
                modelName = "deepseek-chat-vision"
            }
            "siliconflow" -> {
                apiUrl = "https://api.siliconflow.cn/v1/chat/completions"
                modelName = "Qwen/Qwen3-VL-8B-Instruct"
            }
        }
    }
}
