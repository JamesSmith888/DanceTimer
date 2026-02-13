package com.example.dancetimer.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.example.dancetimer.data.preferences.TriggerMode
import com.example.dancetimer.service.TimerState
import com.example.dancetimer.ui.navigation.Screen
import com.example.dancetimer.ui.screen.content.TimerFinishedContent
import com.example.dancetimer.ui.screen.content.TimerIdleContent
import com.example.dancetimer.ui.screen.content.TimerRunningContent
import com.example.dancetimer.ui.screen.components.BackgroundGuideDialog
import com.example.dancetimer.ui.screen.components.isIgnoringBatteryOptimizations
import com.example.dancetimer.ui.screen.components.openBatterySettings
import com.example.dancetimer.ui.theme.DarkSurface
import com.example.dancetimer.ui.theme.TimerRunningBgDark
import com.example.dancetimer.ui.theme.TimerRunningBgLight
import com.example.dancetimer.ui.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavHostController,
    viewModel: HomeViewModel = viewModel()
) {
    val timerState by viewModel.timerState.collectAsState()
    val defaultRule by viewModel.defaultRule.collectAsState(initial = null)
    val triggerMode by viewModel.triggerMode.collectAsState(initial = TriggerMode.LONG_PRESS)
    val context = LocalContext.current
    var showBatteryGuide by remember { mutableStateOf(false) }
    // 每次回到前台重新检测电池优化状态
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
                // 关闭后重新检测
                isBatteryOptimized = !isIgnoringBatteryOptimizations(context)
            }
        )
    }

    val isRunning = timerState is TimerState.Running
    val isPaused = (timerState as? TimerState.Running)?.isPaused == true
    val isDark = MaterialTheme.colorScheme.surface == DarkSurface

    val runningBgColor = if (isDark) TimerRunningBgDark else TimerRunningBgLight
    val bgColor by animateColorAsState(
        targetValue = when {
            isRunning && isPaused -> MaterialTheme.colorScheme.surfaceVariant
            isRunning -> runningBgColor
            else -> MaterialTheme.colorScheme.background
        },
        animationSpec = tween(600),
        label = "bgColor"
    )

    val colors = MaterialTheme.colorScheme

    Scaffold(
        containerColor = colors.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "计时器",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = colors.onBackground,
                    actionIconContentColor = colors.onBackground,
                    navigationIconContentColor = colors.onBackground
                ),
                actions = {
                    IconButton(onClick = {
                        navController.navigate(Screen.History.route) { launchSingleTop = true }
                    }) {
                        Icon(Icons.Filled.History, contentDescription = "历史记录")
                    }
                    IconButton(onClick = {
                        navController.navigate(Screen.Settings.route) { launchSingleTop = true }
                    }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                when (val state = timerState) {
                    is TimerState.Idle -> TimerIdleContent(
                        ruleName = defaultRule?.rule?.name,
                        triggerMode = triggerMode,
                        isBatteryOptimized = isBatteryOptimized,
                        onStartClick = { viewModel.startTimer() },
                        onRulesClick = {
                            navController.navigate(Screen.PricingRules.route) { launchSingleTop = true }
                        },
                        onBatteryFixClick = { showBatteryGuide = true }
                    )
                    is TimerState.Running -> TimerRunningContent(
                        state = state,
                        onPauseClick = { viewModel.pauseTimer() },
                        onResumeClick = { viewModel.resumeTimer() },
                        onStopClick = { viewModel.stopTimer() },
                        onCancelAutoClick = { viewModel.cancelAutoStart() },
                        onRuleClick = {
                            navController.navigate(
                                Screen.EditRule.createRoute(state.ruleId)
                            ) { launchSingleTop = true }
                        }
                    )
                    is TimerState.Finished -> TimerFinishedContent(
                        state = state,
                        onDismiss = { viewModel.dismissResult() }
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
