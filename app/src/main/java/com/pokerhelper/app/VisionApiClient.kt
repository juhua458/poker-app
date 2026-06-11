package com.pokerhelper.app

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * V2.9.83: 视觉API客户端 - 单通道vl-plus提速+公共牌去花色+手牌锁定
 * 核心改动：
 * 1. 去掉flash通道，只用vl-plus（实测vl-plus更准，省一半API时间）
 * 2. D按钮识别合并到主调用（一个prompt搞定，不再并行3路）
 * 3. 公共牌只传rank不传suit（花色不准不如不传）
 * 4. 手牌锁定：rank比较，花色波动不触发重置
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

    // V2.9.78: D按钮位置识别——用vl-plus并行调用
    var dButtonPosition: String = ""  // 上次识别的D按钮位置
    var dButtonLocked: String = ""  // V2.9.80: D按钮锁定值
        private set

    // V2.9.81: 手牌锁定状态
    var holeCardsLocked: List<CardInfo>? = null  // 手牌锁定值（双通道一致后锁定）
    var suitUncertain: Boolean = false  // 花色不确定标记
    var lockReason: String = ""  // 锁定原因日志

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
        val buttons: List<String>,          // 操作按钮文字（如"弃牌""跟注10K""加注"）
        val blindSB: Int,                   // 小盲注（V2.9.41: 从桌面标题识别）
        val blindBB: Int,                   // 大盲注（V2.9.41: 从桌面标题识别）
        val ante: Int,                      // V2.9.43: 前注（每人需投入的前注）
        val players: List<PlayerInfo>,      // V2.9.72: 对手位置信息（辅助校验）
        val dButtonPosition: String,        // V2.9.78: D按钮位置(bottom-center/left-bottom/left-top/top-center/right-top/right-bottom/not_found)
        val oppSeats: List<OppSeatInfo>,    // V2.9.85: 对手座位信息（画像追踪）
        val rawResponse: String             // 原始API返回
    )

    data class CardInfo(
        val rank: String,  // A K Q J T 9 8 7 6 5 4 3 2
        val suit: String   // s h d c (spade/heart/diamond/club)
    )

    // V2.9.72: 对手位置信息（辅助校验，策略引擎不依赖）
    data class PlayerInfo(
        val position: String,   // 位置: top/left/right_top/right_bottom
        val bet: Int,           // 下注额（绿色框里的数字），未下注=0
        val chips: Int,         // 筹码量
        val active: Boolean     // 是否在牌局中（头像被牌遮挡=active）
    )

    // V2.9.85: 对手座位信息（用于画像追踪）
    data class OppSeatInfo(
        val pos: String,        // 座位位置: left-bottom/left-top/top-center/right-top/right-bottom
        val chips: Int,         // 筹码数字（头像下方的总筹码）
        val status: String,     // active=还在牌局 / folded=已弃牌
        val nickname: String,   // V2.9.86: 对手昵称（跨座跨桌追踪）
        val bet: Int            // V2.9.86: 当前轮下注额（桌面绿色框数字，没下注=0）
    )

    /**
     * 分析截图 - 返回识别结果
     * @param jpegData JPEG截图数据
     * @return VisionResult? 识别结果，失败返回null
     */
    fun analyzeScreenshot(jpegData: ByteArray): VisionResult? {
        if (apiKey.isEmpty()) { lastError = "未设置API Key"; return null }

        return try {
            val compressedJpeg = compressImage(jpegData, maxWidth = 1080)
            val base64Image = Base64.encodeToString(compressedJpeg, Base64.NO_WRAP)
            val dataUri = "data:image/jpeg;base64,$base64Image"
            Log.d(TAG, "Image compressed: ${jpegData.size / 1024}KB -> ${compressedJpeg.size / 1024}KB")

            // V2.9.83: 单通道vl-plus——去掉flash并行，速度翻倍
            val result = try {
                val requestJson = buildRequest(dataUri, model = "qwen-vl-plus")
                parseResponse(sendRequest(requestJson))
            } catch (e: Exception) {
                lastError = "API错误: ${e.message}"; Log.e(TAG, "vl-plus失败", e); return null
            }
            if (result == null) { lastError = "API返回空结果"; return null }

            // V2.9.83: 所有牌去花色——只保留rank，suit置空
            val holeCardsNoSuit = result.holeCards.map { it.copy(suit = "") }
            val commCardsNoSuit = result.communityCards.map { it.copy(suit = "") }

            // V2.9.82: 手牌锁定逻辑——只用rank判断是否新一手牌（花色波动不触发重置）
            val currentRankKey = result.holeCards.joinToString(",") { it.rank }
            val lastRankKey = holeCardsLocked?.joinToString(",") { it.rank } ?: ""

            // rank变了→确定是新一手牌→重置
            if (lastRankKey.isNotEmpty() && currentRankKey != lastRankKey) {
                Log.d(TAG, "手牌锁定: 新一手牌(rank: $lastRankKey→$currentRankKey)，重置")
                holeCardsLocked = null; dButtonLocked = ""
            }

            // 已锁定→用锁定值
            if (holeCardsLocked != null) {
                Log.d(TAG, "手牌锁定: 已锁定=${holeCardsLocked!!.map{it.rank}.joinToString()}")
                lockReason = "已锁定，跳过重识"; suitUncertain = false
                var lockedResult = result.copy(holeCards = holeCardsLocked!!, communityCards = commCardsNoSuit)
                val dPosInsured = applyDButtonInsurance(lockedResult.dButtonPosition, holeCardsLocked!!)
                dButtonPosition = dPosInsured
                lockedResult = lockedResult.copy(dButtonPosition = dPosInsured)
                lastResult = lockedResult; lastResultTime = System.currentTimeMillis()
                var corrected = applyStreetCorrection(lockedResult)
                corrected = applyValidationCorrections(corrected); lastResult = corrected
                Log.d(TAG, "识别成功(锁定): ${corrected.holeCards.map{it.rank}.joinToString()} | comm=${corrected.communityCards.map{it.rank}.joinToString()} | 底池${corrected.potSize} | D=$dPosInsured")
                return corrected
            }

            // 未锁定→用当前结果（去花色）
            holeCardsLocked = holeCardsNoSuit; lockReason = "首次识别锁定"
            suitUncertain = false
            Log.d(TAG, "手牌锁定: 首次识别，锁定=${holeCardsNoSuit.map{it.rank}.joinToString()}")

            var correctedResult = result.copy(holeCards = holeCardsNoSuit, communityCards = commCardsNoSuit)
            // D按钮保险
            val dPosInsured = applyDButtonInsurance(correctedResult.dButtonPosition, correctedResult.holeCards)
            dButtonPosition = dPosInsured
            correctedResult = correctedResult.copy(dButtonPosition = dPosInsured)

            lastResult = correctedResult; lastResultTime = System.currentTimeMillis(); lastError = ""
            correctedResult = applyStreetCorrection(correctedResult)
            correctedResult = applyValidationCorrections(correctedResult); lastResult = correctedResult
            Log.d(TAG, "识别成功: ${correctedResult.holeCards.joinToString()} | comm=${correctedResult.communityCards.map{it.rank}.joinToString()} | 底池${correctedResult.potSize} | ${correctedResult.totalPlayers}桌 | D=$dPosInsured")
            correctedResult
        } catch (e: Exception) {
            lastError = "API错误: ${e.message}"; Log.e(TAG, "analyzeScreenshot failed", e); null
        }
    }

    /** V2.9.81: street纠错 */
    private fun applyStreetCorrection(result: VisionResult): VisionResult {
        val commCount = result.communityCards.size
        val correctStreet = when {
            commCount == 0 -> "preflop"; commCount == 3 -> "flop"
            commCount == 4 -> "turn"; commCount == 5 -> "river"; else -> null
        }
        return if (correctStreet != null && result.street.lowercase() != correctStreet) {
            Log.w(TAG, "street纠正: ${result.street}→$correctStreet"); result.copy(street = correctStreet)
        } else result
    }

    /**
     * V2.9.78: 用qwen-vl-plus识别D按钮位置
     * 专门用更强模型识别庄家按钮的屏幕位置，flash无法准确区分左上/左下
     * 返回: bottom-center / left-bottom / left-top / top-center / right-top / right-bottom / not_found
     */
    /**
     * V2.9.80: D按钮保险层
     * 规则1: 同一手牌内，D按钮位置不变——首次识别后锁定，突变时用旧值
     * 规则2: 邻近位容错——left-top/left-bottom同属左侧，right-top/right-bottom同属右侧
     * 规则3: 新一手牌（手牌变化）时重置锁定
     */
    private fun applyDButtonInsurance(rawPos: String, currentCards: List<CardInfo>): String {
        // V2.9.82: 规则3: 只看rank判断是否新一手牌（花色波动不重置D按钮锁定）
        val rankKey = currentCards.joinToString(",") { it.rank }
        val lastRankKey = lastResult?.holeCards?.joinToString(",") { it.rank } ?: ""
        if (rankKey != lastRankKey && lastRankKey.isNotEmpty()) {
            dButtonLocked = ""
            Log.d(TAG, "D按钮保险: 新一手牌(rank: $lastRankKey→$rankKey)，重置锁定")
        }

        if (rawPos.isEmpty() || rawPos == "not_found") {
            // 识别失败，用锁定值
            if (dButtonLocked.isNotEmpty()) {
                Log.d(TAG, "D按钮保险: 识别失败，用锁定值=$dButtonLocked")
                return dButtonLocked
            }
            return rawPos
        }

        // 规则1: 首次识别→锁定
        if (dButtonLocked.isEmpty()) {
            dButtonLocked = rawPos
            Log.d(TAG, "D按钮保险: 首次锁定=$rawPos")
            return rawPos
        }

        // 规则2: 突变检测——是否和锁定值一致或邻近
        val isSame = rawPos == dButtonLocked
        val isNeighbor = isNeighborPosition(rawPos, dButtonLocked)

        if (isSame) {
            Log.d(TAG, "D按钮保险: 一致=$rawPos")
            return rawPos
        }
        if (isNeighbor) {
            // 邻近位，可能是同一位置的不同判断，取锁定值
            Log.d(TAG, "D按钮保险: 邻近位(${rawPos}≈$dButtonLocked)，取锁定值=$dButtonLocked")
            return dButtonLocked
        }
        // 非邻近的突变，不可信，用锁定值
        Log.w(TAG, "D按钮保险: 突变(${dButtonLocked}→${rawPos})，不可信，保留锁定值=$dButtonLocked")
        return dButtonLocked
    }

    /** V2.9.80: 判断两个位置是否邻近（同侧） */
    private fun isNeighborPosition(pos1: String, pos2: String): Boolean {
        if (pos1.isEmpty() || pos2.isEmpty()) return false
        val side1 = when {
            pos1.contains("left") -> "left"
            pos1.contains("right") -> "right"
            pos1.contains("top-center") -> "top"
            pos1.contains("bottom-center") -> "bottom"
            else -> pos1
        }
        val side2 = when {
            pos2.contains("left") -> "left"
            pos2.contains("right") -> "right"
            pos2.contains("top-center") -> "top"
            pos2.contains("bottom-center") -> "bottom"
            else -> pos2
        }
        return side1 == side2
    }


    private fun compressImage(jpegData: ByteArray, maxWidth: Int): ByteArray {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size) ?: return jpegData

        // V2.9.74: 不裁剪底部——操作按钮和手牌在底部，裁掉会导致识别失败
        // 只裁掉顶部2%状态栏（纯黑无信息）
        val cropTop = (bitmap.height * 0.02).toInt()
        val cropBottom = bitmap.height  // 保留到底部
        val cropped = if (cropTop > 0 || cropBottom < bitmap.height) {
            try {
                android.graphics.Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.width, cropBottom - cropTop)
            } catch (_: Exception) {
                bitmap
            }
        } else {
            bitmap
        }
        if (cropped !== bitmap) bitmap.recycle()

        // 计算缩放比例
        val scale = if (cropped.width > maxWidth) maxWidth.toFloat() / cropped.width else 1f
        val scaled = if (scale < 1f) {
            val newWidth = (cropped.width * scale).toInt()
            val newHeight = (cropped.height * scale).toInt()
            val s = android.graphics.Bitmap.createScaledBitmap(cropped, newWidth, newHeight, true)
            cropped.recycle()
            s
        } else {
            cropped
        }

        val stream = ByteArrayOutputStream()
        // V2.9.74: 提高JPEG质量65→85，牌面细节需要高保真才能识别
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)
        scaled.recycle()
        return stream.toByteArray()
    }

    private fun buildRequest(base64Image: String, model: String? = null): String {
        val prompt = """识别德州扑克截图(只认rank不认花色):
pot_size=底池数字 my_chips=左下头像下筹码 hole_cards=底部2张rank(AKQJT98765432) community_cards=中央0/3/4/5张rank d_button_pos=D按钮座位(bottom-center/left-bottom/left-top/top-center/right-top/right-bottom/not_found) buttons=底部按钮文字 total_players=总座数 active_players=活跃数 blind_sb/blind_bb=标题盲注 to_call=跟注额 opp_seats=对手[{pos,chips,status(active/folded),nickname,bet}]
❌pot_size≠my_chips
JSON:{"hole_cards":[{"rank":"A"}],"community_cards":[{"rank":"K"}],"buttons":["弃牌","跟注500","加注"],"my_chips":0,"pot_size":0,"to_call":0,"total_players":5,"active_players":3,"street":"preflop","blind_sb":200,"blind_bb":500,"ante":0,"d_button_pos":"left-top","opp_seats":[{"pos":"left-bottom","chips":3810,"status":"folded","nickname":"P1","bet":0}]}
只返回JSON"""

        val json = JSONObject().apply {
            put("model", model ?: modelName)
            put("max_tokens", 800)
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
            // V2.9.48: 缩短超时，快速反馈
            connectTimeout = 8000
            readTimeout = 20000
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
        // V2.9.107: 不主动disconnect，复用HTTP Keep-Alive连接省~100-200ms
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

        // V2.9.10: 解析按钮文字
        val buttonsArr = data.optJSONArray("buttons")
        val buttons = if (buttonsArr != null) {
            (0 until buttonsArr.length()).map { buttonsArr.getString(it) }
        } else emptyList()

        // V2.9.72: 解析对手位置信息（辅助校验，失败不影响主流程）
        val playersArr = data.optJSONArray("players")
        val players = if (playersArr != null) {
            try {
                (0 until playersArr.length()).mapNotNull { i ->
                    val pObj = playersArr.optJSONObject(i) ?: return@mapNotNull null
                    val pos = pObj.optString("position", "")
                    val betVal = pObj.optInt("bet", 0)
                    val chipsVal = pObj.optInt("chips", 0)
                    val activeVal = pObj.optBoolean("active", true)
                    if (pos.isNotEmpty()) PlayerInfo(pos, betVal, chipsVal, activeVal) else null
                }
            } catch (e: Exception) {
                Log.w(TAG, "players解析失败: ${e.message}")
                emptyList()
            }
        } else emptyList()

        // V2.9.10: 从按钮文字解析跟注金额（比模型直接识别更可靠）
        val callFromButtons = parseCallAmountFromButtons(buttons)
        val finalToCall = if (callFromButtons >= 0) callFromButtons else data.optInt("to_call", 0)

        // V2.9.41: 解析盲注级别
        val blindSB = parseChipValue(data, "blind_sb")
        val blindBB = parseChipValue(data, "blind_bb")
        // V2.9.43: 解析前注
        val ante = parseChipValue(data, "ante")

        return VisionResult(
            holeCards = parseCards(data.optJSONArray("hole_cards")),
            communityCards = parseCards(data.optJSONArray("community_cards")),
            potSize = parsePotSize(data, "pot_size"),
            playerChips = parseChipValue(data, "my_chips"),
            totalPlayers = data.optInt("total_players", 6),
            activePlayers = data.optInt("active_players", 2),
            myPosition = data.optString("my_position", ""),
            street = data.optString("street", "preflop"),
            toCall = finalToCall,
            minRaise = data.optInt("min_raise", 0),
            buttons = buttons,
            players = players,
            blindSB = blindSB,
            blindBB = blindBB,
            ante = ante,
            dButtonPosition = data.optString("d_button_pos", ""),  // V2.9.83: 从主调用解析，不再并行
            oppSeats = parseOppSeats(data.optJSONArray("opp_seats")),  // V2.9.85: 对手座位
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
                        val suit = element.optString("suit", "")  // V2.9.83: suit可选，为空也合法
                        if (rank in validRanks) {
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

    // V2.9.86: 解析对手座位信息（含nickname+bet）
    private fun parseOppSeats(arr: JSONArray?): List<OppSeatInfo> {
        if (arr == null) return emptyList()
        return try {
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val pos = obj.optString("pos", "")
                val chips = obj.optInt("chips", 0)
                val status = obj.optString("status", "active")
                val nickname = obj.optString("nickname", "")
                val bet = obj.optInt("bet", 0)
                if (pos.isNotEmpty()) OppSeatInfo(pos, chips, status, nickname, bet) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "opp_seats解析失败: ${e.message}")
            emptyList()
        }
    }

    /**
     * V2.0: 校验识别结果合理性，返回警告列表
     */
    /**
     * V2.9.51: 从按钮文字解析跟注金额（比模型直接识别更可靠）
     * "跟注10K" → 10000, "跟注87K" → 87000, "跟注5000" → 5000, "跟注3,194" → 3194, "过牌" → 0
     * @return 跟注金额，无跟注按钮返回-1
     */
    private fun parseCallAmountFromButtons(buttons: List<String>): Int {
        for (btn in buttons) {
            // V2.9.41: GG扑克用"让牌"代替"过牌"
            if (btn.contains("过牌") || btn.contains("让牌")) return 0
            if (btn.contains("跟注")) {
                val numStr = btn.replace("跟注", "").trim()
                if (numStr.isEmpty()) return 0
                return try {
                    // V2.9.51: 先去掉逗号再解析（"3,194"→"3194"）
                    val cleaned = numStr.replace(",", "")
                    if (cleaned.endsWith("K", ignoreCase = true)) {
                        (cleaned.dropLast(1).toFloat() * 1000).toInt()
                    } else if (cleaned.endsWith("M", ignoreCase = true)) {
                        (cleaned.dropLast(1).toFloat() * 1000000).toInt()
                    } else {
                        cleaned.toInt()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "parseCallAmount失败: btn='$btn' err=${e.message}")
                    -1
                }
            }
            // V2.9.51: GG全押按钮 "全押3,194" 或 "全下5,000"
            if (btn.contains("全押") || btn.contains("全下")) {
                val numStr = btn.replace("全押", "").replace("全下", "").trim()
                if (numStr.isEmpty()) return -1 // 全押无金额，需从其他来源获取
                return try {
                    val cleaned = numStr.replace(",", "")
                    if (cleaned.endsWith("K", ignoreCase = true)) {
                        (cleaned.dropLast(1).toFloat() * 1000).toInt()
                    } else if (cleaned.endsWith("M", ignoreCase = true)) {
                        (cleaned.dropLast(1).toFloat() * 1000000).toInt()
                    } else {
                        cleaned.toInt()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "parseAllInAmount失败: btn='$btn' err=${e.message}")
                    -1
                }
            }
        }
        return -1 // 没有跟注/过牌按钮
    }

    /**
     * V2.9.48: 解析底池数值，处理逗号分隔、K/M后缀
     * API可能返回"5617"、"5,617"、"5.6K"等格式
     */
    private fun parsePotSize(data: JSONObject, key: String): Int {
        val raw = data.opt(key)
        if (raw == null) return 0
        return when (raw) {
            is Int -> raw
            is Long -> raw.toInt()
            is Double -> raw.toInt()
            is String -> parseChipString(raw.toString())
            else -> data.optInt(key, 0)
        }
    }

    /**
     * V2.9.48: 解析筹码数值，同理解析逗号和K/M后缀
     */
    private fun parseChipValue(data: JSONObject, key: String): Int {
        val raw = data.opt(key)
        if (raw == null) return 0
        return when (raw) {
            is Int -> raw
            is Long -> raw.toInt()
            is Double -> raw.toInt()
            is String -> parseChipString(raw.toString())
            else -> data.optInt(key, 0)
        }
    }

    /**
     * V2.9.48: 解析筹码字符串，支持"5,617"→5617, "10K"→10000, "1.5M"→1500000
     */
    private fun parseChipString(s: String): Int {
        val trimmed = s.trim().replace(",", "")
        return try {
            when {
                trimmed.endsWith("K", ignoreCase = true) ->
                    (trimmed.dropLast(1).toFloat() * 1000).toInt()
                trimmed.endsWith("M", ignoreCase = true) ->
                    (trimmed.dropLast(1).toFloat() * 1000000).toInt()
                trimmed.contains(".") -> trimmed.toFloat().toInt()
                else -> trimmed.toInt()
            }
        } catch (e: Exception) { 0 }
    }

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
     * V2.9.16: 校验纠错层 - 规则校验+自动纠正API识别结果
     * 核心原则：策略引擎的输入必须是合理的，矛盾数据必须纠错
     * 纠错日志会记录到lastError，方便排查
     */
    private fun applyValidationCorrections(result: VisionResult): VisionResult {
        var corrected = result
        val corrections = mutableListOf<String>()

        // === 规则1: active_players 不能超过 total_players ===
        if (corrected.activePlayers > corrected.totalPlayers) {
            val old = corrected.activePlayers
            corrected = corrected.copy(activePlayers = corrected.totalPlayers)
            corrections.add("active($old)>total(${corrected.totalPlayers})→active=${corrected.totalPlayers}")
        }

        // === 规则2: active_players 至少2人（否则游戏不存在）===
        if (corrected.activePlayers < 2) {
            val old = corrected.activePlayers
            corrected = corrected.copy(activePlayers = corrected.totalPlayers)
            corrections.add("active($old)<2→降级为total=${corrected.totalPlayers}")
        }

        // === 规则3: total_players 至少2人 ===
        if (corrected.totalPlayers < 2) {
            corrected = corrected.copy(totalPlayers = 6, activePlayers = 6)
            corrections.add("total(${result.totalPlayers})<2→默认6人桌")
        }

        // === 规则4: total_players 不能超过20 ===
        if (corrected.totalPlayers > 20) {
            val old = corrected.totalPlayers
            corrected = corrected.copy(totalPlayers = 9, activePlayers = minOf(corrected.activePlayers, 9))
            corrections.add("total($old)>20→上限9")
        }

        // === 规则5: preflop阶段 active_players 应该等于 total_players（没人弃牌）===
        if (corrected.street.lowercase() in listOf("preflop", "pre") 
            && corrected.activePlayers < corrected.totalPlayers) {
            // preflop还没人弃牌，active应该等于total
            val old = corrected.activePlayers
            corrected = corrected.copy(activePlayers = corrected.totalPlayers)
            corrections.add("preflop active($old)<total(${corrected.totalPlayers})→active=total")
        }

        // === 规则6: pot_size 不能为负 ===
        if (corrected.potSize < 0) {
            corrected = corrected.copy(potSize = 0)
            corrections.add("pot(${result.potSize})<0→0")
        }

        // === 规则6b: pot_size与盲注合理性校验 ===
        // 底池不应超过任何单个玩家筹码的5倍（可能把玩家筹码当底池了）
        if (corrected.potSize > 0 && corrected.playerChips > 0 && corrected.potSize > corrected.playerChips * 5) {
            corrections.add("⚠️pot(${corrected.potSize})>>chips(${corrected.playerChips})，可能误读玩家筹码为底池")
            // V2.9.54: 自动纠正——pot和chips可能互换了
            if (corrected.playerChips < corrected.potSize && corrected.playerChips > 0) {
                // chips比pot小很多，可能是把chips识别成了pot
                val swapped = corrected.copy(potSize = corrected.playerChips, playerChips = corrected.potSize)
                corrected = swapped
                corrections.add("🔧pot/chips互换纠正: pot=${corrected.potSize} chips=${corrected.playerChips}")
            }
        }
        // 翻后底池至少应≥2*BB（SB+BB就1.5BB，加前注翻前至少1.5BB）
        val bb = if (corrected.blindBB > 0) corrected.blindBB else if (corrected.blindSB > 0) corrected.blindSB * 2 else 0
        if (bb > 0 && corrected.potSize > 0 && corrected.potSize < bb * 2 && corrected.communityCards.isNotEmpty()) {
            // 翻后底池太小，可能识别错误
            val minPot = bb * 2
            corrections.add("⚠️翻后pot(${corrected.potSize})<2*BB(${minPot})，底池可能识别偏小")
        }

        // === V2.9.54规则6c: 底池/筹码互换检测 ===
        // 翻后如果有公共牌但pot<chips且chips看起来像底池（chips远大于BB*3）
        if (corrected.potSize > 0 && corrected.playerChips > 0 && corrected.communityCards.isNotEmpty()) {
            if (corrected.potSize < corrected.playerChips && corrected.playerChips > bb * 3 && bb > 0) {
                // pot < chips 但 chips远大于BB → 很可能互换了
                val swapped = corrected.copy(potSize = corrected.playerChips, playerChips = corrected.potSize)
                corrected = swapped
                corrections.add("🔧翻后pot/chips互换: pot=${corrected.potSize} chips=${corrected.playerChips}")
            }
        }

        // === 规则7: to_call 不能为负 ===
        if (corrected.toCall < 0) {
            corrected = corrected.copy(toCall = 0)
            corrections.add("to_call(${result.toCall})<0→0")
        }

        // === 规则8: to_call > pot_size 时需警惕（跟注额不应超过底池的极端值）===
        if (corrected.toCall > corrected.potSize * 5 && corrected.potSize > 0) {
            // 这种情况可能是底池识别过小，记录警告但不纠正
            corrections.add("⚠️to_call(${corrected.toCall})远大于pot(${corrected.potSize})，底池可能识别偏小")
        }

        // === 规则9: 翻前无公共牌时，社区牌应为空 ===
        if (corrected.street.lowercase() in listOf("preflop", "pre") 
            && corrected.communityCards.isNotEmpty()) {
            val oldCount = corrected.communityCards.size
            // 不删除公共牌，而是纠正street（以公共牌为准）
            corrections.add("⚠️preflop但有${oldCount}张公共牌，以公共牌数纠正street")
        }

        // === 规则10: 手牌必须有2张 ===
        if (corrected.holeCards.size != 2) {
            corrections.add("⚠️手牌数${corrected.holeCards.size}≠2，策略引擎可能无法工作")
        }

        if (corrections.isNotEmpty()) {
            lastError = corrections.joinToString("; ")
            Log.w(TAG, "校验纠错: $lastError")
        } else {
            Log.d(TAG, "校验纠错: 无需纠正")
        }

        return corrected
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
            // V2.9.10: 输出按钮文字给策略引擎
            put("buttons", JSONArray(result.buttons))
            // V2.9.41: 输出盲注级别
            put("blind_sb", result.blindSB)
            put("blind_bb", result.blindBB)
            // V2.9.43: 输出前注
            put("ante", result.ante)
            // V2.9.72: 输出对手位置信息
            put("players", JSONArray(result.players.map {
                JSONObject().apply {
                    put("position", it.position)
                    put("bet", it.bet)
                    put("chips", it.chips)
                    put("active", it.active)
                }
            }))
            // V2.9.78: 输出D按钮位置
            put("d_button_position", result.dButtonPosition)
            // V2.9.86: 输出对手座位信息（含nickname+bet）
            put("opp_seats", JSONArray(result.oppSeats.map {
                JSONObject().apply {
                    put("pos", it.pos)
                    put("chips", it.chips)
                    put("status", it.status)
                    put("nickname", it.nickname)
                    put("bet", it.bet)
                }
            }))
            // V2.9.81: 手牌锁定和花色保险
            put("suit_uncertain", suitUncertain)
            put("hole_cards_locked", holeCardsLocked != null)
            put("lock_reason", lockReason)
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
