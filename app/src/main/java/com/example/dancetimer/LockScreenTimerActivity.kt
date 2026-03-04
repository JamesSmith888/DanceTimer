package com.example.dancetimer

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dancetimer.service.TimerForegroundService
import com.example.dancetimer.service.TimerState
import com.example.dancetimer.util.CostCalculator
import kotlinx.coroutines.delay

/**
 * Lock screen overlay activity — shown on top of the lock screen when a new song
 * is billed or auto-start fires. The screen wakes up, displays timer info on a
 * pure-black background, then auto-dismisses after AUTO_DISMISS_MS.
 *
 * Uses setShowWhenLocked() + setTurnScreenOn() (API 27+), the standard mechanism
 * used by alarm clock apps. No special manufacturer permission required.
 */
class LockScreenTimerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LockScreenTimerAct"
        private const val AUTO_DISMISS_MS = 6_000L

        const val EXTRA_SONG_COUNT = "extra_song_count"
        const val EXTRA_COST = "extra_cost"
        const val EXTRA_ELAPSED_SECONDS = "extra_elapsed_seconds"
        const val EXTRA_IS_AUTO_START = "extra_is_auto_start"

        fun createIntent(
            context: Context,
            songCount: Int,
            cost: Float,
            elapsedSeconds: Int,
            isAutoStart: Boolean = false
        ): Intent = Intent(context, LockScreenTimerActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            putExtra(EXTRA_SONG_COUNT, songCount)
            putExtra(EXTRA_COST, cost)
            putExtra(EXTRA_ELAPSED_SECONDS, elapsedSeconds)
            putExtra(EXTRA_IS_AUTO_START, isAutoStart)
        }
    }

    private val dismissHandler = Handler(Looper.getMainLooper())
    private val autoDismissRunnable = Runnable {
        Log.d(TAG, "Auto-dismiss triggered after ${AUTO_DISMISS_MS}ms")
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Allow this activity to show on top of the lock screen and wake the screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Set window brightness to near-zero to simulate AOD:
        // the screen hardware is technically "on" (needed to render content) but
        // the backlight is at minimum, so everything outside our black background
        // appears pitch-black — exactly the WeChat lock-screen message effect.
        // BRIGHTNESS_OVERRIDE_NONE = -1 (use system), 0.0f = minimum, 1.0f = maximum.
        window.attributes = window.attributes.also { lp ->
            lp.screenBrightness = 0.01f
        }

        // Do NOT call requestDismissKeyguard — we want the lock screen overlay
        // to sit on top of the keyguard, not dismiss it.

        val songCount = intent.getIntExtra(EXTRA_SONG_COUNT, 0)
        val cost = intent.getFloatExtra(EXTRA_COST, 0f)
        val elapsedSeconds = intent.getIntExtra(EXTRA_ELAPSED_SECONDS, 0)
        val isAutoStart = intent.getBooleanExtra(EXTRA_IS_AUTO_START, false)

        Log.d(TAG, "LockScreenTimerActivity created: song=$songCount cost=$cost elapsed=${elapsedSeconds}s autoStart=$isAutoStart")

        setContent {
            LockScreenContent(
                initialSongCount = songCount,
                initialCost = cost,
                initialElapsedSeconds = elapsedSeconds,
                isAutoStart = isAutoStart,
                autoDismissMs = AUTO_DISMISS_MS,
                onOpenApp = {
                    dismissHandler.removeCallbacks(autoDismissRunnable)
                    val intent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                    finish()
                }
            )
        }

        dismissHandler.postDelayed(autoDismissRunnable, AUTO_DISMISS_MS)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Reset auto-dismiss timer when a new billing event re-triggers while visible
        dismissHandler.removeCallbacks(autoDismissRunnable)
        dismissHandler.postDelayed(autoDismissRunnable, AUTO_DISMISS_MS)
        Log.d(TAG, "onNewIntent: auto-dismiss timer reset")
    }

    override fun onDestroy() {
        dismissHandler.removeCallbacks(autoDismissRunnable)
        super.onDestroy()
    }
}

@Composable
private fun LockScreenContent(
    initialSongCount: Int,
    initialCost: Float,
    initialElapsedSeconds: Int,
    isAutoStart: Boolean,
    autoDismissMs: Long,
    onOpenApp: () -> Unit
) {
    // Subscribe to live timer state so values stay accurate while overlay is visible
    val timerState by TimerForegroundService.timerState.collectAsState()
    val running = timerState as? TimerState.Running

    val songCount = running?.songCount ?: initialSongCount
    val cost = running?.cost ?: initialCost
    val elapsedSeconds = running?.elapsedSeconds ?: initialElapsedSeconds

    val totalMinutes = elapsedSeconds / 60
    val remainingSeconds = elapsedSeconds % 60
    val timeStr = if (totalMinutes > 0)
        "%d:%02d".format(totalMinutes, remainingSeconds)
    else
        "${elapsedSeconds}s"

    val costStr = CostCalculator.formatCost(cost)
    val songPart = if (songCount > 0) "第 $songCount 曲" else "未满1曲"

    // Fade-in on appear, fade-out before dismiss
    var alpha by remember { mutableFloatStateOf(0f) }
    var visible by remember { mutableStateOf(true) }
    val animatedAlpha by animateFloatAsState(
        targetValue = if (visible) alpha else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "alpha"
    )

    LaunchedEffect(Unit) {
        alpha = 1f
        delay(autoDismissMs - 500)
        visible = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .alpha(animatedAlpha)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onOpenApp
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            // App icon / timer icon
            Icon(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = null,
                tint = Color(0xFF81C784),
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Status label
            Text(
                text = if (isAutoStart) "息屏自动计时" else "计费提醒",
                fontSize = 13.sp,
                color = Color(0xFF9E9E9E),
                letterSpacing = 2.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Song count — prominent
            Text(
                text = songPart,
                fontSize = 42.sp,
                fontWeight = FontWeight.Light,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Cost — the key number
            Text(
                text = costStr,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF81C784),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Elapsed time
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "已计时 ",
                    fontSize = 15.sp,
                    color = Color(0xFF757575)
                )
                Text(
                    text = timeStr,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFFBDBDBD)
                )
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Hint
            Text(
                text = "点击进入 · 长按音量- 停止",
                fontSize = 12.sp,
                color = Color(0xFF424242),
                textAlign = TextAlign.Center
            )
        }
    }
}
