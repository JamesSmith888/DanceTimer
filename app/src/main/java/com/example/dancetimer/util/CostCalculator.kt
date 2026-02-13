package com.example.dancetimer.util

import com.example.dancetimer.data.model.PriceTier
import kotlin.math.floor

/**
 * 费用计算工具 — 半曲中点计费 + 停止缓冲
 *
 * 核心规则：
 * 1. 以歌曲为计费单位，过了每首歌的中点即收该首歌的费用
 * 2. 已收费曲数 = floor(已跳秒数 / 歌曲时长秒数 + 0.5)
 * 3. 费用 = 已收费曲数 × 每曲价格
 * 4. 歌曲结束后有停止缓冲期，避免因操作延迟导致多收费
 *
 * 例如：4分钟一首歌20元
 *   - 0~1:59 → ¥0、2:00~5:59 → ¥20、6:00~9:59 → ¥40 …
 * 例如：3分钟一首歌10元
 *   - 0~1:29 → ¥0、1:30~4:29 → ¥10、4:30~7:29 → ¥20 …
 */
object CostCalculator {

    /** 停止缓冲期（秒）— 歌曲结束后的缓冲时间，防止操作延迟导致多收费 */
    const val GRACE_PERIOD_SECONDS = 30

    // ==================== 计费核心 ====================

    /**
     * 半曲中点计费（不含停止缓冲）
     *
     * 公式：chargedSongs = floor(durationSeconds / songDurationSeconds + 0.5)
     * 费用 = chargedSongs × pricePerSong
     *
     * @param durationSeconds 实际时长（秒）
     * @param tiers 价格档位（取第一个档位）
     * @return 费用（整数元）
     */
    fun calculateRaw(durationSeconds: Int, tiers: List<PriceTier>): Float {
        if (tiers.isEmpty()) return 0f
        val first = tiers.sortedBy { it.durationMinutes }.first()
        val songDurationSeconds = (first.durationMinutes * 60).toInt()
        if (songDurationSeconds <= 0) return 0f
        val chargedSongs = floor(durationSeconds.toDouble() / songDurationSeconds + 0.5).toInt()
        return (chargedSongs * first.price)
    }

    /**
     * 统一计费入口 — 带停止缓冲
     *
     * 歌曲结束后的缓冲期内，费用保持在上一首歌结束时的金额，
     * 避免因操作延迟（走到手机旁、点击停止等）导致多收费。
     *
     * @param durationSeconds 实际时长（秒）
     * @param tiers 价格档位
     * @return 缓冲调整后的费用
     */
    fun calculate(durationSeconds: Int, tiers: List<PriceTier>): Float {
        if (tiers.isEmpty()) return 0f
        if (durationSeconds == 0) return calculateRaw(0, tiers)

        val effectiveSeconds = getEffectiveSeconds(durationSeconds, tiers)
        return calculateRaw(effectiveSeconds, tiers)
    }

    // ==================== 停止缓冲 ====================

    /**
     * 检查当前是否处于停止缓冲期内
     *
     * 缓冲期：在歌曲结束后 [0, GRACE_PERIOD_SECONDS) 秒内
     * 第一首歌开始时不算缓冲期
     */
    fun isInGracePeriod(durationSeconds: Int, tiers: List<PriceTier>): Boolean {
        if (tiers.isEmpty() || durationSeconds == 0) return false
        val songDurationSeconds = getSongDurationSeconds(tiers)
        if (songDurationSeconds <= 0) return false

        val effectiveGrace = GRACE_PERIOD_SECONDS.coerceAtMost(songDurationSeconds / 2)
        val secondsIntoSong = durationSeconds % songDurationSeconds
        return secondsIntoSong < effectiveGrace && durationSeconds >= songDurationSeconds
    }

    /**
     * 获取停止缓冲期内剩余秒数（用于 UI 显示倒计时）
     * @return 剩余秒数，0 表示不在缓冲期内
     */
    fun getGraceRemainingSeconds(durationSeconds: Int, tiers: List<PriceTier>): Int {
        if (!isInGracePeriod(durationSeconds, tiers)) return 0
        val songDurationSeconds = getSongDurationSeconds(tiers)
        val effectiveGrace = GRACE_PERIOD_SECONDS.coerceAtMost(songDurationSeconds / 2)
        val secondsIntoSong = durationSeconds % songDurationSeconds
        return effectiveGrace - secondsIntoSong
    }

    /**
     * 获取停止缓冲节省的金额
     * @return 节省金额，0 表示未应用缓冲
     */
    fun getGraceSavedAmount(durationSeconds: Int, tiers: List<PriceTier>): Float {
        if (!isInGracePeriod(durationSeconds, tiers)) return 0f
        val rawCost = calculateRaw(durationSeconds, tiers)
        val graceCost = calculate(durationSeconds, tiers)
        return rawCost - graceCost
    }

