package com.example.dancetimer.ui.screen.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 后台运行 & 锁屏通知 设置引导弹窗
 */
@Composable
fun BackgroundGuideDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val isOptimized = !isIgnoringBatteryOptimizations(context)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("后台与通知设置")
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // ===== 1. 后台运行 =====
                Text(
                    text = "① 允许后台运行",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "关闭电池优化，确保计时中通知栏实时更新。",
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Text(
                    text = "点击下方按钮将跳转到本应用的「应用详情」页面，请在其中找到：",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    lineHeight = 17.sp
                )
                Text(
                    text = "• 耗电管理 / 电池 → 允许后台活动\n• 或：电池优化 → 选择「不优化」",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                if (isOptimized) {
                    Text(
                        text = "⚠ 当前：电池优化已开启（需要关闭）",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(
                        text = "✅ 当前：已关闭电池优化",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // ===== 2. 锁屏通知 =====
                Text(
                    text = "② 开启锁屏通知",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "让计时通知在锁屏界面也能显示。",
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Text(
                    text = "在「应用详情」页中找到通知管理，开启「锁屏通知」。",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    lineHeight = 17.sp
                )
            }
        },
        confirmButton = {
            if (isOptimized) {
                Button(
                    onClick = {
                        openBatterySettings(context)
                    }
                ) {
                    Text("前往应用详情")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("知道了")
                }
            }
        },
        dismissButton = {
            if (isOptimized) {
                TextButton(onClick = onDismiss) {
                    Text("稍后设置")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp
    )
}

fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

fun openBatterySettings(context: Context) {
    // 直接打开当前应用的「应用详情」页，用户可在其中找到耗电管理/电池优化开关
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return
    } catch (_: Exception) { }

    // 保底：打开应用管理列表
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    } catch (_: Exception) { }
}
