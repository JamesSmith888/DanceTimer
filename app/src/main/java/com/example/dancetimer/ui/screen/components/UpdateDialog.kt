package com.example.dancetimer.ui.screen.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.dancetimer.data.update.AppUpdateInfo
import com.example.dancetimer.data.update.UpdateState

/**
 * 版本更新对话框。
 *
 * 根据 [UpdateState] 自动切换显示：
 * - [UpdateState.Available] → 展示更新日志 + "立即更新" 按钮
 * - [UpdateState.Downloading] → 下载进度条
 * - [UpdateState.ReadyToInstall] → "立即安装" 按钮
 * - [UpdateState.Error] → 错误提示 + 重试
 */
/**
 * @param onSnooze 用户点击“稍后提醒”，延迟 3 天再自动提示
 * @param onSkipVersion 用户点击“跳过此版本”，不再自动提示此版本
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    onDownload: (AppUpdateInfo) -> Unit,
    onInstall: (downloadId: Long) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onSnooze: () -> Unit = onDismiss,
    onSkipVersion: (versionName: String) -> Unit = { _ -> onDismiss() }
) {
    // 只在有意义的状态下显示对话框
    val shouldShow = state is UpdateState.Available
            || state is UpdateState.Downloading
            || state is UpdateState.ReadyToInstall
            || state is UpdateState.Error

    if (!shouldShow) return

    Dialog(
        onDismissRequest = {
            // 下载中不允许点外部关闭
            if (state !is UpdateState.Downloading) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = state !is UpdateState.Downloading,
            dismissOnClickOutside = state !is UpdateState.Downloading
        )
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                when (state) {
                    is UpdateState.Available -> AvailableContent(
                        info = state.info,
                        onDownload = { onDownload(state.info) },
                        onSnooze = onSnooze,
                        onSkipVersion = { onSkipVersion(state.info.versionName()) }
                    )

                    is UpdateState.Downloading -> DownloadingContent(
                        info = state.info,
                        progress = state.progress,
                        onCancel = onDismiss
                    )

                    is UpdateState.ReadyToInstall -> ReadyToInstallContent(
                        info = state.info,
                        onInstall = { onInstall(state.downloadId) },
                        onDismiss = onDismiss
                    )

                    is UpdateState.Error -> ErrorContent(
                        message = state.message,
                        onRetry = onRetry,
                        onDismiss = onDismiss
                    )

                    else -> { /* 不会到达 */ }
                }
            }
        }
    }
}

// ==================== 新版本可用 ====================

@Composable
private fun AvailableContent(
    info: AppUpdateInfo,
    onDownload: () -> Unit,
    onSnooze: () -> Unit,
    onSkipVersion: () -> Unit
) {
    // 标题
    Text(
        text = "发现新版本",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )

    Spacer(modifier = Modifier.height(4.dp))

    Text(
        text = "v${info.versionName()}",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )

    // 更新日志
    if (info.body.isNotBlank()) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "更新内容",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Text(
                text = info.body.trim(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            )
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    // 主按钮（全宽）
    Button(
        onClick = onDownload,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("立即更新")
    }

    Spacer(modifier = Modifier.height(4.dp))

    // 次要操作
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onSkipVersion) {
            Text("跳过此版本", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onSnooze) {
            Text("稍后提醒")
        }
    }
}

// ==================== 下载中 ====================

@Composable
private fun DownloadingContent(
    info: AppUpdateInfo,
    progress: Int,
    onCancel: () -> Unit
) {
    Text(
        text = "正在下载 v${info.versionName()}",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )

    Spacer(modifier = Modifier.height(20.dp))

    if (progress in 0..100) {
        LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "$progress%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    } else {
        LinearProgressIndicator(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "正在下载…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(onClick = onCancel) {
            Text("取消下载")
        }
    }
}

// ==================== 下载完成，准备安装 ====================

@Composable
private fun ReadyToInstallContent(
    info: AppUpdateInfo,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    Text(
        text = "下载完成",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "v${info.versionName()} 已准备就绪，可以安装。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(modifier = Modifier.height(20.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onDismiss) {
            Text("稍后安装")
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(onClick = onInstall) {
            Text("立即安装")
        }
    }
}

// ==================== 错误 ====================

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    Text(
        text = "检查更新失败",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.error
    )

    Spacer(modifier = Modifier.height(12.dp))

    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis
    )

    Spacer(modifier = Modifier.height(20.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onDismiss) {
            Text("关闭")
        }
        Spacer(modifier = Modifier.width(8.dp))
        FilledTonalButton(onClick = onRetry) {
            Text("重试")
        }
    }
}
