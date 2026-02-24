package com.example.dancetimer.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.example.dancetimer.data.preferences.ThemeMode
import com.example.dancetimer.data.preferences.TriggerMode
import com.example.dancetimer.ui.navigation.Screen
import com.example.dancetimer.ui.screen.components.BackgroundGuideDialog
import com.example.dancetimer.ui.screen.components.isIgnoringBatteryOptimizations
import com.example.dancetimer.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavHostController,
    viewModel: SettingsViewModel = viewModel()
) {
    val triggerMode by viewModel.triggerMode.collectAsState(initial = TriggerMode.LONG_PRESS)
    val vibrateOnTier by viewModel.vibrateOnTier.collectAsState(initial = true)
    val autoStartOnScreenOff by viewModel.autoStartOnScreenOff.collectAsState(initial = false)
    val autoStartDelay by viewModel.autoStartDelaySeconds.collectAsState(initial = 180)
    val stepDetectionEnabled by viewModel.stepDetectionEnabled.collectAsState(initial = false)
    val themeMode by viewModel.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val context = LocalContext.current
    var showBatteryGuide by remember { mutableStateOf(false) }
    // 每次回到前台重新检测
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var isBatteryOptimized by remember { mutableStateOf(false) }
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isBatteryOptimized = !isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showBatteryGuide) {
        BackgroundGuideDialog(
            onDismiss = {
                showBatteryGuide = false
                isBatteryOptimized = !isIgnoringBatteryOptimizations(context)
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "设置",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // ===== 计费规则入口 =====
            SettingSectionHeader("计费")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                onClick = { navController.navigate(Screen.PricingRules.route) { launchSingleTop = true } }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.AttachMoney,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "计费规则",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "管理舞厅价格规则",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ===== 外观主题 =====
            SettingSectionHeader("外观")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column {
                    ThemeModeOption(
                        label = "深色模式",
                        selected = themeMode == ThemeMode.DARK,
                        onClick = { viewModel.setThemeMode(ThemeMode.DARK) }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                    ThemeModeOption(
                        label = "浅色模式",
                        selected = themeMode == ThemeMode.LIGHT,
                        onClick = { viewModel.setThemeMode(ThemeMode.LIGHT) }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )
                    ThemeModeOption(
                        label = "跟随系统",
                        selected = themeMode == ThemeMode.SYSTEM,
                        onClick = { viewModel.setThemeMode(ThemeMode.SYSTEM) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ===== 触发方式 =====
            SettingSectionHeader("触发方式")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column {
                    TriggerModeOption(
                        label = "长按音量键 (1.5秒)",
                        description = "长按不易误触，推荐",
                        selected = triggerMode == TriggerMode.LONG_PRESS,
                        onClick = { viewModel.setTriggerMode(TriggerMode.LONG_PRESS) }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                    )
                    TriggerModeOption(
                        label = "三连按音量键",
                        description = "600ms内连按3次触发",
                        selected = triggerMode == TriggerMode.TRIPLE_CLICK,
                        onClick = { viewModel.setTriggerMode(TriggerMode.TRIPLE_CLICK) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ===== 通知与提醒 =====
            SettingSectionHeader("通知与提醒")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column {
                    // 震动提醒
                    SettingSwitchItem(
                        title = "到达档位时震动",
                        description = "震动次数 = 档位序号（第1档震1次，第2档震2次）",
                        checked = vibrateOnTier,
                        onCheckedChange = { viewModel.setVibrateOnTier(it) }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                    )
                    // 息屏自动计时
                    SettingSwitchItem(
                        title = "息屏/锁屏后自动开始",
                        description = if (autoStartOnScreenOff)
                            buildAnnotatedString {
                                append("锁屏后等待 ")
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
                                    append(if (autoStartDelay >= 60)
                                        "${autoStartDelay / 60}分${if (autoStartDelay % 60 > 0) "${autoStartDelay % 60}秒" else ""}"
                                    else
                                        "${autoStartDelay}秒"
                                    )
                                }
                                append(" 未亮屏则自动计时，首曲计费前可取消")
                            }
                        else
                            buildAnnotatedString { append("开启后，锁屏将自动触发计时（智能防误触）") },
                        checked = autoStartOnScreenOff,
                        onCheckedChange = { viewModel.setAutoStartOnScreenOff(it) }
                    )
                    // 展开延迟设置
                    if (autoStartOnScreenOff) {
                        // 子配置区域：左侧竖线 + 缩进背景，明确归属关系
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
                        ) {
                            // 左侧强调竖线
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Column(modifier = Modifier.fillMaxWidth()) {
                                AutoStartDelaySelector(
                                    selectedDelay = autoStartDelay,
                                    onDelaySelected = { viewModel.setAutoStartDelaySeconds(it) },
                                    stepDetectionEnabled = stepDetectionEnabled
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)
                                )
                                // 步行防误触
                                SettingSwitchItem(
                                    title = "步行中不触发计时",
                                    description = if (autoStartDelay < 10 && stepDetectionEnabled)
                                        buildAnnotatedString {
                                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) {
                                                append("⚠ 延迟${autoStartDelay}秒过短，步行检测不准确，建议延迟≥ 10秒")
                                            }
                                        }
                                    else if (autoStartDelay < 10)
                                        buildAnnotatedString { append("等待期间检测步行(>80步/分)则停止自动计时，停下后自动重试(建议延迟≥ 10秒)")
                                        }
                                    else
                                        buildAnnotatedString { append("等待期间检测步行(>80步/分)则停止自动计时，停下后自动重试") },
                                    checked = stepDetectionEnabled,
                                    onCheckedChange = { viewModel.setStepDetectionEnabled(it) }
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                    )
                    // 后台运行设置
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showBatteryGuide = true }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                "后台与通知设置",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            if (isBatteryOptimized) {
                                Text(
                                    text = "⚠ 电池优化已开启，通知可能不实时",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    lineHeight = 16.sp
                                )
                            } else {
                                Text(
                                    text = "✅ 已关闭电池优化",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = if (isBatteryOptimized) MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                   else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ===== 使用说明 =====
            SettingSectionHeader("使用说明")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val steps = listOf(
                        "1. 配置你的计价规则（设置舞厅的价格档位）",
                        "2. 长按【音量+】震动后，开始计时",
                        "3. 长按【音量-】震动后，结束计时"
                    )
                    steps.forEachIndexed { index, step ->
                        Text(
                            text = step,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 3.dp),
                            lineHeight = 18.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ===== 关于 =====
            SettingSectionHeader("关于")
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                val uriHandler = LocalUriHandler.current
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text(
                        "DanceTimer",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "作者：James Smith",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    // GitHub 链接 — 点击打开浏览器
                    Text(
                        text = "github.com/JamesSmith888/DanceTimer",
                        style = MaterialTheme.typography.bodySmall.copy(
                            textDecoration = TextDecoration.Underline
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            uriHandler.openUri("https://github.com/JamesSmith888/DanceTimer")
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // 邮箱 — 长按可选中复制，点击打开邮件客户端
                    SelectionContainer {
                        Text(
                            text = "jiazhutianxiadiyi@gmail.com",
                            style = MaterialTheme.typography.bodySmall.copy(
                                textDecoration = TextDecoration.Underline
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable {
                                val intent = Intent(Intent.ACTION_SENDTO).apply {
                                    data = Uri.parse("mailto:jiazhutianxiadiyi@gmail.com")
                                }
                                context.startActivity(Intent.createChooser(intent, "发送邮件"))
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun SettingSwitchItem(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingSwitchItem(title, buildAnnotatedString { append(description) }, checked, onCheckedChange)
}

@Composable
private fun SettingSwitchItem(
    title: String,
    description: androidx.compose.ui.text.AnnotatedString,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
    }
}

@Composable
private fun AutoStartDelaySelector(
    selectedDelay: Int,
    onDelaySelected: (Int) -> Unit,
    stepDetectionEnabled: Boolean = false
) {
    // 预设选项：秒 + 分钟
    data class DelayOption(val label: String, val seconds: Int)
    val presets = listOf(
        DelayOption("5秒", 5), DelayOption("10秒", 10), DelayOption("15秒", 15),
        DelayOption("30秒", 30), DelayOption("1分", 60), DelayOption("2分", 120),
        DelayOption("3分", 180), DelayOption("5分", 300)
    )
    // 步行检测开启时，5秒延迟对应的采样时长不足，提示建议延迟
    val stepMinRecommended = if (stepDetectionEnabled) 10 else 0
    val isCustom = selectedDelay !in presets.map { it.seconds }
    var showCustomInput by remember { mutableStateOf(isCustom) }
    var customText by remember { mutableStateOf(if (isCustom) selectedDelay.toString() else "") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
    ) {
        Text(
            "延迟等待时间（触发等待时间，不影响计时）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // 步行检测开启且当前延迟过短时显示提示
        if (stepDetectionEnabled && selectedDelay < stepMinRecommended) {
            Text(
                "⚠ 延迟过短会降低步行检测准确度，建议选择 ≥ 10秒",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        // 所有选项在一行可滚动Row中
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            presets.forEach { option ->
                FilterChip(
                    selected = selectedDelay == option.seconds && !showCustomInput,
                    onClick = {
                        showCustomInput = false
                        onDelaySelected(option.seconds)
                    },
                    label = {
                        Text(
                            option.label,
                            style = MaterialTheme.typography.labelSmall,
                            // 步行检测开启时，5秒选项标识为不推荐（加利线）
                            color = if (stepDetectionEnabled && option.seconds < stepMinRecommended)
                                MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                            else
                                MaterialTheme.colorScheme.onSurface
                        )
                    }
                )
            }
            FilterChip(
                selected = showCustomInput || isCustom,
                onClick = {
                    if (showCustomInput) {
                        // 再次点击 → 关闭自定义输入
                        showCustomInput = false
                    } else {
                        customText = selectedDelay.toString()
                        showCustomInput = true
                    }
                },
                label = {
                    Text(
                        if (isCustom && !showCustomInput)
                            "自定义:${if (selectedDelay >= 60) "${selectedDelay/60}分${selectedDelay%60}秒" else "${selectedDelay}秒"}"
                        else
                            "自定义",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )
        }

        // 自定义输入框
        if (showCustomInput) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it.filter { c -> c.isDigit() } },
                    label = { Text("秒数（1〜600）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                FilledTonalButton(
                    onClick = {
                        val v = customText.toIntOrNull() ?: 0
                        if (v in 1..600) {
                            onDelaySelected(v)
                            showCustomInput = false
                        }
                    },
                    enabled = (customText.toIntOrNull() ?: 0) in 1..600
                ) { Text("确定") }
            }
        }
    }
}

@Composable
private fun TriggerModeOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ThemeModeOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary,
                unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
            )
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}