    /**
     * 获取停止缓冲调整后的有效秒数
     * 在缓冲期内，回退到歌曲结束前一秒
     */
    private fun getEffectiveSeconds(durationSeconds: Int, tiers: List<PriceTier>): Int {
        if (!isInGracePeriod(durationSeconds, tiers)) return durationSeconds
        val songDurationSeconds = getSongDurationSeconds(tiers)
        // 回退到歌曲边界前一秒（即上一首歌结束时刻）
        return (durationSeconds / songDurationSeconds) * songDurationSeconds - 1
    }

    // ==================== 歌曲信息 ====================

    /**
     * 当前歌曲索引（0-based）— 用于振动提醒等，不受停止缓冲影响
     *
     * 在每首歌边界触发，使服务层的 lastReachedSongIndex 比较自然触发振动。
     */
    fun getCurrentSongIndex(durationSeconds: Int, tiers: List<PriceTier>): Int {
        if (tiers.isEmpty() || durationSeconds == 0) return 0
        val songDurationSeconds = getSongDurationSeconds(tiers)
        if (songDurationSeconds <= 0) return 0
        return durationSeconds / songDurationSeconds
    }

    /**
     * 已计费曲数 — 与金额同步，受停止缓冲影响
     *
     * 过了每首歌中点才计为 1 曲。
     * 例如 4分/曲：0~1:59 → 0曲, 2:00~5:59 → 1曲, 6:00~9:59 → 2曲
     */
    fun getSongCount(durationSeconds: Int, tiers: List<PriceTier>): Int {
        if (tiers.isEmpty()) return 0
        val songDurationSeconds = getSongDurationSeconds(tiers)
        if (songDurationSeconds <= 0) return 0
        val effectiveSeconds = getEffectiveSeconds(durationSeconds, tiers)
        return floor(effectiveSeconds.toDouble() / songDurationSeconds + 0.5).toInt()
    }

    /**
     * 生成时间线上的歌曲刻度标记（半曲中点计费）
     * @return List of Pair(分钟数, 此处的累计价格)
     */
    fun generateSongMarks(tiers: List<PriceTier>, maxMinutes: Float = 60f): List<Pair<Float, Float>> {
        if (tiers.isEmpty()) return emptyList()
        val sorted = tiers.sortedBy { it.durationMinutes }
        val first = sorted.first()
        val songDurationMinutes = first.durationMinutes
        if (songDurationMinutes <= 0f) return emptyList()

        val marks = mutableListOf<Pair<Float, Float>>()
        // 标记每首歌的中点（收费切换点）
        var songIndex = 0
        while (true) {
            val midpointMinutes = songIndex * songDurationMinutes + songDurationMinutes / 2f
            if (midpointMinutes > maxMinutes) break
            val chargedSongs = songIndex + 1
            marks.add(Pair(midpointMinutes, chargedSongs * first.price))
            songIndex++
        }
        return marks
    }

    // ==================== 工具方法 ====================

    /** 获取歌曲时长（秒） */
    private fun getSongDurationSeconds(tiers: List<PriceTier>): Int {
        val first = tiers.sortedBy { it.durationMinutes }.firstOrNull() ?: return 0
        return (first.durationMinutes * 60).toInt()
    }

    fun formatDuration(seconds: Int): String {
        val min = seconds / 60
        val sec = seconds % 60
        return "%d:%02d".format(min, sec)
    }

    fun formatCost(cost: Float): String {
        return if (cost == cost.toLong().toFloat()) {
            "¥${cost.toLong()}"
        } else {
            "¥${"%.1f".format(cost)}"
        }
    }

    fun formatStartTime(millis: Long): String {
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(millis))
    }

    fun formatDurationChinese(seconds: Int): String {
        val min = seconds / 60
        val sec = seconds % 60
        return if (min > 0) {
            if (sec > 0) "${min}分${sec}秒" else "${min}分钟"
        } else {
            "${sec}秒"
        }
    }

    /**
     * 生成通知栏计费摘要文本
     *
     * 示例：
     * - 未满1曲：  "未满1曲 · ¥0"
     * - 已计费：   "已计2曲 · ¥40"
     * - 停止缓冲中：  "⏸️ 停止缓冲 7s · 已计2曲 · ¥40"
     */
    fun buildNotificationBillingText(durationSeconds: Int, tiers: List<PriceTier>): String {
        if (tiers.isEmpty()) return ""
        val songCount = getSongCount(durationSeconds, tiers)
        val cost = calculate(durationSeconds, tiers)
        val costStr = formatCost(cost)

        val parts = mutableListOf<String>()

        // 停止缓冲提示
        if (isInGracePeriod(durationSeconds, tiers)) {
            val remaining = getGraceRemainingSeconds(durationSeconds, tiers)
            parts.add("⏸️ 停止缓冲 ${remaining}s")
        }

        val songStr = if (songCount > 0) "已计${songCount}曲" else "未满1曲"
        parts.add(songStr)
        parts.add(costStr)

        return parts.joinToString(" · ")
    }
}
