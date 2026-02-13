package com.example.dancetimer.ui.screen.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 统一的大号圆形操作按钮。
 *
 * @param icon 图标
 * @param label 底部文字标签（可选）
 * @param onClick 点击回调
 * @param containerColor 按钮底色
 * @param contentColor 图标 / 标签颜色
 * @param size 按钮直径
 * @param iconSize 图标尺寸
 */
@Composable
fun CircularActionButton(
    icon: ImageVector,
    label: String? = null,
    onClick: () -> Unit,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
    iconSize: Dp = 36.dp
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 2.dp,
                pressedElevation = 6.dp
            )
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(iconSize)
            )
        }
        if (label != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ===== 预设按钮组合 =====

/** 开始计时 — 大号主色调圆形按钮 */
@Composable
fun StartButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    CircularActionButton(
        icon = Icons.Filled.PlayArrow,
        label = null,
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        size = 96.dp,
        iconSize = 48.dp,
        modifier = modifier
    )
}

/** 计时中 — 暂停 / 继续 + 停止 按钮行 */
@Composable
fun TimerControlButtons(
    isPaused: Boolean,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        // 暂停 / 继续 按钮
        // Resume (继续) -> Primary (强烈的继续信号)
        // Pause (暂停)  -> PrimaryContainer (柔和的保持状态)
        val isResume = isPaused
        val prContainerColor = if (isResume) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
        val prContentColor = if (isResume) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer

        CircularActionButton(
            icon = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            label = if (isPaused) "继续" else "暂停",
            onClick = if (isPaused) onResumeClick else onPauseClick,
            containerColor = prContainerColor,
            contentColor = prContentColor,
            size = 72.dp,
            iconSize = 32.dp
        )

        // 停止 按钮 -> ErrorContainer (警示但不喧宾夺主，与PrimaryContainer更协调)
        CircularActionButton(
            icon = Icons.Filled.Stop,
            label = "停止",
            onClick = onStopClick,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            size = 72.dp,
            iconSize = 32.dp
        )
    }
}
