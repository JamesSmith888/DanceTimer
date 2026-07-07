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
import android.os.VibrationEffect
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.dancetimer.LockScreenTimerActivity
import com.example.dancetimer.MainActivity
import com.example.dancetimer.R
import com.example.dancetimer.data.db.AppDatabase
import com.example.dancetimer.data.model.DanceRecord
import com.example.dancetimer.data.model.PriceTier
import com.example.dancetimer.data.model.ScreenLockEvent
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
        private const val CHANNEL_ID_ALERT = "dance_timer_alert"
        private const val CHANNEL_ID_BILLING = "dance_timer_billing_peek"
        private const val NOTIFICATION_ID_STANDBY = 1001
        private const val NOTIFICATION_ID_RUNNING = 1002
        private const val NOTIFICATION_ID_ALERT = 1003
        private const val NOTIFICATION_ID_BILLING_PEEK = 1004
        private const val LONG_PRESS_MS = 1500L // 长按阈值（毫秒）

        const val ACTION_START = "com.example.dancetimer.ACTION_START"
        const val ACTION_STOP = "com.example.dancetimer.ACTION_STOP"
        const val ACTION_PAUSE = "com.example.dancetimer.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.dancetimer.ACTION_RESUME"
        const val ACTION_STANDBY = "com.example.dancetimer.ACTION_STANDBY"
        const val ACTION_DISMISS = "com.example.dancetimer.ACTION_DISMISS"
        private const val ACTION_TICK = "com.example.dancetimer.ACTION_TICK"
        const val ACTION_START_FROM_LOCK_EVENT = "com.example.dancetimer.ACTION_START_FROM_LOCK_EVENT"
        const val EXTRA_LOCK_EVENT_TIMESTAMP = "lock_event_timestamp"
        const val EXTRA_LOCK_EVENT_ELAPSED_REALTIME = "lock_event_elapsed_realtime"
        /** AlarmManager 唤醒间隔 — OEM 冻结进程时的保底刷新 */
        private const val ALARM_TICK_INTERVAL_MS = 30_000L
        /** 锁屏事件通知更新间隔（毫秒）— 待机通知每 60 秒刷新一次最近锁屏信息 */
        private const val LOCK_EVENT_NOTIFICATION_INTERVAL_MS = 60_000L

        private val _timerState = MutableStateFlow<TimerState>(TimerState.Idle)
        val timerState: StateFlow<TimerState> = _timerState.asStateFlow()

        /** 待命服务是否正在运行 */
        @Volatile
        var isStandbyActive = false
            private set

        /** 最近锁屏事件 — 供 UI 层轻量订阅，在服务内更新 */
        private val _latestLockEvent = MutableStateFlow<ScreenLockEvent?>(null)
        val latestLockEvent: StateFlow<ScreenLockEvent?> = _latestLockEvent.asStateFlow()

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

        /** 从锁屏事件回溯启动计时 */
        fun startFromLockEvent(context: Context, event: ScreenLockEvent) {
            val intent = Intent(context, TimerForegroundService::class.java).apply {
                action = ACTION_START_FROM_LOCK_EVENT
                putExtra(EXTRA_LOCK_EVENT_TIMESTAMP, event.timestamp)
                putExtra(EXTRA_LOCK_EVENT_ELAPSED_REALTIME, event.elapsedRealtime)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 以锁屏事件直接生成计费结果（跳过计时中状态，直接进入 Finished）。
         * 在调用方的 coroutineScope 中执行数据库操作。
         */
        suspend fun finishFromLockEvent(context: Context, event: ScreenLockEvent) {
            val db = AppDatabase.getInstance(context)
            val ruleWithTiers = db.pricingRuleDao().getDefaultRuleWithTiers()
            val tiers = ruleWithTiers?.sortedTiers ?: emptyList()
            val ruleName = ruleWithTiers?.rule?.name ?: "未配置规则"
            val ruleId = ruleWithTiers?.rule?.id ?: 0L

            val now = System.currentTimeMillis()
            val elapsed = ((now - event.timestamp) / 1000).toInt().coerceAtLeast(0)
            val cost = CostCalculator.calculate(elapsed, tiers)
            val songCount = CostCalculator.getSongCount(elapsed, tiers)
            val isGraceApplied = CostCalculator.isInGracePeriod(elapsed, tiers)
            val savedAmount = CostCalculator.getGraceSavedAmount(elapsed, tiers)

            // 保存历史记录
            val record = DanceRecord(
                startTime = event.timestamp,
                endTime = now,
                durationSeconds = elapsed,
                cost = cost,
                pricingRuleName = ruleName,
                pricingRuleId = ruleId,
                triggerType = DanceRecord.TRIGGER_LOCK_EVENT,
                autoStartResult = null,
                screenOffDelaySeconds = 0
            )
            db.danceRecordDao().insert(record)

            // 直接设置 Finished 状态
            _timerState.value = TimerState.Finished(
                durationSeconds = elapsed,
                cost = cost,
                songCount = songCount,
                ruleName = ruleName,
                ruleId = ruleId,
                startTimeMillis = event.timestamp,
                endTimeMillis = now,
                isGraceApplied = isGraceApplied,
                savedAmount = savedAmount
            )
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
    // 上次触发锁屏亮屏的曲数（独立追踪，初始 0 使第一次计费即可触发）
    private var lastBilledSongCountForLockScreen: Int = 0
    // 暂停时累计的已过秒数
    private var pausedElapsedSeconds: Int = 0
    // 用于通知 Chronometer 的基准时间（恢复后调整）
    private var chronometerBase: Long = 0L

    // 触发来源追踪（用于保存历史记录元数据）
    private var startTriggerType: String = DanceRecord.TRIGGER_MANUAL

    // 锁屏事件通知更新定时任务
    private var lockEventNotificationRunnable: Runnable? = null

    /**
     * 息屏广播接收器 — 仅负责记录锁屏事件，与计时逻辑完全解耦。
     * 历史教训：原实现将事件记录混入了"自动计时"逻辑（handleScreenOff），
     * 当自动计时功能被删除时，记录功能也被误删。
     * 正确做法：保持单一职责，此接收器只做记录。
     */
    private var screenOffReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // 注册息屏接收器（仅记录锁屏事件，不涉及任何计时逻辑）
        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != Intent.ACTION_SCREEN_OFF) return
                recordScreenLockEvent()
                // 待命模式下立即重启通知更新器，使通知在 5 秒内反映最新锁屏时间
                if (isStandbyActive && _timerState.value !is TimerState.Running) {
                    startLockEventNotificationUpdater()
                }
            }
        }
        val screenOffFilter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, screenOffFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenOffReceiver, screenOffFilter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STANDBY -> handleStandby()
            ACTION_START -> handleStart()
            ACTION_START_FROM_LOCK_EVENT -> handleStartFromLockEvent(intent)
            ACTION_STOP -> handleStop()
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_DISMISS -> handleDismiss()
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
        startLockEventNotificationUpdater()
    }

    private fun handleDismiss() {
        Log.d(TAG, "退出待命模式")
        stopLockEventNotificationUpdater()
        releaseMediaSession()
        SilentAudioPlayer.stop()
        stopTicking()
        releaseWakeLock()
        isStandbyActive = false
        startTriggerType = DanceRecord.TRIGGER_MANUAL
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

    private fun handleStart() {
        if (_timerState.value is TimerState.Running) return

        startTriggerType = DanceRecord.TRIGGER_MANUAL

        serviceScope.launch {
            // 从数据库加载默认计价规则
            val db = AppDatabase.getInstance(applicationContext)
            val ruleWithTiers = db.pricingRuleDao().getDefaultRuleWithTiers()
            if (ruleWithTiers == null) {
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

            lastReachedSongIndex = CostCalculator.getCurrentSongIndex(0, tiers)
            lastNotifiedCost = 0f
            lastNotifiedSongCount = -1
            lastNotifiedInGrace = false
            lastNotifiedMinute = -1
            lastBilledSongCountForLockScreen = CostCalculator.getSongCount(0, tiers)
            pausedElapsedSeconds = 0

            acquireWakeLock()

            val initCost = CostCalculator.calculate(0, tiers)
            val initSongCount = CostCalculator.getSongCount(0, tiers)
            val initSongIndex = CostCalculator.getCurrentSongIndex(0, tiers)

            val nm = getSystemService(NotificationManager::class.java)
            nm.cancel(NOTIFICATION_ID_STANDBY)
            startForeground(NOTIFICATION_ID_RUNNING, buildRunningNotification(0, initCost, initSongCount))
            Log.d(TAG, "计时通知已发送, channel=$CHANNEL_ID_RUNNING")

            updateMediaSessionForRunning(initCost)
            VibrationHelper.vibrateFeedback(applicationContext)

            _timerState.value = TimerState.Running(
                elapsedSeconds = 0,
                currentSongIndex = initSongIndex,
                cost = initCost,
                songCount = initSongCount,
                startTimeMillis = startWallClock,
                tiers = tiers,
                ruleName = ruleName,
                ruleId = ruleId,
                isPaused = false,
                isInGracePeriod = false
            )

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

        // 检查是否进入新歌 → 震动提醒 + 锁屏亮屏提醒
        // songCount is billing-based (triggers at song midpoint), matching when cost increments.
        if (songCount > lastBilledSongCountForLockScreen) {
            Log.d(TAG, "New song billed: songCount=$songCount lastBilled=$lastBilledSongCountForLockScreen cost=$cost elapsed=${elapsed}s")
            serviceScope.launch {
                val prefs = UserPreferencesManager(applicationContext)
                val shouldVibrate = prefs.vibrateOnTier.first()
                if (shouldVibrate) {
                    VibrationHelper.vibrateFeedback(applicationContext)
                }
            }
            postBillingPeekNotification(songCount, cost, elapsed)
            // Wake screen and show full-screen lock screen overlay
            startLockScreenActivity(songCount, cost, elapsed)
            lastBilledSongCountForLockScreen = songCount
        }

        // Keep lastReachedSongIndex in sync
        if (songIndex > lastReachedSongIndex) {
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
            isInGracePeriod = inGrace
        )

        // 更新通知
        val costChanged = cost != lastNotifiedCost
        val currentMinute = elapsed / 60
        val minuteChanged = currentMinute != lastNotifiedMinute
        // During the first minute, update every second so the seconds display stays live.
        // After the first minute, update only when the displayed content changes.
        val inFirstMinute = currentMinute == 0
        val needsNotificationUpdate = costChanged
                || songCount != lastNotifiedSongCount
                || inGrace != lastNotifiedInGrace
                || minuteChanged
                || inGrace
                || inFirstMinute
        if (needsNotificationUpdate) {
            val graceRemaining = if (inGrace) CostCalculator.getGraceRemainingSeconds(elapsed, tiers) else 0
            val notification = buildRunningNotification(elapsed, cost, songCount, inGrace, graceRemaining)
            startForeground(NOTIFICATION_ID_RUNNING, notification)
            Log.d(TAG, "Notification updated: elapsed=${elapsed}s min=$currentMinute songs=$songCount cost=$cost inGrace=$inGrace")
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
                pricingRuleId = ruleId,
                triggerType = startTriggerType
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
            startLockEventNotificationUpdater()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        screenOffReceiver?.let { unregisterReceiver(it) }
        screenOffReceiver = null
        stopTicking()
        stopLockEventNotificationUpdater()
        releaseMediaSession()
        SilentAudioPlayer.stop()
        serviceScope.cancel()
        releaseWakeLock()
        isStandbyActive = false
        startTriggerType = DanceRecord.TRIGGER_MANUAL
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

            // Log overall notification permission state
            val areNotificationsEnabled = nm.areNotificationsEnabled()
            Log.d(TAG, "createNotificationChannel: areNotificationsEnabled=$areNotificationsEnabled")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Log.d(TAG, "createNotificationChannel: canUseFullScreenIntent=${nm.canUseFullScreenIntent()}")
            }

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

            // 自动计时提醒渠道 — 高优先级，弹头通知 + 默认声音
            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERT,
                "自动计时提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "息屏自动计时启动时的弹头提醒"
                setShowBadge(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 200, 300)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(alertChannel)

            // 计费提醒渠道 — 高优先级，无声无震动（震动由 VibrationHelper 负责），短暂出现后自动消失
            val billingChannel = NotificationChannel(
                CHANNEL_ID_BILLING,
                "计费提醒",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "新曲计费时的锁屏唤醒提醒（无声无震动，仅亮屏）"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(billingChannel)

            // Log each channel's actual importance and lock screen visibility as registered by system
            listOf(CHANNEL_ID_STANDBY, CHANNEL_ID_RUNNING, CHANNEL_ID_ALERT, CHANNEL_ID_BILLING).forEach { id ->
                val ch = nm.getNotificationChannel(id)
                if (ch != null) {
                    Log.d(TAG, "channel[$id]: importance=${ch.importance} lockscreen=${ch.lockscreenVisibility} blocked=${ch.importance == NotificationManager.IMPORTANCE_NONE}")
                } else {
                    Log.w(TAG, "channel[$id]: NOT FOUND after creation")
                }
            }
        }
    }

    private fun buildStandbyNotification(contentText: String? = null): Notification {
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

        val displayText = contentText ?: "长按音量+ 开始  ·  长按音量- 停止"
        val expandedText = contentText
            ?: "长按音量+ 开始  ·  长按音量- 停止\n请勿关闭本通知，否则锁屏后将无法通过音量键控制。"
        val largeIconBitmap = android.graphics.BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        return NotificationCompat.Builder(this, CHANNEL_ID_STANDBY)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setLargeIcon(largeIconBitmap)
            .setContentTitle("计时器待命")
            .setSubText("保持通知开启")
            .setContentText(displayText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(expandedText))
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
        isPaused: Boolean = false
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

        val costStr = CostCalculator.formatCost(cost)
        val songPart = if (songCount > 0) "已计${songCount}曲" else "未满1曲"
        val totalMinutes = elapsedSeconds / 60
        val timeDisplay = if (totalMinutes > 0) "${totalMinutes}分钟" else "${elapsedSeconds}秒"
        val title = when {
            isPaused -> "已暂停"
            else -> "计时中"
        }
        val contentText = when {
            isInGrace -> "缓冲 ${graceRemaining}s  ·  $timeDisplay  ·  $songPart  ·  $costStr"
            isPaused -> "$timeDisplay  ·  $songPart  ·  $costStr  ·  长按音量+ 继续"
            else -> "$timeDisplay  ·  $songPart  ·  $costStr"
        }

        val largeIcon = android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher)
        val chronometerBase = System.currentTimeMillis() - elapsedSeconds * 1000L

        val publicVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID_RUNNING)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setLargeIcon(largeIcon)
                .setContentTitle(title)
                .setContentText(contentText)
                .setSubText(ruleName)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setUsesChronometer(!isPaused)
                .apply { if (!isPaused) { setWhen(chronometerBase); setShowWhen(true) } }
                .build()
        } else null

        Log.d(TAG, "buildRunningNotification: elapsed=${elapsedSeconds}s title=$title content=$contentText")

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID_RUNNING)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        if (isPaused) {
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
                .setLargeIcon(largeIcon)
                .setContentTitle(title)
                .setContentText(contentText)
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
                .setLargeIcon(largeIcon)
                .setContentTitle(title)
                .setContentText(contentText)
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
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setColorized(true)
                    }
                }
        }

        publicVersion?.let { builder.setPublicVersion(it) }


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

    private fun updateStandbyNotificationText(contentText: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID_STANDBY, buildStandbyNotification(contentText))
    }

    // ===== 锁屏事件记录 =====

    /**
     * 记录一条锁屏事件 — 在 handleScreenOff 入口处调用，与自动计时逻辑解耦。
     * 受 lockEventRecordEnabled 开关控制。
     */
    private fun recordScreenLockEvent() {
        serviceScope.launch {
            val prefs = UserPreferencesManager(applicationContext)
            val enabled = prefs.lockEventRecordEnabled.first()
            if (!enabled) return@launch

            val event = ScreenLockEvent(
                timestamp = System.currentTimeMillis(),
                elapsedRealtime = SystemClock.elapsedRealtime()
            )
            val dao = AppDatabase.getInstance(applicationContext).screenLockEventDao()
            dao.insert(event)
            _latestLockEvent.value = event
            Log.d(TAG, "已记录锁屏事件: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(event.timestamp)}")

            // 清理 24 小时前的旧数据
            dao.deleteOlderThan(System.currentTimeMillis() - 24 * 3600 * 1000L)
        }
    }

    /**
     * 从锁屏事件回溯启动计时 — 将计时起点追溯到锁屏发生时刻。
     */
    private fun handleStartFromLockEvent(intent: Intent) {
        if (_timerState.value is TimerState.Running) return

        val lockTimestamp = intent.getLongExtra(EXTRA_LOCK_EVENT_TIMESTAMP, 0L)
        val lockElapsedRealtime = intent.getLongExtra(EXTRA_LOCK_EVENT_ELAPSED_REALTIME, 0L)
        if (lockTimestamp == 0L || lockElapsedRealtime == 0L) return

        startTriggerType = DanceRecord.TRIGGER_LOCK_EVENT

        serviceScope.launch {
            val db = AppDatabase.getInstance(applicationContext)
            val ruleWithTiers = db.pricingRuleDao().getDefaultRuleWithTiers()
            if (ruleWithTiers == null) {
                tiers = emptyList()
                ruleName = "未配置规则"
                ruleId = 0L
            } else {
                tiers = ruleWithTiers.sortedTiers
                ruleName = ruleWithTiers.rule.name
                ruleId = ruleWithTiers.rule.id
            }

            // 回溯：使用锁屏时刻的 elapsedRealtime 作为起始基准
            startElapsedRealtime = lockElapsedRealtime
            startWallClock = lockTimestamp
            chronometerBase = lockTimestamp

            val initialElapsed = ((SystemClock.elapsedRealtime() - startElapsedRealtime) / 1000).toInt()
            lastReachedSongIndex = CostCalculator.getCurrentSongIndex(initialElapsed, tiers)
            lastNotifiedCost = 0f
            lastNotifiedSongCount = -1
            lastNotifiedInGrace = false
            lastNotifiedMinute = -1
            pausedElapsedSeconds = 0

            acquireWakeLock()
            stopLockEventNotificationUpdater()

            val initCost = CostCalculator.calculate(initialElapsed, tiers)
            val initSongCount = CostCalculator.getSongCount(initialElapsed, tiers)
            val initSongIndex = CostCalculator.getCurrentSongIndex(initialElapsed, tiers)

            val nm = getSystemService(NotificationManager::class.java)
            nm.cancel(NOTIFICATION_ID_STANDBY)
            startForeground(NOTIFICATION_ID_RUNNING, buildRunningNotification(initialElapsed, initCost, initSongCount))
            updateMediaSessionForRunning(initCost)
            VibrationHelper.vibrateFeedback(applicationContext)

            _timerState.value = TimerState.Running(
                elapsedSeconds = initialElapsed,
                currentSongIndex = initSongIndex,
                cost = initCost,
                songCount = initSongCount,
                startTimeMillis = startWallClock,
                tiers = tiers,
                ruleName = ruleName,
                ruleId = ruleId,
                isPaused = false,
                isInGracePeriod = false,
                isBackdated = true
            )

            startTicking()
            Log.d(TAG, "从锁屏事件回溯计时: ${CostCalculator.formatDuration(initialElapsed)} 已过")
        }
    }

    /**
     * 启动锁屏事件通知更新器 — 待机模式下每 60 秒更新通知，
     * 显示最近锁屏到当前的时间与费用信息。
     * 仅在非计时状态下运行，计时开始时自动暂停。
     */
    private fun startLockEventNotificationUpdater() {
        stopLockEventNotificationUpdater()
        lockEventNotificationRunnable = object : Runnable {
            override fun run() {
                if (!isStandbyActive || _timerState.value is TimerState.Running) return
                updateLockEventNotification()
                handler.postDelayed(this, LOCK_EVENT_NOTIFICATION_INTERVAL_MS)
            }
        }
        // 首次延迟 5 秒后启动（避免与其他初始化冲突）
        handler.postDelayed(lockEventNotificationRunnable!!, 5_000L)
    }

    private fun stopLockEventNotificationUpdater() {
        lockEventNotificationRunnable?.let { handler.removeCallbacks(it) }
        lockEventNotificationRunnable = null
    }

    /**
     * 查询最近锁屏事件并更新待机通知 — 不打扰用户（使用低优先级待机通道，无声无震动）
     */
    private fun updateLockEventNotification() {
        serviceScope.launch {
            val prefs = UserPreferencesManager(applicationContext)
            val enabled = prefs.lockEventRecordEnabled.first()
            if (!enabled) return@launch

            val dao = AppDatabase.getInstance(applicationContext).screenLockEventDao()
            val latest = dao.getLatestEvent() ?: return@launch

            // 仅展示 1 小时内的锁屏事件
            val ageMs = System.currentTimeMillis() - latest.timestamp
            if (ageMs > 3600 * 1000L) return@launch

            val elapsedSeconds = (ageMs / 1000).toInt()
            val db = AppDatabase.getInstance(applicationContext)
            val ruleWithTiers = db.pricingRuleDao().getDefaultRuleWithTiers()
            if (ruleWithTiers != null) {
                val cost = CostCalculator.calculate(elapsedSeconds, ruleWithTiers.sortedTiers)
                val songCount = CostCalculator.getSongCount(elapsedSeconds, ruleWithTiers.sortedTiers)
                val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(latest.timestamp))
                val durationStr = CostCalculator.formatDurationChinese(elapsedSeconds)
                val costStr = CostCalculator.formatCost(cost)
                val songStr = if (songCount > 0) "${songCount}曲" else "未满1曲"
                val notificationText = "最近锁屏 $timeStr · $durationStr · $songStr · $costStr"
                updateStandbyNotificationText(notificationText)
            }
        }
    }

    /**
     * 用户确认自动计时 — 从通知或App内点击"继续计时"触发
     */
    private fun startLockScreenActivity(
        songCount: Int,
        cost: Float,
        elapsedSeconds: Int
    ) {
        val pm = getSystemService(android.os.PowerManager::class.java)
        val screenOn = pm.isInteractive
        Log.d(TAG, "startLockScreenActivity: screenOn=$screenOn song=$songCount cost=$cost elapsed=${elapsedSeconds}s")
        if (screenOn) {
            Log.d(TAG, "startLockScreenActivity: screen is interactive, skip overlay")
            return
        }

        val activityIntent = LockScreenTimerActivity.createIntent(
            context = applicationContext,
            songCount = songCount,
            cost = cost,
            elapsedSeconds = elapsedSeconds
        )
        val activityPi = PendingIntent.getActivity(
            applicationContext,
            30 + songCount,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // setAlarmClock fires the PendingIntent nearly immediately (triggerTime = now)
        // and grants a BAL (Background Activity Launch) exemption recognised by all
        // Android OEM ROMs including OPPO ColorOS and Xiaomi MIUI.
        val am = getSystemService(AlarmManager::class.java)
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
        Log.d(TAG, "startLockScreenActivity: canScheduleExactAlarms=$canExact")
        val triggerAtMillis = System.currentTimeMillis() + 100L
        val alarmClockInfo = AlarmManager.AlarmClockInfo(triggerAtMillis, activityPi)
        if (canExact) {
            am.setAlarmClock(alarmClockInfo, activityPi)
            Log.d(TAG, "startLockScreenActivity: setAlarmClock scheduled for +100ms")
        } else {
            // Fallback: direct startActivity (may be blocked by OEM BAL restrictions)
            Log.w(TAG, "startLockScreenActivity: no exact alarm permission, falling back to startActivity")
            startActivity(activityIntent)
        }
    }

    /**
     * Post a transient billing-peek notification when a new song is charged.
     *
     * Uses CHANNEL_ID_BILLING (IMPORTANCE_HIGH, no sound, no vibration) so that
     * system_server invokes FLAG_TURN_SCREEN_ON via WindowManager — the same
     * mechanism used by WeChat lock-screen messages. Physical vibration is already
     * handled separately by VibrationHelper.
     *
     * The notification auto-cancels after 5 seconds via setTimeoutAfter() so it
     * does not clutter the notification shade.
     */
    private fun postBillingPeekNotification(songCount: Int, cost: Float, elapsedSeconds: Int) {
        val nm = getSystemService(NotificationManager::class.java)

        // Diagnostic: log permission and channel state before posting
        val areNotificationsEnabled = nm.areNotificationsEnabled()
        val billingChannel = nm.getNotificationChannel(CHANNEL_ID_BILLING)
        val channelImportance = billingChannel?.importance ?: -1
        val channelBlocked = channelImportance == NotificationManager.IMPORTANCE_NONE
        val canFsi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) nm.canUseFullScreenIntent() else true
        Log.d(TAG, "postBillingPeek: notifEnabled=$areNotificationsEnabled channelImportance=$channelImportance channelBlocked=$channelBlocked canUseFullScreenIntent=$canFsi song=$songCount cost=$cost elapsed=${elapsedSeconds}s")

        if (!areNotificationsEnabled) {
            Log.w(TAG, "postBillingPeek: notifications disabled globally, skip")
            return
        }
        if (channelBlocked) {
            Log.w(TAG, "postBillingPeek: CHANNEL_BILLING is blocked by user, skip")
            return
        }

        val contentIntent = PendingIntent.getActivity(
            this, 20,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val costStr = CostCalculator.formatCost(cost)
        val totalMinutes = elapsedSeconds / 60
        val timeDisplay = if (totalMinutes > 0) "${totalMinutes}分钟" else "${elapsedSeconds}秒"
        val title = "第${songCount}曲  ·  $costStr"
        val contentText = "已计时 $timeDisplay  ·  长按音量键停止"

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID_BILLING)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setLargeIcon(android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher))
            .setContentTitle(title)
            .setSubText("舞蹈计时器")
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setTimeoutAfter(5_000)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setColor(0xFF6750A4.toInt())
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    if (canFsi) {
                        setFullScreenIntent(contentIntent, false)
                        Log.d(TAG, "postBillingPeek: setFullScreenIntent applied (API 34+)")
                    } else {
                        Log.w(TAG, "postBillingPeek: USE_FULL_SCREEN_INTENT not granted, no screen wake")
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setFullScreenIntent(contentIntent, false)
                    Log.d(TAG, "postBillingPeek: setFullScreenIntent applied (API < 34)")
                }
            }
            .build()

        nm.notify(NOTIFICATION_ID_BILLING_PEEK, notification)
        Log.d(TAG, "postBillingPeek: notified ID=$NOTIFICATION_ID_BILLING_PEEK title=$title")
    }
}
