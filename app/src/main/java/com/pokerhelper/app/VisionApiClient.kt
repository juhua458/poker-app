package com.pokerhelper.app

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

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
    
    // V2.9.108: prompt格式开关——true=单行紧凑(快), false=原格式(稳)
    var useCompactPrompt = true
    
    var apiProvider = "openai"
    var apiKey = ""
    var apiUrl = "https://api.openai.com/v1/chat/completions"
    var modelName = "gpt-4o-mini"
    var lastError = ""
    var lastResult: VisionResult? = null
        private set
    var lastResultTime: Long = 0
        private set

    var dButtonPosition: String = ""
    var dButtonLocked: String = ""
        private set

    var holeCardsLocked: List<CardInfo>? = null
    var suitUncertain: Boolean = false
    var lockReason: String = ""

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
        val oppHud: List<OppHudInfo>
    )

    data class CardInfo(val rank: String, val suit: String)
    data class PlayerInfo(val position: String, val bet: Int, val chips: Int, val active: Boolean)
    // V2.9.143: 摊牌信息——对手亮牌+输赢
    data class ShowdownInfo(val seat: Int, val cards: List<CardInfo>, val won: Boolean)
    // V2.9.153: Smart HUD
    data class OppHudInfo(val seat: Int, val vpip: Int, val pfr: Int, val ats: Int, val threeBet: Int)

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

            var result: VisionResult? = null
            if (useCompactPrompt) {
                try {
                    val requestJson = buildRequest(dataUri, model = "qwen-vl-plus", compact = true)
                    val tApi0 = System.currentTimeMillis()
                    result = parseResponse(sendRequest(requestJson))
                    val tApi1 = System.currentTimeMillis()
                    if (result != null && result.potSize == 0 && result.playerChips > 0 && result.communityCards.isNotEmpty()) {
                        Log.w(TAG, "紧凑格式pot=0(有公共牌)，尝试原格式重试")
                        result = null
                    }
                    if (result != null) {
                        compactSuccessCount++; lastPromptMode = "compact"
                        Log.d(TAG, "⏱ compact API: ${tApi1-tApi0}ms 成功(${compactSuccessCount}/${compactSuccessCount+compactFailCount})")
                    }
                } catch (e: Exception) { Log.w(TAG, "紧凑格式异常: ${e.message}") }
                
                if (result == null) {
                    compactFailCount++
                    Log.d(TAG, "紧凑格式失败，fallback原格式(${compactFailCount}次)")
                    try {
                        val requestJson = buildRequest(dataUri, model = "qwen-vl-plus", compact = false)
                        val tFb0 = System.currentTimeMillis()
                        result = parseResponse(sendRequest(requestJson))
                        val tFb1 = System.currentTimeMillis()
                        if (result != null) {
                            fallbackSuccessCount++; lastPromptMode = "legacy(fallback)"
                            Log.d(TAG, "⏱ fallback API: ${tFb1-tFb0}ms")
                        }
                    } catch (e: Exception) { lastError = "API错误: ${e.message}"; return null }
                }
            } else {
                try {
                    val requestJson = buildRequest(dataUri, model = "qwen-vl-plus", compact = false)
                    val tApi0 = System.currentTimeMillis()
                    result = parseResponse(sendRequest(requestJson)); lastPromptMode = "legacy"
                    val tApi1 = System.currentTimeMillis()
                    Log.d(TAG, "⏱ legacy API: ${tApi1-tApi0}ms")
                } catch (e: Exception) { lastError = "API错误: ${e.message}"; return null }
            }
            val t3 = System.currentTimeMillis()
            Log.d(TAG, "⏱ 全链路: compress=${t1-t0}ms encode=${t2-t1}ms api+parse=${t3-t2}ms total=${t3-t0}ms")
            if (result == null) { lastError = "API返回空结果"; return null }

            val currentRankKey = result.holeCards.joinToString(",") { it.rank }
            val lastRankKey = holeCardsLocked?.joinToString(",") { it.rank } ?: ""
            if (lastRankKey.isNotEmpty() && currentRankKey != lastRankKey) {
                Log.d(TAG, "手牌锁定: 新一手牌(rank: $lastRankKey→$currentRankKey)，重置")
                holeCardsLocked = null; dButtonLocked = ""
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

    // V2.9.135: 960px/Q75 (实测花色rank全通过，体积减35%→上传快)
    private fun compressImage(jpegData: ByteArray, maxWidth: Int): ByteArray {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size) ?: return jpegData
        val cropTop = (bitmap.height * 0.02).toInt()
        val cropped = try { android.graphics.Bitmap.createBitmap(bitmap, 0, cropTop, bitmap.width, bitmap.height - cropTop) } catch (_: Exception) { bitmap }
        if (cropped !== bitmap) bitmap.recycle()
        val scale = if (cropped.width > maxWidth) maxWidth.toFloat() / cropped.width else 1f
        val scaled = if (scale < 1f) { val s = android.graphics.Bitmap.createScaledBitmap(cropped, (cropped.width * scale).toInt(), (cropped.height * scale).toInt(), true); cropped.recycle(); s } else cropped
        val stream = ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, stream); scaled.recycle()
        return stream.toByteArray()
    }

    private fun buildRequest(base64Image: String, model: String? = null, compact: Boolean = true): String {
        val prompt = if (compact) {
            """先判断截图是否为德州扑克游戏桌面(必须有手牌区+操作按钮+牌桌才叫扑克桌面)，返回单行JSON(禁止换行禁止markdown)。格式:
{"is_poker_table":true,"hole_cards":[{"rank":"A","suit":"h"},{"rank":"K","suit":"d"}],"community_cards":[{"rank":"Q","suit":"c"}],"pot":"200","my_chips":"5000","bet_to_call":"100","dealer_seat":3,"my_seat":1,"blinds":"100/200","phase":"preflop","opp_seats":[{"seat":2,"chips":"3000","action":"fold"}],"buttons":["弃牌","跟注500","加注"],"d_button_pos":"left-top","total_players":6,"active_players":3,"showdown_cards":[{"seat":3,"cards":[{"rank":"K","suit":"s"},{"rank":"9","suit":"h"}],"won":true}]}
⚠️CRITICAL:buttons是决定策略的核心字段！必须完整识别屏幕底部所有操作按钮文字！包括:弃牌/让牌/过牌/跟注(含金额如"跟注1,500")/加注(含比例如"50%加注4,500"或"加注1,200")/下注(含比例如"33%下注726"或"下注500")/全押/全下/任意加注/最小加注/弃牌让牌(组合按钮=有让牌选项)。buttons识别不全会导致策略完全错误！
⚠️showdown_cards:如果截图处于摊牌阶段(能看到对手翻开的牌)，必须识别每个亮牌对手的seat号+2张手牌+是否赢了(won)。如果不在摊牌阶段或看不到对手的牌，showdown_cards填[]。
⚠️opp_hud:识别每个对手头像旁的Smart HUD统计数字。格式:[{"seat":2,"vpip":35,"pfr":18,"ats":40,"three_bet":8}]。vpip/pfr/ats/three_bet都是百分比整数。如果看不到HUD数字，opp_hud填[]。
is_poker_table=布尔值(不是扑克桌面必须false)，rank=A/2-10/J/Q/K，suit=h(红心♥)/d(方块♦)/c(梅花♣)/s(黑桃♠)，phase=preflop/flop/turn/river/showdown,action=fold/check/call/raise/allin,d_button_pos=bottom-center/left-bottom/left-top/top-center/right-top/right-bottom/not_found。⚠️hole_cards必须识别2张手牌(屏幕底部正面朝上的牌)，每张必须返回rank和suit！不是扑克桌面时is_poker_table=false，其余字段填默认值即可。从截图识别真实数据,无公共牌community_cards填[]。⚠️community_cards只返回当前桌面上已亮出的公共牌，未亮出的牌位不要返回空槽位(如翻牌3张就只返回3个，不要返回5个含2个null)"""
        } else {
            """先判断截图是否为德州扑克游戏桌面(必须有手牌区+操作按钮+牌桌才叫扑克桌面)。

识别德州扑克截图，需要rank和suit：

1. is_poker_table：是否为扑克桌面(不是必须false)
2. 底池pot_size：牌桌中央"底池XXX"的数字，去逗号，10K=10000
3. 筹码my_chips：左下角你头像下数字
4. ⚠️手牌hole_cards：底部2张正面朝上的牌，必须返回rank和suit！rank=A K Q J T 9 8 7 6 5 4 3 2(T=10)，suit=h(红心♥) d(方块♦) c(梅花♣) s(黑桃♠)
5. 公共牌community_cards：桌面中央0/3/4/5张，必须返回rank和suit。⚠️只返回当前已亮出的牌，未亮出的牌不要返回空槽位(翻牌=3张，转牌=4张，河牌=5张)
6. D按钮位置d_button_pos：黄色圆圈D标记靠近哪个座位——bottom-center/left-bottom/left-top/top-center/right-top/right-bottom/not_found
7. ⚠️操作按钮buttons(极其重要！)：底部所有按钮原样输出，必须包含"弃牌""让牌/过牌""跟注XXX""加注XXX""下注XXX""全押/全下"等全部可见按钮文字，遗漏会导致策略完全错误！
8. total_players总座位数，active_players活跃人数
9. 盲注：标题"100/200"→blind_sb=100 blind_bb=200
10. 跟注to_call："跟注3,194"→3194，"让牌"→0，"全押"→my_chips
11. ⚠️摊牌showdown_cards：如果能看到对手翻开的牌(摊牌阶段)，识别每个亮牌对手的seat+2张手牌+是否赢了(won)。看不到对手牌则填[]

❌ pot_size不是你头像下数字（那是my_chips）

返回JSON：
{"is_poker_table":true,"hole_cards":[{"rank":"A","suit":"h"},{"rank":"K","suit":"d"}],"community_cards":[{"rank":"K","suit":"c"},{"rank":"T","suit":"s"}],"buttons":["弃牌","跟注500","加注"],"my_chips":0,"pot_size":0,"to_call":0,"total_players":6,"active_players":3,"street":"preflop","blind_sb":200,"blind_bb":500,"ante":0,"d_button_pos":"left-top","showdown_cards":[{"seat":3,"cards":[{"rank":"K","suit":"s"},{"rank":"9","suit":"h"}],"won":true}]}

不是扑克桌面时is_poker_table必须false，其余字段填默认值。只返回JSON"""
        }
        return JSONObject().apply {
            put("model", model ?: modelName); put("max_tokens", 500); put("temperature", 0.1)
            put("messages", JSONArray().apply { put(JSONObject().apply {
                put("role", "user"); put("content", JSONArray().apply {
                    put(JSONObject().apply { put("type", "text"); put("text", prompt) })
                    put(JSONObject().apply { put("type", "image_url"); put("image_url", JSONObject().apply { put("url", base64Image); put("detail", "high") }) })
                })
            }) })
        }.toString()
    }

    private fun sendRequest(requestJson: String): String {
        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        conn.apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); setRequestProperty("Authorization", "Bearer $apiKey"); doOutput = true; connectTimeout = 8000; readTimeout = 20000 }
        conn.outputStream.use { it.write(requestJson.toByteArray(Charsets.UTF_8)) }
        return if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText()
        else throw Exception("HTTP ${conn.responseCode}: ${conn.errorStream?.bufferedReader()?.readText() ?: ""}")
    }

    private fun parseResponse(responseBody: String): VisionResult? {
        val content = JSONObject(responseBody).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
        val jsonStr = extractJson(content) ?: return null
        val data = JSONObject(jsonStr)
        val isCompact = data.has("phase") || data.has("bet_to_call") || data.has("blinds")
        val buttons = (0 until (data.optJSONArray("buttons")?.length() ?: 0)).map { data.optJSONArray("buttons")!!.getString(it) }
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
return VisionResult(isPokerTable, parseCards(data.optJSONArray("hole_cards")), parseCards(data.optJSONArray("community_cards")), insuredPot, parseChipValue(data, "my_chips"), data.optInt("total_players", 6), data.optInt("active_players", 2), data.optString("my_position", ""), street, finalToCall, data.optInt("min_raise", 0), buttons, blindSB, blindBB, parseChipValue(data, "ante"), players, data.optString("d_button_pos", ""), content, showdownCards, oppHud)
    }

    private fun parseOppSeats(arr: JSONArray?): List<PlayerInfo> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            try { val o = arr.optJSONObject(i) ?: return@mapNotNull null; val s = o.optInt("seat", 0); val c = parseChipValue(o, "chips"); val a = o.optString("action", ""); if (s > 0) PlayerInfo(seatToPosition(s), if (a == "raise" || a == "call" || a == "allin") c else 0, c, a != "fold") else null } catch (_: Exception) { null }
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

    private fun extractJson(text: String): String? {
        try { JSONObject(text); return text } catch (_: Exception) {}
        val m = Regex("```(?:json)?\\s*\\n?([\\s\\S]*?)\\n?```").find(text); if (m != null) try { JSONObject(m.groupValues[1].trim()); return m.groupValues[1].trim() } catch (_: Exception) {}
        val s = text.indexOf('{'); val e = text.lastIndexOf('}'); if (s >= 0 && e > s) try { JSONObject(text.substring(s, e + 1)); return text.substring(s, e + 1) } catch (_: Exception) {}
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
            if (btn.contains("过牌") || btn.contains("让牌")) return 0
            if (btn.contains("跟注")) { val n = btn.replace("跟注","").trim().replace(",",""); return try { if (n.isEmpty()) 0 else if (n.endsWith("K",true)) (n.dropLast(1).toFloat()*1000).toInt() else if (n.endsWith("M",true)) (n.dropLast(1).toFloat()*1000000).toInt() else n.toInt() } catch (_: Exception) { -1 } }
            if (btn.contains("全押") || btn.contains("全下")) { val n = btn.replace("全押","").replace("全下","").trim().replace(",",""); return try { if (n.isEmpty()) -1 else if (n.endsWith("K",true)) (n.dropLast(1).toFloat()*1000).toInt() else if (n.endsWith("M",true)) (n.dropLast(1).toFloat()*1000000).toInt() else n.toInt() } catch (_: Exception) { -1 } }
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
            put("players", JSONArray(result.players.map { JSONObject().apply { put("position", it.position); put("bet", it.bet); put("chips", it.chips); put("active", it.active) } }))
            put("is_poker_table", result.isPokerTable); put("d_button_position", result.dButtonPosition); put("suit_uncertain", suitUncertain); put("hole_cards_locked", holeCardsLocked != null); put("lock_reason", lockReason)
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
        }
    }
}
