package com.example.dancetimer.ui.screen.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dancetimer.data.model.PriceTier
import com.example.dancetimer.util.CostCalculator

// ===== 时间线刻度数据 =====
private data class SongMark(
    val minutes: Float,
    val price: Float
)

/**
 * 可视化舞曲计时时间线。
 *
 * 显示一条带有歌曲刻度标记的水平进度条，包含：
 * - 进度填充
 * - 脉冲动画指示器
 * - 自动抽样标签（首尾必显示）
 */
@Composable
fun DanceTimeline(
    tiers: List<PriceTier>,
    elapsedSeconds: Int,
    isPaused: Boolean,
    modifier: Modifier = Modifier
) {
    if (tiers.isEmpty()) return

    // 生成歌曲刻度标记（最多1小时）
    val rawMarks = CostCalculator.generateSongMarks(tiers, maxMinutes = 60f)
    if (rawMarks.isEmpty()) return

    // 添加起始点（0分钟，显示初始费用）
    val startPrice = CostCalculator.calculate(0, tiers)
    val marks = listOf(SongMark(0f, startPrice)) + rawMarks.map { SongMark(it.first, it.second) }

    val lastMarkMinutes = marks.last().minutes
    val songDuration = tiers.sortedBy { it.durationMinutes }.first().durationMinutes
    val maxDuration = lastMarkMinutes.coerceAtLeast(songDuration)
    val currentMinutes = elapsedSeconds / 60f
    val rawProgress = if (maxDuration > 0f) currentMinutes / maxDuration else 0f

    val animatedProgress by animateFloatAsState(
        targetValue = rawProgress.coerceIn(0f, 1f),
        animationSpec = tween(500),
        label = "progress"
    )

    // 脉冲动画
    val infiniteTransition = rememberInfiniteTransition(label = "timelinePulse")
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 7f,
        targetValue = 13f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseR"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseA"
    )

    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()

    // 标签抽样间隔
    val labelInterval = when {
        marks.size <= 7 -> 1
        marks.size <= 14 -> 2
        marks.size <= 21 -> 3
        else -> 4
    }

    val accentColor = MaterialTheme.colorScheme.primary
    val trackInactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val labelInactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val tickInactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val dotInactiveColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
    val indicatorCenterColor = MaterialTheme.colorScheme.background

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(horizontal = 16.dp)
    ) {
        val trackY = 20.dp.toPx()
        val trackStart = 0f
        val trackEnd = size.width
        val trackWidth = trackEnd - trackStart

        // 轨道背景
        drawLine(
            color = trackInactiveColor,
            start = Offset(trackStart, trackY),
            end = Offset(trackEnd, trackY),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )

        // 进度填充
        val fillEnd = trackStart + trackWidth * animatedProgress
        if (fillEnd > trackStart) {
            drawLine(
                color = accentColor.copy(alpha = 0.5f),
                start = Offset(trackStart, trackY),
                end = Offset(fillEnd, trackY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        val safeGapPx = 40.dp.toPx()

        // 刻度 + 标签
        marks.forEachIndexed { index, mark ->
            val fraction = mark.minutes / maxDuration
            val x = trackStart + trackWidth * fraction
            val reached = currentMinutes >= mark.minutes

            val isFirst = index == 0
            val isLast = index == marks.lastIndex
            val isStandard = index % labelInterval == 0

            val shouldShowLabel = when {
                isFirst -> true
                isLast -> true
                isStandard -> {
                    val pxToFirst = (mark.minutes - marks.first().minutes) / maxDuration * trackWidth
                    val pxToLast = (marks.last().minutes - mark.minutes) / maxDuration * trackWidth
                    pxToFirst >= safeGapPx && pxToLast >= safeGapPx
                }
                else -> false
            }

            // 竖线 tick
            val tickHalf = if (shouldShowLabel) 7.dp.toPx() else 4.dp.toPx()
            drawLine(
                color = if (reached) accentColor.copy(alpha = 0.5f) else tickInactiveColor,
                start = Offset(x, trackY - tickHalf),
                end = Offset(x, trackY + tickHalf),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round
            )

            if (shouldShowLabel) {
                drawCircle(
                    color = if (reached) accentColor else dotInactiveColor,
                    radius = 3.dp.toPx(),
                    center = Offset(x, trackY)
                )
            }

            if (shouldShowLabel) {
                val labelColor = if (reached) accentColor else labelInactiveColor
                val minuteText = formatMinuteLabel(mark.minutes)
                val priceText = CostCalculator.formatCost(mark.price)

                val minuteStyle = TextStyle(fontSize = 9.sp, color = labelColor)
                val priceStyle = TextStyle(fontSize = 9.sp, color = labelColor, fontWeight = FontWeight.Medium)

                val minuteLayout = textMeasurer.measure(minuteText, minuteStyle)
                val priceLayout = textMeasurer.measure(priceText, priceStyle)

                val labelTop1 = trackY + 10.dp.toPx()
                val labelTop2 = labelTop1 + minuteLayout.size.height + 1.dp.toPx()

                val minuteX = when {
                    isFirst -> x
                    isLast -> (x - minuteLayout.size.width).coerceAtLeast(0f)
                    else -> (x - minuteLayout.size.width / 2f).coerceIn(0f, trackEnd - minuteLayout.size.width)
                }
                val priceX = when {
                    isFirst -> x
                    isLast -> (x - priceLayout.size.width).coerceAtLeast(0f)
                    else -> (x - priceLayout.size.width / 2f).coerceIn(0f, trackEnd - priceLayout.size.width)
                }

                drawText(minuteLayout, topLeft = Offset(minuteX, labelTop1))
                drawText(priceLayout, topLeft = Offset(priceX, labelTop2))
            }
        }

        // 当前位置指示器
        val currentX = (trackStart + trackWidth * animatedProgress)
            .coerceIn(trackStart, trackEnd)

        // 脉冲光晕
        if (!isPaused) {
            drawCircle(
                color = accentColor.copy(alpha = pulseAlpha),
                radius = with(density) { pulseRadius.dp.toPx() },
                center = Offset(currentX, trackY)
            )
        }

        // 主点
        drawCircle(
            color = accentColor,
            radius = 6.dp.toPx(),
            center = Offset(currentX, trackY)
        )
        // 白色中心
        drawCircle(
            color = indicatorCenterColor,
            radius = 2.5.dp.toPx(),
            center = Offset(currentX, trackY)
        )
    }
}

/** 格式化分钟标签 */
internal fun formatMinuteLabel(minutes: Float): String {
    return if (minutes == minutes.toLong().toFloat()) {
        "${minutes.toLong()}分"
    } else {
        "${"%.1f".format(minutes)}分"
    }
}
