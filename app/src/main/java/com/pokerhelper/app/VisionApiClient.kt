package com.pokerhelper.app

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * V2.9.81: 视觉API客户端 - 三重保障：双通道并行+花色保险+手牌锁定
 * flash + vl-plus双通道识别手牌，一致时100%锁定；不一致时标记花色不确定
 * 手牌锁定后只刷新公共牌/底池/D按钮，不重新识别手牌
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

    /**
     * 分析截图 - 返回识别结果
     * @param jpegData JPEG截图数据
     * @return VisionResult? 识别结果，失败返回null
     */
    /**
     * V2.9.81: 双通道交叉验证 - flash + vl-plus
     * 一致→100%可信，锁定；不一致→不锁定，标记花色不确定
     */
    private fun crossValidateHoleCards(flashCards: List<CardInfo>, plusCards: List<CardInfo>): Pair<List<CardInfo>, String> {
        if (flashCards.size != 2 || plusCards.size != 2) {
            return Pair(plusCards, "数量异常")
        }
        val isConsistent = flashCards.joinToString(",") { "${it.rank}${it.suit}" } ==
                             plusCards.joinToString(",") { "${it.rank}${it.suit}" }
        return if (isConsistent) {
            Pair(flashCards, "双通道一致✅")
        } else {
            Log.w(TAG, "双通道冲突: flash=${flashCards.joinToString()} vs vl-plus=${plusCards.joinToString()}")
            Pair(plusCards, "双通道冲突⚠️")
        }
    }

    fun analyzeScreenshot(jpegData: ByteArray): VisionResult? {
        if (apiKey.isEmpty()) { lastError = "未设置API Key"; return null }

        return try {
            val compressedJpeg = compressImage(jpegData, maxWidth = 1080)
            val base64Image = Base64.encodeToString(compressedJpeg, Base64.NO_WRAP)
            val dataUri = "data:image/jpeg;base64,$base64Image"
            Log.d(TAG, "Image compressed: ${jpegData.size / 1024}KB -> ${compressedJpeg.size / 1024}KB")

            // V2.9.81: 三路并行——flash + vl-plus + D按钮
            val flashResultHolder = arrayOf<VisionResult?>(null)
            val plusResultHolder = arrayOf<VisionResult?>(null)
            val dButtonHolder = arrayOf<String?>(null)
            val flashErrorHolder = arrayOf<String?>(null)
            val plusErrorHolder = arrayOf<String?>(null)
            val dButtonErrorHolder = arrayOf<String?>(null)

            val flashThread = Thread {
                try {
                    val requestJson = buildRequest(dataUri, model = "qwen3-vl-flash")
                    flashResultHolder[0] = parseResponse(sendRequest(requestJson))
                } catch (e: Exception) { flashErrorHolder[0] = e.message }
            }
            val plusThread = Thread {
                try {
                    val requestJson = buildRequest(dataUri, model = "qwen-vl-plus")
                    plusResultHolder[0] = parseResponse(sendRequest(requestJson))
                } catch (e: Exception) { plusErrorHolder[0] = e.message }
            }
            val dButtonThread = Thread {
                try { dButtonHolder[0] = analyzeDButtonPosition(dataUri) }
                catch (e: Exception) { dButtonErrorHolder[0] = e.message }
            }

            flashThread.start(); plusThread.start(); dButtonThread.start()
            flashThread.join(5000); plusThread.join(5000); dButtonThread.join(5000)

            // 至少一个通道成功
            if (flashErrorHolder[0] != null && plusErrorHolder[0] != null) {
                lastError = "双通道API错误: flash=${flashErrorHolder[0]} vl-plus=${plusErrorHolder[0]}"
                return null
            }
            val flashResult = flashResultHolder[0]
            val plusResult = plusResultHolder[0]
            if (flashResult == null && plusResult == null) { lastError = "双通道都返回空"; return null }

            // 单通道失败
            if (flashResult == null) { lockReason = "flash失败，单通道"; suitUncertain = true }
            else if (plusResult == null) { lockReason = "vl-plus失败，单通道"; suitUncertain = true }

            // 双通道交叉验证手牌
            val crossResult = if (flashResult != null && plusResult != null) {
                crossValidateHoleCards(flashResult.holeCards, plusResult.holeCards)
            } else if (flashResult != null) {
                Pair(flashResult.holeCards, "vl-plus失败，用flash")
            } else {
                Pair(plusResult!!.holeCards, "flash失败，用vl-plus")
            }
            val holeCardsToUse: List<CardInfo> = crossResult.first
            val crossReason: String = crossResult.second
            suitUncertain = "双通道冲突⚠️" in crossReason || "单通道" in crossReason
            Log.d(TAG, "双通道验证: $crossReason, holeCards=${holeCardsToUse.joinToString()}")

            // 手牌锁定逻辑
            val currentCardKey = holeCardsToUse.joinToString(",") { "${it.rank}${it.suit}" }
            val lastCardKey = holeCardsLocked?.joinToString(",") { "${it.rank}${it.suit}" } ?: ""

            // 新一手牌→重置
            if (lastCardKey.isNotEmpty() && currentCardKey != lastCardKey) {
                Log.d(TAG, "手牌锁定: 新一手牌($lastCardKey→$currentCardKey)，重置")
                holeCardsLocked = null; dButtonLocked = ""
            }

            // 已锁定→用锁定值
            if (holeCardsLocked != null) {
                val lockedKey = holeCardsLocked!!.joinToString(",") { "${it.rank}${it.suit}" }
                if (currentCardKey == lockedKey) {
                    Log.d(TAG, "手牌锁定: 已锁定=${holeCardsLocked!!.joinToString()}")
                    lockReason = "已锁定，跳过重识"; suitUncertain = false
                    val srcResult = plusResult ?: flashResult!!
                    var result = srcResult.copy(holeCards = holeCardsLocked!!)
                    val dPosInsured = applyDButtonInsurance(dButtonHolder[0] ?: "", holeCardsLocked!!)
                    dButtonPosition = dPosInsured
                    result = result.copy(dButtonPosition = dPosInsured)
                    lastResult = result; lastResultTime = System.currentTimeMillis()
                    var corrected = applyStreetCorrection(result)
                    corrected = applyValidationCorrections(corrected); lastResult = corrected
                    Log.d(TAG, "识别成功(锁定): ${corrected.holeCards.joinToString()} | ${corrected.communityCards.joinToString()} | 底池${corrected.potSize} | D=$dPosInsured | 花色OK")
                    return corrected
                }
            }

            // 未锁定→决定是否锁定
            if ("双通道一致✅" in crossReason) {
                holeCardsLocked = holeCardsToUse; suitUncertain = false; lockReason = "双通道一致锁定✅"
                Log.d(TAG, "手牌锁定: 双通道一致，锁定=${holeCardsLocked!!.joinToString()}")
            } else {
                holeCardsLocked = null; lockReason = crossReason
                Log.d(TAG, "手牌锁定: $crossReason，不锁定")
            }

            val baseResult = plusResult ?: flashResult!!
            var result = baseResult.copy(holeCards = holeCardsToUse)
            val dPosRaw = dButtonHolder[0] ?: ""
            val dPosInsured = applyDButtonInsurance(dPosRaw, result.holeCards)
            dButtonPosition = dPosInsured
            if (dButtonErrorHolder[0] != null) Log.w(TAG, "D按钮失败(不影响主流程): ${dButtonErrorHolder[0]}")
            Log.d(TAG, "D按钮: $dPosRaw→$dPosInsured")

            var correctedResult = result.copy(dButtonPosition = dPosInsured)
            lastResult = correctedResult; lastResultTime = System.currentTimeMillis(); lastError = ""
            correctedResult = applyStreetCorrection(correctedResult)
            correctedResult = applyValidationCorrections(correctedResult); lastResult = correctedResult
            Log.d(TAG, "识别成功: ${correctedResult.holeCards.joinToString()} | ${correctedResult.communityCards.joinToString()} | 底池${correctedResult.potSize} | ${correctedResult.totalPlayers}桌 | D=$dPosInsured | ${if(suitUncertain)"⚠️花色不确定" else "花色OK"}")
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
        // 规则3: 手牌变了→新一手牌，重置锁定
        val cardKey = currentCards.joinToString(",") { "${it.rank}${it.suit}" }
        val lastCardKey = lastResult?.holeCards?.joinToString(",") { "${it.rank}${it.suit}" } ?: ""
        if (cardKey != lastCardKey && lastCardKey.isNotEmpty()) {
            dButtonLocked = ""
            Log.d(TAG, "D按钮保险: 新一手牌(${lastCardKey}→${cardKey})，重置锁定")
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


    private fun analyzeDButtonPosition(base64Image: String): String? {
        // 只有dashscope才调用vl-plus（同一个API平台，同一个key）
        if (apiProvider != "dashscope" || apiKey.isEmpty()) {
            Log.d(TAG, "D按钮识别跳过: 非dashscope平台或无API Key")
            return null
        }

        val prompt = """Look at this poker table screenshot. There is a small YELLOW circle with the letter "D" on it - this is the dealer button. It sits near one of the player seats around the table.

The table has 6 possible seat positions arranged in an ellipse:
- bottom-center: YOUR seat (always has your face-up cards below)
- left-bottom: seat between you and the top, on the LEFT side
- left-top: seat on the LEFT side near the top
- top-center: seat directly across from you at the top
- right-top: seat on the RIGHT side near the top
- right-bottom: seat between you and the top, on the RIGHT side

Which position has the "D" dealer button? Answer with ONLY one of these exact words: bottom-center, left-bottom, left-top, top-center, right-top, right-bottom, or not_found"""

        try {
            val requestJson = JSONObject().apply {
                put("model", "qwen-vl-plus")
                put("max_tokens", 50)
                put("temperature", 0.0)
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

            val response = sendRequest(requestJson.toString())
            val responseJson = JSONObject(response)
            val content = responseJson
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
                .lowercase()

            // 验证返回值是否合法
            val validPositions = setOf("bottom-center", "left-bottom", "left-top", "top-center", "right-top", "right-bottom", "not_found")
            return if (content in validPositions) {
                Log.d(TAG, "D按钮识别成功: $content")
                content
            } else {
                // 尝试模糊匹配
                val matched = validPositions.find { content.contains(it) }
                if (matched != null) {
                    Log.d(TAG, "D按钮识别(模糊匹配): $content → $matched")
                    matched
                } else {
                    Log.w(TAG, "D按钮识别返回无效值: $content")
                    "not_found"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "D按钮位置识别异常: ${e.message}")
            return null
        }
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
        val prompt = """你是德州扑克截图识别专家。按以下步骤识别：

【步骤1】先找到2个关键位置（从上到下）：
  ① 顶部白色数字框 → 这是底池(pot_size)
  ② 左下角你头像下方的数字 → 这是你的筹码(my_chips)
  判断依据：底池数字在屏幕上方1/3区域的白色框里；你的筹码在左下角头像旁边

【步骤2】底池(pot_size)识别——最关键，错误会导致策略全错：
  来源优先级：牌桌中央"底池XXX"标签 > 顶部白色数字框
  - 两处数字应该一致，取较大值
  - 去掉逗号："5,750"→5750
  - K/M转换："10K"→10000
  ❌ pot_size不是左下角你头像下的数字（那是my_chips）
  ❌ pot_size不是其他玩家头像旁的数字
  ❌ pot_size不是玩家头像旁边下注筹码堆上的数字
  ❌ pot_size不要自己算（不要把各玩家下注加起来）

【步骤3】你的筹码(my_chips)识别：
  - 只看左下角你自己头像名字下方的数字
  - ❌ my_chips不是顶部白色数字框（那是底池）

【步骤4】识别其余信息：
  1. 手牌：屏幕最下方2张正面牌
  2. 公共牌：桌面中央0/3/4/5张牌
  3. 操作按钮：底部按钮原样输出
  4. 座位总数、活跃玩家数（面前有牌的，已弃牌不算）
  5. 盲注：桌面标题"200 / 500"→blind_sb=200 blind_bb=500
  6. 前注ante

【步骤5】跟注金额(to_call)识别：
  - "跟注3,194"→to_call=3194
  - "跟注10K"→to_call=10000
  - "让牌"/"过牌"→to_call=0
  - "全押"/"全下"→to_call=my_chips值

★★★ 关键规则 ★★★：
- rank: A K Q J T 9 8 7 6 5 4 3 2（T=10）
- suit: s=黑桃♠ h=红桃♥ d=方块♦ c=梅花♣（⚠️黑桃s是实心箭头形，梅花c是三瓣圆弧形，严禁把黑桃误认为梅花！）
- K/M后缀转换：10K=10000 1.5M=1500000
- street：0张=preflop 3=flop 4=turn 5=river

★★★ 底池vs筹码自检（输出前必做）★★★：
如果pot_size < my_chips 且有多张公共牌，很可能是底池和筹码搞反了！
正确情况：翻后pot_size通常 ≥ my_chips（底池至少是盲注的几倍）

返回JSON：
{"hole_cards":[{"rank":"A","suit":"s"}],"community_cards":[],"buttons":["弃牌","跟注10K","加注"],"my_chips":0,"pot_size":0,"to_call":0,"total_players":6,"active_players":3,"street":"preflop","blind_sb":200,"blind_bb":500,"ante":0}

只返回JSON"""

        val json = JSONObject().apply {
            put("model", model ?: modelName)
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
            dButtonPosition = "",  // V2.9.78: 由并行vl-plus调用填充，此处为空默认值
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
