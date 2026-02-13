package com.example.dancetimer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.example.dancetimer.data.preferences.ThemeMode
import com.example.dancetimer.data.preferences.UserPreferencesManager
import com.example.dancetimer.service.TimerForegroundService
import com.example.dancetimer.ui.navigation.AppNavigation
import com.example.dancetimer.ui.theme.DanceTimerTheme
import com.example.dancetimer.util.VolumeKeyDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class MainActivity : ComponentActivity() {

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val volumeKeyDetector = VolumeKeyDetector(
        onVolumeUpTriggered = {
            if (!TimerForegroundService.isRunning) {
                TimerForegroundService.startTimer(this)
            }
        },
        onVolumeDownTriggered = {
            if (TimerForegroundService.isRunning) {
                TimerForegroundService.stopTimer(this)
            }
        }
    )

    // 通知权限请求 (Android 13+)
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            // 无论结果如何都继续
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestNotificationPermission()
        loadTriggerMode()

        // 启动待命服务（MediaSession 锁屏音量键拦截）
        TimerForegroundService.enterStandby(this)

        setContent {
            val context = LocalContext.current
            val prefs = remember { UserPreferencesManager(context) }
            val themeMode by prefs.themeMode.collectAsState(initial = ThemeMode.DARK)

            DanceTimerTheme(themeMode = themeMode) {
                val navController = rememberNavController()
                AppNavigation(navController = navController)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 每次恢复时重新加载触发方式偏好
        loadTriggerMode()
    }

    /**
     * 拦截音量键事件 — App 前台时生效
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (volumeKeyDetector.onKeyEvent(keyCode, KeyEvent.ACTION_DOWN)) {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (volumeKeyDetector.onKeyEvent(keyCode, KeyEvent.ACTION_UP)) {
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun loadTriggerMode() {
        activityScope.launch {
            val prefs = UserPreferencesManager(this@MainActivity)
            val mode = prefs.triggerMode.first()
            volumeKeyDetector.triggerMode = mode
        }
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }
}