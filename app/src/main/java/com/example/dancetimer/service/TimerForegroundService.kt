package com.example.dancetimer.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.dancetimer.MainActivity
import com.example.dancetimer.R
import com.example.dancetimer.data.db.AppDatabase
import com.example.dancetimer.data.model.DanceRecord
import com.example.dancetimer.data.model.PriceTier
import com.example.dancetimer.data.preferences.UserPreferencesManager
import com.example.dancetimer.util.CostCalculator
import com.example.dancetimer.util.SilentAudioPlayer
import com.example.dancetimer.util.VibrationHelper
import com.example.dancetimer.widget.DanceTimerWidgetReceiver
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * 前台计时服务 — 持有 WakeLock + MediaSession
 * 
 * 两种工作模式：
 * 1. 待命模式(STANDBY)：低优先级通知 + MediaSession 拦截锁屏音量键
 * 2. 计时模式(RUNNING)：精确计时 + WakeLock + 计时通知
 * 
 * 音量键控制方案：
 * - 锁屏状态：MediaSession + VolumeProvider + 无声音频播放
 * - App前台：Activity onKeyDown/onKeyUp
 */
class TimerForegroundService : Service() {

    companion object {
        private const val TAG = "TimerFGService"
        private const val CHANNEL_ID_STANDBY = "dance_timer_standby"
        private const val CHANNEL_ID_RUNNING = "dance_timer_running"
        private const val NOTIFICATION_ID_STANDBY = 1001
        private const val NOTIFICATION_ID_RUNNING = 1002
        private const val LONG_PRESS_MS = 1500L // 长按阈值（毫秒）

        const val ACTION_START = "com.example.dancetimer.ACTION_START"
        const val ACTION_STOP = "com.example.dancetimer.ACTION_STOP"
        const val ACTION_PAUSE = "com.example.dancetimer.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.dancetimer.ACTION_RESUME"
        const val ACTION_STANDBY = "com.example.dancetimer.ACTION_STANDBY"
        const val ACTION_DISMISS = "com.example.dancetimer.ACTION_DISMISS"
        const val ACTION_AUTO_START = "com.example.dancetimer.ACTION_AUTO_START"
        const val ACTION_CANCEL_AUTO = "com.example.dancetimer.ACTION_CANCEL_AUTO"
        private const val ACTION_TICK = "com.example.dancetimer.ACTION_TICK"
        /** AlarmManager 唤醒间隔 — OEM 冻结进程时的保底刷新 */
        private const val ALARM_TICK_INTERVAL_MS = 30_000L

        /** 自动计时确认窗口（秒）— 在此期间内可快速取消误触发 */
        const val AUTO_START_CONFIRM_SECONDS = 15

        private val _timerState = MutableStateFlow<TimerState>(TimerState.Idle)
        val timerState: StateFlow<TimerState> = _timerState.asStateFlow()

        /** 待命服务是否正在运行 */
        @Volatile
        var isStandbyActive = false
            private set

        val isRunning: Boolean
            get() = _timerState.value is TimerState.Running

        fun resetToIdle() {
            _timerState.value = TimerState.Idle
        }

        /** 进入待命模式（App启动时调用） */
        fun enterStandby(context: Context) {
            val intent = Intent(context, TimerForegroundService::class.java).apply {
                action = ACTION_STANDBY
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startTimer(context: Context) {
            val intent = Intent(context, TimerForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopTimer(context: Context) {
            val intent = Intent(context, TimerForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun pauseTimer(context: Context) {
            val intent = Intent(context, TimerForegroundService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun resumeTimer(context: Context) {
            val intent = Intent(context, TimerForegroundService::class.java).apply {
                action = ACTION_RESUME
            }
            context.startService(intent)
        }

        /** 完全退出待命模式 */
        fun dismiss(context: Context) {
            val intent = Intent(context, TimerForegroundService::class.java).apply {
                action = ACTION_DISMISS
            }
            context.startService(intent)
        }

        /** 取消自动计时 */
        fun cancelAutoStart(context: Context) {
            val intent = Intent(context, TimerForegroundService::class.java).apply {
                action = ACTION_CANCEL_AUTO
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    // 计时机制：Handler 主循环(1s) + AlarmManager 备份唤醒(30s)
    private var tickRunnable: Runnable? = null
    private var alarmPendingIntent: PendingIntent? = null

    // MediaSession（锁屏音量键拦截）
    private var mediaSession: MediaSession? = null
    private val handler = Handler(Looper.getMainLooper())
    private var volumeUpLongPressRunnable: Runnable? = null
    private var volumeDownLongPressRunnable: Runnable? = null
    private var volumeUpTriggered = false
    private var volumeDownTriggered = false

    // 计时状态
    private var startElapsedRealtime: Long = 0L
    private var startWallClock: Long = 0L
    private var tiers: List<PriceTier> = emptyList()
    private var ruleName: String = ""
    private var ruleId: Long = 0L

    // 已经到达的最高歌曲索引（用于只在新歌开始时才震动）
    private var lastReachedSongIndex: Int = -1
    // 上次通知显示的状态（用于减少不必要的通知更新，时间由 Chronometer 自动更新）
    private var lastNotifiedCost: Float = -1f
    private var lastNotifiedSongCount: Int = -1
    private var lastNotifiedInGrace: Boolean = false
    private var lastNotifiedMinute: Int = -1
    // 暂停时累计的已过秒数
    private var pausedElapsedSeconds: Int = 0
    // 用于通知 Chronometer 的基准时间（恢复后调整）
    private var chronometerBase: Long = 0L
    // 自动计时标记
    private var isAutoStarted: Boolean = false

    // 息屏自动计时广播接收器
    private var screenOffReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STANDBY -> handleStandby()
            ACTION_START -> handleStart(isAuto = false)
            ACTION_AUTO_START -> handleStart(isAuto = true)
            ACTION_STOP -> handleStop()
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_DISMISS -> handleDismiss()
            ACTION_CANCEL_AUTO -> handleCancelAuto()
            ACTION_TICK -> handleAlarmTick()
        }
        return START_STICKY
    }

    // ===== 待命模式 =====

    private fun handleStandby() {
        if (isStandbyActive || _timerState.value is TimerState.Running) return
        Log.d(TAG, "进入待命模式")
        isStandbyActive = true
        startForeground(NOTIFICATION_ID_STANDBY, buildStandbyNotification())
        SilentAudioPlayer.start()
        setupMediaSession()
        resetMediaSessionForStandby()
        registerScreenOffReceiver()
    }

    private fun handleDismiss() {
        Log.d(TAG, "退出待命模式")
        unregisterScreenOffReceiver()
        releaseMediaSession()
        SilentAudioPlayer.stop()
        stopTicking()
        releaseWakeLock()
        isStandbyActive = false
        _timerState.value = TimerState.Idle
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ===== MediaSession 锁屏音量键拦截 =====

    private fun setupMediaSession() {
        if (mediaSession != null) return

        mediaSession = MediaSession(this, "DanceTimerSession").apply {
            // 设置 VolumeProvider：拦截音量键
            val vp = object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 15, 7) {
                override fun onAdjustVolume(direction: Int) {
                    Log.d(TAG, "MediaSession onAdjustVolume: direction=$direction")
                    handleVolumeFromMediaSession(direction)
                }
            }
            setPlaybackToRemote(vp)

            // 设置回调（空实现，保持 session 活跃）
            setCallback(object : MediaSession.Callback() {})

            // 设置 PlaybackState 为 PLAYING 让系统路由音量键到我们的 VolumeProvider
            val state = PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                .build()
            setPlaybackState(state)

            isActive = true
        }
        Log.d(TAG, "MediaSession 已创建并激活")
    }

    private fun releaseMediaSession() {
        mediaSession?.let {
            it.isActive = false
            it.release()
        }
        mediaSession = null
    }

    /**
     * 更新 MediaSession 元数据 — 在锁屏/状态胶囊上显示计时信息
     */
    private fun updateMediaSessionForRunning(cost: Float) {
        mediaSession?.let { session ->
            val costStr = CostCalculator.formatCost(cost)
            val elapsed = ((SystemClock.elapsedRealtime() - startElapsedRealtime) / 1000).toInt()
            val songCount = CostCalculator.getSongCount(elapsed, tiers)
            val songPart = if (songCount > 0) "已计${songCount}曲 · " else "未满1曲 · "
            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "$songPart$costStr")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, ruleName)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, -1L)
                .build()
            session.setMetadata(metadata)

            // 设置播放位置，系统自动计算已播放时长
            val state = PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
                .setActions(PlaybackState.ACTION_STOP)
                .build()
            session.setPlaybackState(state)
        }
    }

    /**
     * 重置 MediaSession 元数据为待命状态
     */
    private fun resetMediaSessionForStandby() {
        mediaSession?.let { session ->
            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "DanceTimer")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "长按音量键控制计时")
                .build()
            session.setMetadata(metadata)

            val state = PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .setActions(PlaybackState.ACTION_PLAY_PAUSE)
                .build()
            session.setPlaybackState(state)
        }
    }

    /**
     * MediaSession 音量键处理（锁屏场景）
     * 使用长按检测：与息屏/前台操作完全一致
     * direction > 0 = 音量+按下， direction < 0 = 音量-按下
     * direction = 0 = 松开
     */
    private fun handleVolumeFromMediaSession(direction: Int) {
        when {
            direction > 0 -> { // 音量+ 按下
                if (volumeUpLongPressRunnable == null) {
                    volumeUpTriggered = false
                    Log.d(TAG, "锁屏音量+ 按下，启动${LONG_PRESS_MS}ms长按检测")
                    volumeUpLongPressRunnable = Runnable {
                        if (!volumeUpTriggered) {
                            volumeUpTriggered = true
                            Log.d(TAG, "锁屏音量+ 长按触发！")
                            val current = _timerState.value
                            if (!isRunning) {
                                handleStart()
                            } else if (current is TimerState.Running && current.isPaused) {
                                handleResume()
                            }
                        }
                    }
                    handler.postDelayed(volumeUpLongPressRunnable!!, LONG_PRESS_MS)
                }
            }
            direction < 0 -> { // 音量- 按下
                if (volumeDownLongPressRunnable == null) {
                    volumeDownTriggered = false
                    Log.d(TAG, "锁屏音量- 按下，启动${LONG_PRESS_MS}ms长按检测")
                    volumeDownLongPressRunnable = Runnable {
                        if (!volumeDownTriggered) {
                            volumeDownTriggered = true
                            Log.d(TAG, "锁屏音量- 长按触发！")
                            if (isRunning) {
                                handleStop()
                            }
                        }
                    }
                    handler.postDelayed(volumeDownLongPressRunnable!!, LONG_PRESS_MS)
                }
            }
            else -> { // direction == 0，松开
                // 取消未触发的长按
                volumeUpLongPressRunnable?.let {
                    handler.removeCallbacks(it)
                    val triggered = volumeUpTriggered
                    Log.d(TAG, "锁屏音量+ 松开，已触发=$triggered")
                }
                volumeUpLongPressRunnable = null
                
                volumeDownLongPressRunnable?.let {
                    handler.removeCallbacks(it)
                    val triggered = volumeDownTriggered
                    Log.d(TAG, "锁屏音量- 松开，已触发=$triggered")
                }
                volumeDownLongPressRunnable = null
            }
        }
    }

    // ===== 启动计时 =====

    private fun handleStart(isAuto: Boolean = false) {
        if (_timerState.value is TimerState.Running) return

        isAutoStarted = isAuto
        if (isAuto) {
            Log.d(TAG, "息屏自动计时启动")
        }

        serviceScope.launch {
            // 从数据库加载默认计价规则
            val db = AppDatabase.getInstance(applicationContext)
            val ruleWithTiers = db.pricingRuleDao().getDefaultRuleWithTiers()
            if (ruleWithTiers == null) {
                // 没有配置任何规则，仍然允许计时（费用显示0）
                tiers = emptyList()
                ruleName = "未配置规则"
                ruleId = 0L
            } else {
                tiers = ruleWithTiers.sortedTiers
                ruleName = ruleWithTiers.rule.name
                ruleId = ruleWithTiers.rule.id
            }

            startElapsedRealtime = SystemClock.elapsedRealtime()
            startWallClock = System.currentTimeMillis()
            chronometerBase = startWallClock
            lastReachedSongIndex = -1
            lastNotifiedCost = 0f
            lastNotifiedSongCount = -1
            lastNotifiedInGrace = false
            lastNotifiedMinute = -1
            pausedElapsedSeconds = 0

            // 获取 WakeLock
            acquireWakeLock()

            val initCost = CostCalculator.calculate(0, tiers)
            val initSongCount = CostCalculator.getSongCount(0, tiers)

            // 切换前台通知：先移除待命通知，再启动计时通知
            // 使用不同 NOTIFICATION_ID 避免 OEM（OPPO/ColorOS/MIUI）渠道切换失败
            val nm = getSystemService(NotificationManager::class.java)
            nm.cancel(NOTIFICATION_ID_STANDBY)
            Log.d(TAG, "待命通知已取消(ID=$NOTIFICATION_ID_STANDBY), 准备发送计时通知(ID=$NOTIFICATION_ID_RUNNING)")
            startForeground(NOTIFICATION_ID_RUNNING, buildRunningNotification(0, initCost, initSongCount, isAutoStarted = isAuto))
            Log.d(TAG, "计时通知已发送, channel=$CHANNEL_ID_RUNNING")

            // 更新 MediaSession 元数据（锁屏/状态胶囊显示计时信息）
            updateMediaSessionForRunning(initCost)

            // 震动反馈：开始
            VibrationHelper.vibrateFeedback(applicationContext)

            // 更新状态
            _timerState.value = TimerState.Running(
                elapsedSeconds = 0,
                currentSongIndex = 0,
                cost = initCost,
                songCount = initSongCount,
                startTimeMillis = startWallClock,
                tiers = tiers,
                ruleName = ruleName,
                ruleId = ruleId,
                isPaused = false,
                isInGracePeriod = false,
                isAutoStarted = isAuto
            )

            // 启动计时机制 — Handler 主循环 + AlarmManager 备份唤醒
            startTicking()
        }
    }

    /**
     * 启动双重计时机制：
     * 1. Handler.postDelayed 每秒 tick（进程活跃时流畅更新）
     * 2. AlarmManager.setAndAllowWhileIdle 每30秒唤醒（进程被 OEM 冻结时强制解冻）
     *
     * 为何需要 AlarmManager：
     * OPPO/ColorOS 会冻结前台服务进程的所有线程（包括 Thread.sleep、coroutine delay、Handler），
     * 但 AlarmManager 由 system_server 管理，触发时系统必须解冻进程来投递 Intent。
     */
    private fun startTicking() {
        stopTicking()

        // Handler 主循环 — 每秒 tick
        tickRunnable = object : Runnable {
            override fun run() {
                val current = _timerState.value
                if (current is TimerState.Running && !current.isPaused) {
                    tick()
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.postDelayed(tickRunnable!!, 1000)

        // AlarmManager 备份唤醒
        alarmPendingIntent = PendingIntent.getService(
            this, 100,
            Intent(this, TimerForegroundService::class.java).apply { action = ACTION_TICK },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        scheduleAlarmTick()
        Log.d(TAG, "计时机制已启动 (Handler + AlarmManager)")
    }

    private fun stopTicking() {
        tickRunnable?.let { handler.removeCallbacks(it) }
        tickRunnable = null
        alarmPendingIntent?.let {
            (getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(it)
        }
        alarmPendingIntent = null
    }

    /** 安排下一个 AlarmManager 唤醒 */
    private fun scheduleAlarmTick() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmPendingIntent?.let {
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + ALARM_TICK_INTERVAL_MS,
                it
            )
        }
    }

    /** AlarmManager 唤醒时调用 — 执行 tick 并重新安排下一个闹钟 */
    private fun handleAlarmTick() {
        val current = _timerState.value
        if (current is TimerState.Running && !current.isPaused) {
            Log.d(TAG, "AlarmManager 唤醒 tick")
            tick()
            scheduleAlarmTick()
            // 重新启动 Handler 循环（可能因冻结而停止）
            tickRunnable?.let {
                handler.removeCallbacks(it)
                handler.postDelayed(it, 1000)
            }
        }
    }

    /**
     * 每秒/每次唤醒 tick — 主线程执行
     * 计算基于 SystemClock.elapsedRealtime()，即使漏掉中间 tick 也不影响准确性
     */
    private fun tick() {
        val elapsed = ((SystemClock.elapsedRealtime() - startElapsedRealtime) / 1000).toInt()
        val songIndex = CostCalculator.getCurrentSongIndex(elapsed, tiers)
        val cost = CostCalculator.calculate(elapsed, tiers)
        val songCount = CostCalculator.getSongCount(elapsed, tiers)
        val inGrace = CostCalculator.isInGracePeriod(elapsed, tiers)

        // 检查是否进入新歌 → 震动提醒
        if (songIndex > lastReachedSongIndex && songIndex > 0) {
            serviceScope.launch {
                val prefs = UserPreferencesManager(applicationContext)
                val shouldVibrate = prefs.vibrateOnTier.first()
                if (shouldVibrate) {
                    VibrationHelper.vibrateFeedback(applicationContext)
                }
            }
            lastReachedSongIndex = songIndex
        }

        // 更新状态
        _timerState.value = TimerState.Running(
            elapsedSeconds = elapsed,
            currentSongIndex = songIndex,
            cost = cost,
            songCount = songCount,
            startTimeMillis = startWallClock,
            tiers = tiers,
            ruleName = ruleName,
            ruleId = ruleId,
            isPaused = false,
            isInGracePeriod = inGrace,
            isAutoStarted = isAutoStarted
        )

        // 自动计时确认窗口结束后，清除自动标记
        if (isAutoStarted && elapsed >= AUTO_START_CONFIRM_SECONDS) {
            isAutoStarted = false
        }

        // 更新通知
        val costChanged = cost != lastNotifiedCost
        val currentMinute = elapsed / 60
        val minuteChanged = currentMinute != lastNotifiedMinute
        val needsNotificationUpdate = costChanged
                || songCount != lastNotifiedSongCount
                || inGrace != lastNotifiedInGrace
                || minuteChanged
                || inGrace
        if (needsNotificationUpdate) {
            val graceRemaining = if (inGrace) CostCalculator.getGraceRemainingSeconds(elapsed, tiers) else 0
            val notification = buildRunningNotification(elapsed, cost, songCount, inGrace, graceRemaining)
            startForeground(NOTIFICATION_ID_RUNNING, notification)
            Log.d(TAG, "通知已更新: elapsed=${elapsed}s, min=$currentMinute, 已计${songCount}曲, cost=$cost")
            lastNotifiedCost = cost
            lastNotifiedSongCount = songCount
            lastNotifiedInGrace = inGrace
            lastNotifiedMinute = currentMinute
        }

        // 更新 MediaSession
        if (costChanged || minuteChanged) {
            updateMediaSessionForRunning(cost)
        }

        // 更新桌面 Widget
        DanceTimerWidgetReceiver.requestUpdate(applicationContext)
    }

    // ===== 停止计时 =====

    private fun handleStop() {
        if (_timerState.value !is TimerState.Running) {
            stopSelf()
            return
        }

        stopTicking()
        isAutoStarted = false

        val endWallClock = System.currentTimeMillis()
        val elapsed = ((SystemClock.elapsedRealtime() - startElapsedRealtime) / 1000).toInt()
        val cost = CostCalculator.calculate(elapsed, tiers)
        val songCount = CostCalculator.getSongCount(elapsed, tiers)
        val isGraceApplied = CostCalculator.isInGracePeriod(elapsed, tiers)
        val savedAmount = CostCalculator.getGraceSavedAmount(elapsed, tiers)

        // 震动反馈：停止
        VibrationHelper.vibrateFeedback(applicationContext)

        // 设置完成状态
        _timerState.value = TimerState.Finished(
            durationSeconds = elapsed,
            cost = cost,
            songCount = songCount,
            ruleName = ruleName,
            ruleId = ruleId,
            startTimeMillis = startWallClock,
            endTimeMillis = endWallClock,
            isGraceApplied = isGraceApplied,
            savedAmount = savedAmount
        )

        // 保存历史记录（存储停止缓冲调整后的费用）
        serviceScope.launch {
            val record = DanceRecord(
                startTime = startWallClock,
                endTime = endWallClock,
                durationSeconds = elapsed,
                cost = cost,
                pricingRuleName = ruleName,
                pricingRuleId = ruleId
            )
            AppDatabase.getInstance(applicationContext).danceRecordDao().insert(record)
        }

        // 更新桌面 Widget
        DanceTimerWidgetReceiver.requestUpdate(applicationContext)

        // 释放 WakeLock
        releaseWakeLock()

        // 重置通知状态
        lastNotifiedCost = -1f
        lastNotifiedSongCount = -1
        lastNotifiedInGrace = false
        lastNotifiedMinute = -1

        // 如果处于待命模式，回到待命通知；否则彻底停止
        if (isStandbyActive) {
            Log.d(TAG, "计时结束，回到待命模式")
            // 切换回待命通知：先移除计时通知，再显示待命通知
            val nm = getSystemService(NotificationManager::class.java)
            nm.cancel(NOTIFICATION_ID_RUNNING)
            startForeground(NOTIFICATION_ID_STANDBY, buildStandbyNotification())
            resetMediaSessionForStandby()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // ===== 取消自动计时 =====

    private fun handleCancelAuto() {
        val current = _timerState.value
        if (current !is TimerState.Running || !current.isAutoStarted) return

        Log.d(TAG, "取消自动计时（误触发）")
        stopTicking()
        isAutoStarted = false

        // 震动反馈：取消
        VibrationHelper.vibrateFeedback(applicationContext)

        // 不保存记录，直接回到 Idle
        _timerState.value = TimerState.Idle

        // 释放 WakeLock
        releaseWakeLock()
        lastNotifiedCost = -1f
        lastNotifiedSongCount = -1
        lastNotifiedInGrace = false
        lastNotifiedMinute = -1

        // 更新桌面 Widget
        DanceTimerWidgetReceiver.requestUpdate(applicationContext)

        // 回到待命通知
        if (isStandbyActive) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.cancel(NOTIFICATION_ID_RUNNING)
            startForeground(NOTIFICATION_ID_STANDBY, buildStandbyNotification())
            resetMediaSessionForStandby()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopTicking()
        unregisterScreenOffReceiver()
        releaseMediaSession()
        SilentAudioPlayer.stop()
        serviceScope.cancel()
        releaseWakeLock()
        isStandbyActive = false
        super.onDestroy()
    }

    // ===== 暂停/恢复 =====

    private fun handlePause() {
        val current = _timerState.value
        if (current !is TimerState.Running || current.isPaused) return

        Log.d(TAG, "暂停计时")
        stopTicking()

        pausedElapsedSeconds = ((SystemClock.elapsedRealtime() - startElapsedRealtime) / 1000).toInt()

        releaseWakeLock()

        _timerState.value = current.copy(isPaused = true)

        // 更新通知为暂停状态（使用 startForeground 确保 OEM ROM 刷新）
        val songCount = CostCalculator.getSongCount(pausedElapsedSeconds, tiers)
        startForeground(NOTIFICATION_ID_RUNNING, buildRunningNotification(pausedElapsedSeconds, current.cost, songCount, isPaused = true))
        updateMediaSessionForPaused(current.cost)

        VibrationHelper.vibrateFeedback(applicationContext)
    }

    private fun handleResume() {
        val current = _timerState.value
        if (current !is TimerState.Running || !current.isPaused) return

        Log.d(TAG, "恢复计时")
        startElapsedRealtime = SystemClock.elapsedRealtime() - (pausedElapsedSeconds * 1000L)
        chronometerBase = System.currentTimeMillis() - (pausedElapsedSeconds * 1000L)

        acquireWakeLock()

        _timerState.value = current.copy(isPaused = false)

        // 更新通知为计时状态（使用 startForeground 确保 OEM ROM 刷新）
        val songCount = CostCalculator.getSongCount(pausedElapsedSeconds, tiers)
        startForeground(NOTIFICATION_ID_RUNNING, buildRunningNotification(pausedElapsedSeconds, current.cost, songCount))
        updateMediaSessionForRunning(current.cost)

        VibrationHelper.vibrateFeedback(applicationContext)

        // 重启计时机制
        startTicking()
    }

    /**
     * 更新 MediaSession 为暂停状态
     */
    private fun updateMediaSessionForPaused(cost: Float) {
        mediaSession?.let { session ->
            val costStr = CostCalculator.formatCost(cost)
            val songCount = CostCalculator.getSongCount(pausedElapsedSeconds, tiers)
            val songPart = if (songCount > 0) "已计${songCount}曲 · " else "未满1曲 · "
            val metadata = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "⏸ $songPart$costStr")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, ruleName)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, -1L)
                .build()
            session.setMetadata(metadata)

            val state = PlaybackState.Builder()
                .setState(PlaybackState.STATE_PAUSED, 0L, 0f)
                .setActions(PlaybackState.ACTION_PLAY)
                .build()
            session.setPlaybackState(state)
        }
    }

    // ===== 通知 =====

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            // 清理旧版通知渠道
            nm.deleteNotificationChannel("dance_timer_channel")

            // 待命通知渠道 — 低优先级，静默
            val standbyChannel = NotificationChannel(
                CHANNEL_ID_STANDBY,
                "计时器待命",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "待命模式常驻通知（静默）"
                setShowBadge(false)
            }
            nm.createNotificationChannel(standbyChannel)

            // 计时通知渠道 — 默认优先级，锁屏可见，无声音
            val runningChannel = NotificationChannel(
                CHANNEL_ID_RUNNING,
                "计时中",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "计时中的通知（锁屏可见，状态胶囊）"
                setShowBadge(true)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(runningChannel)
        }
    }

    private fun buildStandbyNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val dismissIntent = PendingIntent.getService(
            this, 2,
            Intent(this, TimerForegroundService::class.java).apply {
                action = ACTION_DISMISS
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID_STANDBY)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("计时器待命中 (保持通知开启)")
            .setContentText("长按音量+ 开始 · 长按音量- 停止")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("长按音量+ 开始 · 长按音量- 停止。\n请勿关闭本通知，否则锁屏后将无法通过音量键控制。"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .addAction(0, "退出", dismissIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * 构建计时中通知 — 精简内容，避免信息重复
     *
     * 布局：Chronometer 自动显示时间 | Title 显示曲数+费用 | SubText 显示规则名
     * 停止缓冲时 ContentText 显示倒计时
     */
    private fun buildRunningNotification(
        elapsedSeconds: Int,
        cost: Float,
        songCount: Int = 0,
        isInGrace: Boolean = false,
        graceRemaining: Int = 0,
        isPaused: Boolean = false,
        isAutoStarted: Boolean = this.isAutoStarted
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TimerForegroundService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pauseResumeIntent = PendingIntent.getService(
            this, 3,
            Intent(this, TimerForegroundService::class.java).apply {
                action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelAutoIntent = PendingIntent.getService(
            this, 4,
            Intent(this, TimerForegroundService::class.java).apply {
                action = ACTION_CANCEL_AUTO
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val costStr = CostCalculator.formatCost(cost)
        val songPart = if (songCount > 0) "已计${songCount}曲" else "未满1曲"
        val totalMinutes = elapsedSeconds / 60
        // 标题：分钟优先 · 曲数 · 费用（时间由 Chronometer 系统渲染，不受进程冻结影响）
        val title = when {
            isAutoStarted -> "自动 | ${totalMinutes}分钟 · $songPart · $costStr"
            isPaused -> "已暂停 | ${totalMinutes}分钟 · $songPart · $costStr"
            else -> "${totalMinutes}分钟 · $songPart · $costStr"
        }
        val contentText = if (isInGrace) "🛡️ 宽限 ${graceRemaining}s" else null
        // Chronometer 基准：SystemUI 渲染，进程冻结也能正常走秒
        val chronometerBase = System.currentTimeMillis() - elapsedSeconds * 1000L

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID_RUNNING)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        if (isAutoStarted) {
            val cancelAction = Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stop_notification),
                "取消",
                cancelAutoIntent
            ).build()
            val stopAction = Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stop_notification),
                "停止",
                stopIntent
            ).build()
            builder.setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setSubText("息屏触发 · 误触请取消")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_STOPWATCH)
                .setUsesChronometer(true)
                .setWhen(chronometerBase)
                .setShowWhen(true)
                .setContentIntent(contentIntent)
                .addAction(cancelAction)
                .addAction(stopAction)
                .setColor(0xFFE65100.toInt())
        } else if (isPaused) {
            val resumeAction = Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_play_notification),
                "继续",
                pauseResumeIntent
            ).build()
            val stopAction = Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stop_notification),
                "停止",
                stopIntent
            ).build()
            builder.setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setSubText(ruleName)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_STOPWATCH)
                .setUsesChronometer(false)
                .setShowWhen(false)
                .setContentIntent(contentIntent)
                .addAction(resumeAction)
                .addAction(stopAction)
                .setColor(0xFF455A64.toInt())
        } else {
            val pauseAction = Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_pause_notification),
                "暂停",
                pauseResumeIntent
            ).build()
            val stopAction = Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stop_notification),
                "停止",
                stopIntent
            ).build()
            builder.setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setSubText(ruleName)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_STOPWATCH)
                .setUsesChronometer(true)
                .setWhen(chronometerBase)
                .setShowWhen(true)
                .setContentIntent(contentIntent)
                .addAction(pauseAction)
                .addAction(stopAction)
                .setColor(0xFF6750A4.toInt())
        }

        // 停止缓冲倒计时
        if (contentText != null) {
            builder.setContentText(contentText)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setColorized(true)
        }

        return builder.build()
    }

    // ===== WakeLock =====

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "DanceTimer::TimerWakeLock"
        ).apply {
            acquire(60 * 60 * 1000L) // 最长 1 小时超时保护
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    // ===== 息屏自动计时 =====

    private fun registerScreenOffReceiver() {
        if (screenOffReceiver != null) return
        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_OFF) {
                    handleScreenOff()
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenOffReceiver, filter)
        }
        Log.d(TAG, "已注册息屏广播接收器")
    }

    private fun unregisterScreenOffReceiver() {
        screenOffReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) { }
        }
        screenOffReceiver = null
    }

    private fun handleScreenOff() {
        // 仅在待命+空闲状态下触发
        if (!isStandbyActive || _timerState.value is TimerState.Running) return

        serviceScope.launch {
            val prefs = UserPreferencesManager(applicationContext)
            val autoStartEnabled = prefs.autoStartOnScreenOff.first()
            if (!autoStartEnabled) return@launch

            Log.d(TAG, "息屏检测: 自动启动计时")
            handleStart(isAuto = true)
        }
    }
}
