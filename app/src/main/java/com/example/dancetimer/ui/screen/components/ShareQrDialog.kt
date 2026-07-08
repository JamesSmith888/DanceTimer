package com.example.dancetimer.ui.screen.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.dancetimer.ui.viewmodel.ShareQrState

/**
 * 展示"扫码下载"二维码的对话框。
 *
 * - [ShareQrState.Loading]：显示加载指示器
 * - [ShareQrState.Ready]：显示二维码图片 + 说明文字
 * - [ShareQrState.Error]：显示错误信息 + 重试按钮
 * - [ShareQrState.Idle]：不显示对话框
 */
@Composable
fun ShareQrDialog(
    state: ShareQrState,
    onDismiss: () -> Unit,
    onRetry: () -> Unit
) {
    if (state is ShareQrState.Idle) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "分享 DanceTimer（仅支持 Android）",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            when (state) {
                is ShareQrState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "正在获取最新版下载地址…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                is ShareQrState.Ready -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            bitmap = state.bitmap.asImageBitmap(),
                            contentDescription = "DanceTimer 下载二维码",
                            modifier = Modifier.size(230.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Spacer(modifier = Modifier.height(8.dp))
                        // 操作说明
                        Spacer(modifier = Modifier.height(6.dp))
                        // iOS 提示
                        Text(
                            text = "⚠ 微信扫码后可能会被拦截，复制下载地址到手机浏览器下载即可",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                is ShareQrState.Error -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(onClick = onRetry) {
                            Text("重试")
                        }
                    }
                }

                is ShareQrState.Idle -> { /* unreachable */ }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}
