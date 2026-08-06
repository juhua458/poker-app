package com.pokerhelper.app

import android.util.Base64
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * V2.9.108: 视觉API客户端 - 单行格式提速+双prompt保险
 * 核心改动：
 * 1. 新增useCompactPrompt开关——true=单行紧凑格式(快26%), false=原格式(兜底)
 * 2. 单行格式生成更少token(~150 vs ~210)，实测2.0s vs 2.8s
 * 3. parseResponse自动适配两种JSON格式，无需手动切换
 * 4. 单行格式解析失败时自动fallback用原格式重试
 * 5. 保留所有已有保险：手牌锁定、D按钮保险、street纠错、校验纠错
 */
object VisionApiClient {

    private const val TAG = "VisionAPI"
    
    // V2.9.183: OkHttp连接池复用——避免每次TCP握手浪费100-200ms
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(5, 2, TimeUnit.MINUTES))
            .build()
    }
    
    // V2.9.108: prompt格式开关——true=单行紧凑(快), false=原格式(稳)
    var useCompactPrompt = true
    
    var apiProvider = "openai"
    var apiKey = ""
    var apiUrl = "https://api.openai.com/v1/chat/completions"
    var modelName = "gpt-4o-mini"
    var lastError = ""
    // V2.9.193: 保存API原始响应——用于诊断识别失败根因
    var lastRawResponse = ""
    var lastResult: VisionResult? = null
        private set
    var lastResultTime: Long = 0
        private set

    var dButtonPosition: String = ""
    var dButtonLocked: String = ""
        private set

    var holeCardsLocked: List<CardInfo>? = null
    var streetLocked: String? = null  // V2.9.165: 本地CV根据公共牌数量锁定的street
    var suitUncertain: Boolean = false
    var lockReason: String = ""
    // V2.9.197: 混合方案 — 仅锁定rank（本地CV高置信度），suit仍由API识别
    var holeCardsRankLocked: List<String>? = null

    // V2.9.108: 统计信息
    var compactSuccessCount = 0
        private set
    var compactFailCount = 0
        private set
    var fallbackSuccessCount = 0
        private set
    var lastPromptMode = ""
        private set

    data class VisionResult(
        val isPokerTable: Boolean,
        val holeCards: List<CardInfo>,
        val communityCards: List<CardInfo>,
        val potSize: Int,
        val playerChips: Int,
        val totalPlayers: Int,
        val activePlayers: Int,
        val myPosition: String,
        val street: String,
        val toCall: Int,
        val minRaise: Int,
        val buttons: List<String>,
        val blindSB: Int,
        val blindBB: Int,
        val ante: Int,
        val players: List<PlayerInfo>,
        val dButtonPosition: String,
        val rawResponse: String,
        // V2.9.143: 摊牌检测——对手亮出的牌
        val showdownCards: List<ShowdownInfo>,
        val oppHud: List<OppHudInfo>,
        // V2.9.180: 按钮坐标（用于全自动执行）
        val buttonPositions: List<ButtonPosition>,
        // V2.9.206: 搓牌检测——花色不确定时可能正在squeeze
        val suitUncertain: Boolean = false,
        // V2.9.200: GG扑克特有字段
        val isStraddle: Boolean = false,           // 是否Straddle阶段
        val isBombPot: Boolean = false,            // 是否Bomb Pot
        val isInsurance: Boolean = false,          // 是否出现Insurance/Cashout按钮
        val isPKO: Boolean = false                 // 是否PKO赏金赛
    )

    data class CardInfo(val rank: String, val suit: String)
    data class PlayerInfo(val position: String, val bet: Int, val chips: Int, val active: Boolean, val nickname: String = "")
    // V2.9.143: 摊牌信息——对手亮牌+输赢
    data class ShowdownInfo(val seat: Int, val cards: List<CardInfo>, val won: Boolean)
    // V2.9.153: Smart HUD
    data class OppHudInfo(val seat: Int, val vpip: Int, val pfr: Int, val ats: Int, val threeBet: Int)
    // V2.9.180: 按钮坐标
    data class ButtonPosition(val text: String, val xPct: Double, val yPct: Double)

    fun analyzeScreenshot(jpegData: ByteArray): VisionResult? {
        if (apiKey.isEmpty()) { lastError = "未设置API Key"; return null }
        return try {
            val t0 = System.currentTimeMillis()
            val compressedJpeg = compressImage(jpegData, maxWidth = 960)
            val t1 = System.currentTimeMillis()
            val base64Image = Base64.encodeToString(compressedJpeg, Base64.NO_WRAP)
            val t2 = System.currentTimeMillis()
            val dataUri = "data:image/jpeg;base64,$base64Image"
            Log.d(TAG, "⏱ Image: ${jpegData.size/1024}KB→${compressedJpeg.size/1024}KB compress=${t1-t0}ms encode=${t2-t1}ms")

            // V2.9.156: 统一用新分层prompt，不再区分compact/legacy
            var result: VisionResult? = null
            var rawResponse: String? = null
            try {
                val requestJson = buildRequest(dataUri, compact = true)
                val tApi0 = System.currentTimeMillis()
                rawResponse = sendRequest(requestJson)
                lastRawResponse = rawResponse  // V2.9.193: 保存原始响应用于诊断
                result = parseResponse(rawResponse)
                val tApi1 = System.currentTimeMillis()
                if (result != null) {
                    compactSuccessCount++; lastPromptMode = "v156_schema"
                    Log.d(TAG, "⏱ v156 API: ${tApi1-tApi0}ms 成功(${compactSuccessCount}/${compactSuccessCount+compactFailCount})")
                }
            } catch (e: Exception) { 
                Log.w(TAG, "v156异常: ${e.message}")
                lastRawResponse = "EXCEPTION: ${e.message}"  // V2.9.193: 记录异常
            }
            
            if (result == null) {
                compactFailCount++
                Log.e(TAG, "★ API解析失败, 原始响应前300字符: ${rawResponse?.take(300) ?: "null"}")
                Log.d(TAG, "v156失败(${compactFailCount}次)")
                lastError = "API错误: 识别失败"; return null
            }
            val t3 = System.currentTimeMillis()
            Log.d(TAG, "⏱ 全链路: compress=${t1-t0}ms encode=${t2-t1}ms api+parse=${t3-t2}ms total=${t3-t0}ms")
            if (result == null) { lastError = "API返回空结果"; return null }

            val currentRankKey = result.holeCards.joinToString(",") { it.rank }
            val lastRankKey = holeCardsLocked?.joinToString(",") { it.rank }
                ?: holeCardsRankLocked?.joinToString(",") ?: ""
            if (lastRankKey.isNotEmpty() && currentRankKey != lastRankKey) {
                Log.d(TAG, "手牌锁定: 新一手牌(rank: $lastRankKey→$currentRankKey)，重置")
                holeCardsLocked = null; holeCardsRankLocked = null; dButtonLocked = ""; streetLocked = null
            }
            // V2.9.197: 混合方案 — 本地CV锁定rank + API补充suit
            // 当本地CV高置信度识别手牌rank时，rank锁定，suit由API提供
            if (holeCardsRankLocked != null && holeCardsRankLocked!!.size == 2 && result.holeCards.size == 2) {
                val mergedHoleCards = result.holeCards.mapIndexed { idx, apiCard ->
                    CardInfo(holeCardsRankLocked!![idx], apiCard.suit)
                }
                lockReason = "混合锁定(本地rank+API suit)"
                suitUncertain = false
                var mergedResult = result.copy(holeCards = mergedHoleCards)
                val dPosInsured = applyDButtonInsurance(mergedResult.dButtonPosition, mergedHoleCards)
                dButtonPosition = dPosInsured; mergedResult = mergedResult.copy(dButtonPosition = dPosInsured)
                lastResult = mergedResult; lastResultTime = System.currentTimeMillis(); lastError = ""
                var corrected = applyStreetCorrection(mergedResult)
                corrected = applyValidationCorrections(corrected); lastResult = corrected
                Log.d(TAG, "识别成功(混合rank锁定): hand=${mergedHoleCards.map{"${it.rank}${it.suit}"}} | comm=${corrected.communityCards.map{it.rank}.joinToString()} | 底池${corrected.potSize} | D=$dPosInsured")
                return corrected
            }
            // V2.9.114: 空手牌不应被锁定——如果之前锁定了空列表，必须重置
            // V2.9.134: 保留suit（vision已识别花色），不再抹掉
            if (holeCardsLocked != null && holeCardsLocked!!.isNotEmpty()) {
                lockReason = "已锁定，跳过重识"; suitUncertain = false
                var lockedResult = result.copy(holeCards = holeCardsLocked!!, communityCards = result.communityCards)
                val dPosInsured = applyDButtonInsurance(lockedResult.dButtonPosition, holeCardsLocked!!)
                dButtonPosition = dPosInsured; lockedResult = lockedResult.copy(dButtonPosition = dPosInsured)
                lastResult = lockedResult; lastResultTime = System.currentTimeMillis()
                var corrected = applyStreetCorrection(lockedResult)
                corrected = applyValidationCorrections(corrected); lastResult = corrected
                Log.d(TAG, "识别成功(锁定,$lastPromptMode): ${corrected.holeCards.map{it.rank}.joinToString()} | comm=${corrected.communityCards.map{it.rank}.joinToString()} | 底池${corrected.potSize} | D=$dPosInsured")
                return corrected
            }
            // V2.9.114: 只锁定非空手牌，防止空列表锁死
            // V2.9.134: 保留suit（vision已识别花色），不再抹掉
            if (result.holeCards.isNotEmpty()) {
                holeCardsLocked = result.holeCards; lockReason = "首次识别锁定"; suitUncertain = false
            } else {
                holeCardsLocked = null; lockReason = "手牌为空不锁定"; suitUncertain = false
            }
            var correctedResult = result.copy(holeCards = result.holeCards, communityCards = result.communityCards)
            val dPosInsured = applyDButtonInsurance(correctedResult.dButtonPosition, correctedResult.holeCards)
            dButtonPosition = dPosInsured; correctedResult = correctedResult.copy(dButtonPosition = dPosInsured)
            lastResult = correctedResult; lastResultTime = System.currentTimeMillis(); lastError = ""
            correctedResult = applyStreetCorrection(correctedResult)
            correctedResult = applyValidationCorrections(correctedResult); lastResult = correctedResult
            Log.d(TAG, "识别成功($lastPromptMode): ${correctedResult.holeCards.joinToString()} | comm=${correctedResult.communityCards.map{it.rank}.joinToString()} | 底池${correctedResult.potSize} | ${correctedResult.totalPlayers}桌 | D=$dPosInsured")
            correctedResult
        } catch (e: Exception) { lastError = "API错误: ${e.message}"; Log.e(TAG, "analyzeScreenshot failed", e); null }
    }

    private fun applyStreetCorrection(result: VisionResult): VisionResult {
        // V2.9.165: 优先使用本地CV锁定的street（最可靠）
        if (streetLocked != null && result.street.lowercase() != streetLocked!!.lowercase()) {
            Log.w(TAG, "street锁定覆盖: ${result.street}→${streetLocked!!} (本地CV)")
            return result.copy(street = streetLocked!!)
        }
        // 兜底：根据VLM返回的公共牌数量纠正street
        val commCount = result.communityCards.size
        val correctStreet = when { commCount == 0 -> "preflop"; commCount == 3 -> "flop"; commCount == 4 -> "turn"; commCount == 5 -> "river"; else -> null }
        return if (correctStreet != null && result.street.lowercase() != correctStreet) { Log.w(TAG, "street纠正: ${result.street}→$correctStreet"); result.copy(street = correctStreet) } else result
    }

    private fun applyDButtonInsurance(rawPos: String, currentCards: List<CardInfo>): String {
        val rankKey = currentCards.joinToString(",") { it.rank }
        val lastRankKey = lastResult?.holeCards?.joinToString(",") { it.rank } ?: ""
        if (rankKey != lastRankKey && lastRankKey.isNotEmpty()) { dButtonLocked = ""; Log.d(TAG, "D按钮保险: 新一手牌，重置锁定") }
        if (rawPos.isEmpty() || rawPos == "not_found") { if (dButtonLocked.isNotEmpty()) return dButtonLocked; return rawPos }
        if (dButtonLocked.isEmpty()) { dButtonLocked = rawPos; return rawPos }
        if (rawPos == dButtonLocked) return rawPos
        if (isNeighborPosition(rawPos, dButtonLocked)) return dButtonLocked
        Log.w(TAG, "D按钮保险: 突变(${dButtonLocked}→${rawPos})，保留锁定值"); return dButtonLocked
    }

    private fun isNeighborPosition(pos1: String, pos2: String): Boolean {
        if (pos1.isEmpty() || pos2.isEmpty()) return false
        val side = { p: String -> when { p.contains("left") -> "left"; p.contains("right") -> "right"; p.contains("top-center") -> "top"; p.contains("bottom-center") -> "bottom"; else -> p } }
        return side(pos1) == side(pos2)
    }

    // V2.9.156: 1080px/Q80 + 底部裁切 + 对比度增强
    private fun compressImage(jpegData: ByteArray, maxWidth: Int): ByteArray {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size) ?: return jpegData
        // 裁切顶部2%（标题栏）和底部8%（系统栏）
        val cropTop = (bitmap.height * 0.02).toInt()
        val cropBottom = (bitmap.height * 0.92).toInt()
        val cropLeft = (bitmap.width * 0.02).toInt()
        val cropRight = (bitmap.width * 0.98).toInt()
        val cropped = try {
            android.graphics.Bitmap.createBitmap(bitmap, cropLeft, cropTop,
                cropRight - cropLeft, cropBottom - cropTop)
        } catch (_: Exception) {
            // fallback: 只裁顶部
            try { android.graphics.Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.width, bitmap.height - cropTop) } catch (_: Exception) { bitmap }
        }
        if (cropped !== bitmap) bitmap.recycle()
        // 分辨率提升: 960→1080
        val targetWidth = 1080
        val scale = if (cropped.width > targetWidth) targetWidth.toFloat() / cropped.width else 1f
        val scaled = if (scale < 1f) {
            val s = android.graphics.Bitmap.createScaledBitmap(cropped, (cropped.width * scale).toInt(), (cropped.height * scale).toInt(), true)
            cropped.recycle(); s
        } else cropped
        // V2.9.156: 对比度增强(1.2x)帮助花色识别
        val enhanced = enhanceContrast(scaled, 1.2f)
        if (enhanced !== scaled) scaled.recycle()
        val stream = ByteArrayOutputStream()
        enhanced.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, stream); enhanced.recycle()
        return stream.toByteArray()
    }

    // V2.9.156: 对比度增强——帮助VLM区分♠♣和♥♦
    private fun enhanceContrast(bmp: android.graphics.Bitmap, factor: Float): android.graphics.Bitmap {
        return try {
            val matrix = android.graphics.ColorMatrix(floatArrayOf(
                factor, 0f, 0f, 0f, (1f - factor) * 128f,
                0f, factor, 0f, 0f, (1f - factor) * 128f,
                0f, 0f, factor, 0f, (1f - factor) * 128f,
                0f, 0f, 0f, 1f, 0f
            ))
            val result = android.graphics.Bitmap.createBitmap(bmp.width, bmp.height, bmp.config ?: android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(result)
            val paint = android.graphics.Paint()
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
            canvas.drawBitmap(bmp, 0f, 0f, paint)
            result
        } catch (_: Exception) { bmp }
    }

    /**
     * V2.9.200: 根据当前平台生成Prompt差异化描述
     * 返回 Pair(平台前缀描述, 按钮描述文本)
     */
    private fun buildPlatformPromptHint(): Pair<String, String> {
        return when (GameModeConfig.currentPlatform) {
            GamePlatform.GGPOKER -> Pair(
                "GG扑克(GGPoker)。特征:深蓝/深绿色桌面,竖屏布局,按钮可能是英文(Fold/Check/Call/Raise/All In)或中文,行动时按钮放大10%,可能有Straddle/Bomb Pot/Insurance/PKO等特殊模式。",
                "Fold/Check/Call含金额/Raise含金额/All In,可能含预设加注额(1/2Pot,2/3Pot,Pot),中英文都可能"
            )
            GamePlatform.SHORT_DECK -> Pair(
                "短牌扑克(6+)。特征:去掉2-5,只有36张牌(A-6),牌面显示与常规一致。",
                "弃牌/让牌/跟注含金额/加注含金额比例/下注/全押"
            )
            GamePlatform.STANDARD -> Pair(
                "标准德州扑克。",
                "弃牌/让牌/跟注含金额/加注含金额比例/下注/全押"
            )
        }
    }

    // V2.9.156: 分层Prompt+Schema+Few-Shot+JSON Mode+temperature=0
    private fun buildRequest(base64Image: String, model: String? = null, compact: Boolean = true): String {
        val streetHint = streetLocked?.let { "\n【已知street】当前street已确认为${it}，phase字段直接填${it}，buttons识别必须与此street的场景一致。\n" } ?: ""
        // V2.9.197: 混合方案 — 传递rank锁定提示给API，让API专注suit识别
        val rankHint = holeCardsRankLocked?.let {
            if (it.size == 2) "\n【手牌rank已锁定】手牌点数已确认为[${it[0]}, ${it[1]}]，hole_cards的rank字段必须填这两个值，你只需识别suit（花色）。\n" else ""
        } ?: ""
        // V2.9.196: 精简prompt减少input token加速推理
        // V2.9.200: 根据当前平台动态调整prompt描述（GG/标准/短牌）
        val platformHint = buildPlatformPromptHint()
        val prompt = """${platformHint.first}5-max识别引擎。只输出JSON。
Schema(缺填null):{"is_poker_table":bool,"hole_cards":[{"rank":"A","suit":"s"}],"community_cards":[],"pot":数字,"my_chips":数字,"bet_to_call":数字,"dealer_seat":1-5,"my_seat":1-5,"blinds":"100/200","phase":"preflop","opp_seats":[{"seat":2,"nickname":"P1","chips":"3000","action":"fold"}],"buttons":["弃牌","跟注500"],"button_positions":[{"text":"弃牌","xPct":0.17,"yPct":0.88}],"d_button_pos":"left-top","total_players":5,"active_players":3,"showdown_cards":[],"opp_hud":[],"is_straddle":false,"is_bomb_pot":false,"is_insurance":false,"is_pko":false}
花色:s=♠黑 h=♥红心 d=♦方块 c=♣梅花。对子花色须不同。
pot展开简写:1.2K=1200,1.5M=1500000。底池=桌面中央筹码堆。
active_players=仅有牌(明/暗)的玩家,弃牌/空座不计。
buttons=底部全部按钮(${platformHint.second}),不可遗漏!
button_positions=每按钮{text与buttons一致,xPct=中心X/屏宽,yPct=中心Y/屏高},加注可能横排多坐标。
opp_seats须含nickname(头像旁用户名)。showdown_cards=摊牌对手牌,看不到填[]。opp_hud=对手统计,看不到填[]。
GG特有字段:is_straddle=是否Straddle(第三盲注);is_bomb_pot=是否BombPot(所有玩家ante后直接翻牌);is_insurance=是否出现Insurance/EV Cashout按钮;is_pko=是否PKO赏金赛(牌桌有赏金标识)。
示例:{"is_poker_table":true,"hole_cards":[{"rank":"A","suit":"s"},{"rank":"K","suit":"h"}],"community_cards":[{"rank":"Q","suit":"d"}],"pot":1500,"my_chips":25000,"bet_to_call":0,"dealer_seat":3,"my_seat":1,"blinds":"100/200","phase":"flop","opp_seats":[{"seat":2,"nickname":"King","chips":"18000","action":"check"}],"buttons":["让牌","下注500"],"button_positions":[{"text":"让牌","xPct":0.50,"yPct":0.88},{"text":"下注500","xPct":0.83,"yPct":0.88}],"d_button_pos":"left-top","total_players":5,"active_players":3,"showdown_cards":[],"opp_hud":[],"is_straddle":false,"is_bomb_pot":false,"is_insurance":false,"is_pko":false}
${streetHint}${rankHint}识别:"""

        return JSONObject().apply {
            put("model", model ?: modelName)
            put("max_tokens", 800)  // V2.9.196: 1500→800减少输出等待
            put("temperature", 0.0)  // V2.9.156: 确定性输出
            // V2.9.164: DeepSeek vision模型不支持JSON Mode，跳过
            if (!modelName.contains("deepseek", ignoreCase = true)) {
                put("response_format", JSONObject().put("type", "json_object"))
            }
            put("messages", JSONArray().apply { put(JSONObject().apply {
                put("role", "user"); put("content", JSONArray().apply {
                    put(JSONObject().apply { put("type", "text"); put("text", prompt) })
                    put(JSONObject().apply { put("type", "image_url"); put("image_url", JSONObject().apply { put("url", base64Image); put("detail", "low") }) })  // V2.9.196: high→low提速
                })
            }) })
        }.toString()
    }

    private fun sendRequest(requestJson: String): String {
        var lastException: Exception? = null
        // V2.9.184: 网络波动重试1次，间隔500ms
        repeat(2) { attempt ->
            try {
                val body = requestJson.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(apiUrl)
                    .post(body)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .build()
                val response = httpClient.newCall(request).execute()
                return if (response.isSuccessful) {
                    response.body?.string() ?: throw Exception("Empty response body")
                } else {
                    val errBody = response.body?.string() ?: ""
                    if (attempt == 0 && response.code >= 500) {
                        Log.w(TAG, "HTTP ${response.code}, retrying...")
                        lastException = Exception("HTTP ${response.code}: $errBody")
                        Thread.sleep(500)
                        return@repeat
                    }
                    throw Exception("HTTP ${response.code}: $errBody")
                }
            } catch (e: Exception) {
                lastException = e
                if (attempt == 0) {
                    Log.w(TAG, "API request failed, retrying: ${e.message}")
                    Thread.sleep(500)
                }
            }
        }
        throw lastException ?: Exception("Unknown error")
    }

    private fun parseResponse(responseBody: String): VisionResult? {
        val content = JSONObject(responseBody).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        val jsonStr = extractJson(content) ?: return null
        val data = JSONObject(jsonStr)
        val isCompact = data.has("phase") || data.has("bet_to_call") || data.has("blinds")
        val buttonsJson = data.optJSONArray("buttons")
        val buttons = if (buttonsJson != null) {
            (0 until buttonsJson.length()).map { buttonsJson.getString(it) }
        } else emptyList()
        // V2.9.180: 解析按钮坐标
        val btnPosJson = data.optJSONArray("button_positions")
        val buttonPositions = if (btnPosJson != null) {
            (0 until btnPosJson.length()).mapNotNull { i ->
                try {
                    val obj = btnPosJson.getJSONObject(i)
                    ButtonPosition(obj.getString("text"), obj.getDouble("x_pct"), obj.getDouble("y_pct"))
                } catch (_: Exception) { null }
            }
        } else emptyList()
        val players = if (isCompact) parseOppSeats(data.optJSONArray("opp_seats")) else parseLegacyPlayers(data.optJSONArray("players"))
        val callFromButtons = parseCallAmountFromButtons(buttons)
        val finalToCall = if (callFromButtons >= 0) callFromButtons else if (isCompact) parseChipValue(data, "bet_to_call") else data.optInt("to_call", 0)
        val (blindSB, blindBB) = if (isCompact) parseBlindsString(data.optString("blinds", "")) else Pair(parseChipValue(data, "blind_sb"), parseChipValue(data, "blind_bb"))
        val street = if (isCompact) data.optString("phase", "preflop") else data.optString("street", "preflop")
        val potSize = if (isCompact) parseChipValue(data, "pot") else parsePotSize(data, "pot_size")
        val insuredPot = if (potSize == 0 && data.has("pot_size")) { val v = parsePotSize(data, "pot_size"); if (v > 0) v else potSize } else potSize
        val isPokerTable = data.optBoolean("is_poker_table", true) // V2.9.111: 默认true兼容旧格式
        // V2.9.143: 解析摊牌信息
        val showdownCards = parseShowdownCards(data.optJSONArray("showdown_cards"))
        val oppHud = parseOppHud(data.optJSONArray("opp_hud"))
// V2.9.200: GG特有字段
        val isStraddle = data.optBoolean("is_straddle", false)
        val isBombPot = data.optBoolean("is_bomb_pot", false)
        val isInsurance = data.optBoolean("is_insurance", false)
        val isPKO = data.optBoolean("is_pko", false)
return VisionResult(isPokerTable, parseCards(data.optJSONArray("hole_cards")), parseCards(data.optJSONArray("community_cards")), insuredPot, parseChipValue(data, "my_chips"), data.optInt("total_players", 6), data.optInt("active_players", 2), data.optString("my_position", ""), street, finalToCall, data.optInt("min_raise", 0), buttons, blindSB, blindBB, parseChipValue(data, "ante"), players, data.optString("d_button_pos", ""), content, showdownCards, oppHud, buttonPositions, suitUncertain, isStraddle, isBombPot, isInsurance, isPKO)
    }

    private fun parseOppSeats(arr: JSONArray?): List<PlayerInfo> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            try { val o = arr.optJSONObject(i) ?: return@mapNotNull null; val s = o.optInt("seat", 0); val c = parseChipValue(o, "chips"); val a = o.optString("action", ""); val nick = o.optString("nickname", ""); if (s > 0) PlayerInfo(seatToPosition(s), if (a == "raise" || a == "call" || a == "allin") c else 0, c, a != "fold", nick) else null } catch (_: Exception) { null }
        }
    }
    private fun seatToPosition(s: Int) = when(s) { 1->"bottom"; 2->"left-bottom"; 3->"left-top"; 4->"top-center"; 5->"right-top"; 6->"right-bottom"; else->"seat_$s" }
    // V2.9.143: 解析摊牌信息——对手亮出的牌
    private fun parseShowdownCards(arr: JSONArray?): List<ShowdownInfo> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            try {
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val seat = o.optInt("seat", 0)
                val cards = parseCards(o.optJSONArray("cards"))
                val won = o.optBoolean("won", false)
                if (seat > 0 && cards.isNotEmpty()) ShowdownInfo(seat, cards, won) else null
            } catch (_: Exception) { null }
        }
    }
    // V2.9.153
    private fun parseOppHud(arr: JSONArray?): List<OppHudInfo> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            try { val o = arr.optJSONObject(i) ?: return@mapNotNull null; val s = o.optInt("seat", 0); val v = o.optInt("vpip", 0); val p = o.optInt("pfr", 0); if (s > 0 && (v > 0 || p > 0)) OppHudInfo(s, v, p, o.optInt("ats", 0), o.optInt("three_bet", 0)) else null } catch (_: Exception) { null }
        }
    }
    private fun parseLegacyPlayers(arr: JSONArray?): List<PlayerInfo> {
        if (arr == null) return emptyList()
        return try { (0 until arr.length()).mapNotNull { i -> val o = arr.optJSONObject(i) ?: return@mapNotNull null; val p = o.optString("position", ""); if (p.isNotEmpty()) PlayerInfo(p, o.optInt("bet", 0), o.optInt("chips", 0), o.optBoolean("active", true)) else null } } catch (_: Exception) { emptyList() }
    }
    private fun parseBlindsString(blinds: String): Pair<Int, Int> = try { val p = blinds.split("/"); if (p.size == 2) Pair(parseChipString(p[0].trim()), parseChipString(p[1].trim())) else Pair(0, 0) } catch (_: Exception) { Pair(0, 0) }

    // V2.9.194: extractJson 重写——逐个尝试每个{位置+详细日志
    private fun extractJson(text: String): String? {
        Log.d(TAG, "extractJson: len=${text.length}, head=${text.take(80).replace("\n","\\n")}")
        // 1. 整体解析
        try { JSONObject(text); Log.d(TAG, "extractJson: step1 whole-text OK"); return text } catch (_: Exception) {}
        // 2. markdown 代码块提取
        val m = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```").find(text)
        if (m != null) try { val v = m.groupValues[1].trim(); JSONObject(v); Log.d(TAG, "extractJson: step2 markdown OK"); return v } catch (_: Exception) {}
        // 3. V2.9.194: 逐个{位置尝试括号匹配——不轻易放弃
        var startIdx = text.indexOf('{')
        while (startIdx >= 0) {
            var depth = 0
            var inString = false
            var escaped = false
            var matched = false
            for (i in startIdx until text.length) {
                val c = text[i]
                when {
                    escaped -> escaped = false
                    c == '\\' && inString -> escaped = true
                    c == '"' -> inString = !inString
                    inString -> {}
                    c == '{' -> depth++
                    c == '}' -> {
                        depth--
                        if (depth == 0) {
                            val candidate = text.substring(startIdx, i + 1)
                            try { JSONObject(candidate); Log.d(TAG, "extractJson: step3 brace-match OK at idx=$startIdx, len=${candidate.length}"); return candidate } catch (e: Exception) {
                                Log.d(TAG, "extractJson: step3 idx=$startIdx invalid: ${e.message?.take(60)}")
                            }
                            matched = true; break
                        }
                    }
                }
            }
            if (!matched) {
                // 没找到匹配的}，用fallback: 从startIdx到最后一个}
                val lastBrace = text.lastIndexOf('}')
                if (lastBrace > startIdx) {
                    val candidate = text.substring(startIdx, lastBrace + 1)
                    try { JSONObject(candidate); Log.d(TAG, "extractJson: step3 fallback OK at idx=$startIdx"); return candidate } catch (_: Exception) {}
                }
            }
            // 继续找下一个{
            startIdx = text.indexOf('{', startIdx + 1)
        }
        Log.e(TAG, "extractJson: ALL FAILED, text=${text.take(200).replace("\n","\\n")}")
        return null
    }

    private fun parseCards(arr: JSONArray?): List<CardInfo> {
        if (arr == null) return emptyList()
        val valid = setOf("A","K","Q","J","T","9","8","7","6","5","4","3","2"); val norm = mapOf("10" to "T", "1" to "A")
        return (0 until arr.length()).mapNotNull { i -> try {
            val el = arr.get(i)
            when (el) {
                is JSONObject -> { var r = el.optString("rank", ""); r = norm[r] ?: r; val s = el.optString("suit", ""); if (r in valid) CardInfo(r, s) else null }
                is String -> { if (el.length >= 2) { val sc = el.last().lowercaseChar(); val sm = mapOf('s' to "s",'h' to "h",'d' to "d",'c' to "c",'♠' to "s",'♥' to "h",'♦' to "d",'♣' to "c"); val s = sm[sc] ?: ""; var r = el.substring(0, el.length-1).trim().uppercase(); r = norm[r] ?: r; if (r in valid && s.isNotEmpty()) CardInfo(r, s) else null } else null }
                else -> null
            }
        } catch (_: Exception) { null } }
    }

    private fun parseCallAmountFromButtons(buttons: List<String>): Int {
        for (btn in buttons) {
            // V2.9.200: 同时支持中英文按钮（GG扑克英文按钮）
            if (btn.contains("过牌") || btn.contains("让牌") ||
                btn.contains("check", ignoreCase = true)) return 0
            if (btn.contains("跟注") || btn.contains("call", ignoreCase = true)) {
                val n = btn.replace(Regex("(?i)call|跟注"),"").trim().replace(",","")
                return try { if (n.isEmpty()) 0 else if (n.endsWith("K",true)) (n.dropLast(1).toFloat()*1000).toInt() else if (n.endsWith("M",true)) (n.dropLast(1).toFloat()*1000000).toInt() else n.toInt() } catch (_: Exception) { -1 }
            }
            if (btn.contains("全押") || btn.contains("全下") ||
                btn.contains("all in", ignoreCase = true) ||
                btn.contains("all-in", ignoreCase = true)) {
                val n = btn.replace(Regex("(?i)all[\\s-]?in|全押|全下"),"").trim().replace(",","")
                return try { if (n.isEmpty()) -1 else if (n.endsWith("K",true)) (n.dropLast(1).toFloat()*1000).toInt() else if (n.endsWith("M",true)) (n.dropLast(1).toFloat()*1000000).toInt() else n.toInt() } catch (_: Exception) { -1 }
            }
        }
        return -1
    }

    private fun parsePotSize(data: JSONObject, key: String): Int { val r = data.opt(key) ?: return 0; return when(r) { is Int -> r; is Long -> r.toInt(); is Double -> r.toInt(); is String -> parseChipString(r); else -> data.optInt(key, 0) } }
    private fun parseChipValue(data: JSONObject, key: String): Int { val r = data.opt(key) ?: return 0; return when(r) { is Int -> r; is Long -> r.toInt(); is Double -> r.toInt(); is String -> parseChipString(r); else -> data.optInt(key, 0) } }
    private fun parseChipString(s: String): Int { val t = s.trim().replace(",",""); return try { when { t.endsWith("K",true) -> (t.dropLast(1).toFloat()*1000).toInt(); t.endsWith("M",true) -> (t.dropLast(1).toFloat()*1000000).toInt(); t.contains(".") -> t.toFloat().toInt(); else -> t.toInt() } } catch (_: Exception) { 0 } }

    private fun validateResult(result: VisionResult): List<String> {
        val w = mutableListOf<String>(); val vr = setOf("A","K","Q","J","T","9","8","7","6","5","4","3","2")
        for (c in result.holeCards) { if (c.rank !in vr) w.add("无效点数:${c.rank}"); if (c.suit.isNotEmpty() && c.suit !in setOf("s","h","d","c")) w.add("无效花色:${c.suit}") }
        for (c in result.communityCards) { if (c.rank !in vr) w.add("公共牌无效点数:${c.rank}") }
        if (result.holeCards.size != 2) w.add("手牌数${result.holeCards.size}≠2")
        if (result.totalPlayers < 2 || result.totalPlayers > 20) w.add("人数${result.totalPlayers}异常")
        return w
    }

    private fun applyValidationCorrections(result: VisionResult): VisionResult {
        var c = result; val cor = mutableListOf<String>()
        if (c.activePlayers > c.totalPlayers) { c = c.copy(activePlayers = c.totalPlayers); cor.add("active>total") }
        if (c.activePlayers < 2) { c = c.copy(activePlayers = c.totalPlayers); cor.add("active<2") }
        if (c.totalPlayers < 2) { c = c.copy(totalPlayers = 6, activePlayers = 6); cor.add("total<2→6") }
        if (c.totalPlayers > 20) { c = c.copy(totalPlayers = 9, activePlayers = minOf(c.activePlayers, 9)); cor.add("total>20→9") }
        if (c.street.lowercase() in listOf("preflop","pre") && c.activePlayers < c.totalPlayers) { c = c.copy(activePlayers = c.totalPlayers); cor.add("preflop active=total") }
        if (c.potSize < 0) { c = c.copy(potSize = 0); cor.add("pot<0→0") }
        if (c.potSize == 0 && c.communityCards.isNotEmpty()) { val lp = lastResult?.potSize ?: 0; if (lp > 0) { c = c.copy(potSize = lp); cor.add("🔧翻后pot=0→用上轮pot=$lp") } else if (c.blindBB > 0) { val ep = c.blindBB * 3; c = c.copy(potSize = ep); cor.add("🔧翻后pot=0→BB*3=$ep") } }
        if (c.potSize > 0 && c.playerChips > 0 && c.potSize > c.playerChips * 5) { val sw = c.copy(potSize = c.playerChips, playerChips = c.potSize); c = sw; cor.add("🔧pot/chips互换") }
        val bb = if (c.blindBB > 0) c.blindBB else if (c.blindSB > 0) c.blindSB * 2 else 0
        if (bb > 0 && c.potSize > 0 && c.playerChips > 0 && c.potSize < c.playerChips && c.playerChips > bb * 3 && c.communityCards.isNotEmpty()) { c = c.copy(potSize = c.playerChips, playerChips = c.potSize); cor.add("🔧翻后pot/chips互换") }
        if (c.toCall < 0) { c = c.copy(toCall = 0); cor.add("to_call<0→0") }
        if (c.holeCards.size != 2) cor.add("⚠️手牌数${c.holeCards.size}≠2")
        if (cor.isNotEmpty()) { lastError = cor.joinToString("; "); Log.w(TAG, "校验纠错: $lastError") } else Log.d(TAG, "校验纠错: 无需纠正")
        return c
    }

    fun toJson(result: VisionResult): String {
        val warnings = validateResult(result)
        return JSONObject().apply {
            put("hole_cards", JSONArray(result.holeCards.map { JSONObject().apply { put("rank", it.rank); put("suit", it.suit) } }))
            put("community_cards", JSONArray(result.communityCards.map { JSONObject().apply { put("rank", it.rank); put("suit", it.suit) } }))
            put("pot_size", result.potSize); put("my_chips", result.playerChips); put("total_players", result.totalPlayers); put("active_players", result.activePlayers)
            put("my_position", result.myPosition); put("street", result.street); put("to_call", result.toCall); put("min_raise", result.minRaise)
            put("buttons", JSONArray(result.buttons)); put("blind_sb", result.blindSB); put("blind_bb", result.blindBB); put("ante", result.ante)
            put("button_positions", JSONArray(result.buttonPositions.map { JSONObject().apply { put("text", it.text); put("x_pct", it.xPct); put("y_pct", it.yPct) } }))
            put("players", JSONArray(result.players.map { JSONObject().apply { put("position", it.position); put("bet", it.bet); put("chips", it.chips); put("active", it.active); if(it.nickname.isNotEmpty()) put("nickname", it.nickname) } }))
            // V2.9.168: 同时输出opp_seats格式供JS OppProfiler使用
            put("opp_seats", JSONArray(result.players.map { p -> JSONObject().apply {
                val seatNum = when(p.position) { "bottom"->1; "left-bottom"->2; "left-top"->3; "top-center"->4; "right-top"->5; "right-bottom"->6; else->0 }
                put("seat", seatNum)
                put("chips", p.chips.toString())
                put("action", if(p.bet > 0) { if(p.bet > 600) "raise" else "call" } else if(p.active) "" else "fold")
                put("active", p.active)
                if(p.nickname.isNotEmpty()) put("nickname", p.nickname)
            } }))
            put("is_poker_table", result.isPokerTable); put("d_button_position", result.dButtonPosition); put("suit_uncertain", result.suitUncertain); put("hole_cards_locked", holeCardsLocked != null); put("rank_locked", holeCardsRankLocked != null); put("rank_lock_values", holeCardsRankLocked?.joinToString(",") ?: ""); put("lock_reason", lockReason)
            put("prompt_mode", lastPromptMode)
            // V2.9.143: 摊牌信息
            if (result.showdownCards.isNotEmpty()) {
                put("showdown_cards", JSONArray(result.showdownCards.map { JSONObject().apply {
                    put("seat", it.seat)
                    put("cards", JSONArray(it.cards.map { c -> JSONObject().apply { put("rank", c.rank); put("suit", c.suit) } }))
                    put("won", it.won)
                } }))
            }
            if (result.oppHud.isNotEmpty()) { put("opp_hud", JSONArray(result.oppHud.map { JSONObject().apply { put("seat", it.seat); put("vpip", it.vpip); put("pfr", it.pfr); put("ats", it.ats); put("three_bet", it.threeBet) } })) }
            // V2.9.200: GG扑克特有字段
            put("platform", GameModeConfig.currentPlatform.name)
            put("is_straddle", result.isStraddle)
            put("is_bomb_pot", result.isBombPot)
            put("is_insurance", result.isInsurance)
            put("is_pko", result.isPKO)
            if (warnings.isNotEmpty()) put("_warnings", JSONArray(warnings))
        }.toString()
    }

    fun updateConfig(provider: String, key: String) {
        apiProvider = provider; apiKey = key
        when (provider) {
            "openai" -> { apiUrl = "https://api.openai.com/v1/chat/completions"; modelName = "gpt-4o-mini" }
            "dashscope" -> { apiUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"; modelName = "qwen-vl-plus" }
            "deepseek" -> { apiUrl = "https://api.deepseek.com/v1/chat/completions"; modelName = "deepseek-chat-vision" }
            "siliconflow" -> { apiUrl = "https://api.siliconflow.cn/v1/chat/completions"; modelName = "Qwen/Qwen3-VL-8B-Instruct" }
            else -> { Log.w(TAG, "未知供应商: $provider，保持当前配置"); lastError = "未知供应商: $provider" }
        }
    }
}
