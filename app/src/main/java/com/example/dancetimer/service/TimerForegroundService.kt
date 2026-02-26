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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
        private const val CHANNEL_ID_ALERT = "dance_timer_alert"
        private const val NOTIFICATION_ID_STANDBY = 1001
        private const val NOTIFICATION_ID_RUNNING = 1002
        private const val NOTIFICATION_ID_ALERT = 1003
        private const val LONG_PRESS_MS = 1500L // 长按阈值（毫秒）

        const val ACTION_START = "com.example.dancetimer.ACTION_START"
        const val ACTION_STOP = "com.example.dancetimer.ACTION_STOP"
        const val ACTION_PAUSE = "com.example.dancetimer.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.dancetimer.ACTION_RESUME"
        const val ACTION_STANDBY = "com.example.dancetimer.ACTION_STANDBY"
        const val ACTION_DISMISS = "com.example.dancetimer.ACTION_DISMISS"
        const val ACTION_AUTO_START = "com.example.dancetimer.ACTION_AUTO_START"
        const val ACTION_CANCEL_AUTO = "com.example.dancetimer.ACTION_CANCEL_AUTO"
        const val ACTION_CONFIRM_AUTO = "com.example.dancetimer.ACTION_CONFIRM_AUTO"
        private const val ACTION_TICK = "com.example.dancetimer.ACTION_TICK"
        /** AlarmManager 唤醒间隔 — OEM 冻结进程时的保底刷新 */
        private const val ALARM_TICK_INTERVAL_MS = 30_000L

        /** 步行检测阈值（步/分钟）— 超过此值判定为走路而非跳舞 */
        private const val STEP_WALKING_THRESHOLD_PER_MINUTE = 80

        /** 步频计算滚动窗口（秒）— 固定10秒，不受延迟配置长短影响 */
        private const val STEP_DETECTION_WINDOW_SECONDS = 10

        /** 步频评估所需最短采样时间（ms）— 不足此时长数据过少，不做判断 */
        private const val STEP_DETECTION_MIN_SAMPLE_MS = 5_000L

        /** 步行监控阶段：每次检查间隔（ms） */
        private const val WALKING_CHECK_INTERVAL_MS = 3_000L

        /** 步行监控阶段：连续多少次非步行才视为已停止 (~6秒) */
        private const val WALKING_STOPPED_CONFIRM_COUNT = 2

        /** 步行监控最大等待时长（ms）— 超时后放弃，避免长期驻留传感器 */
        private const val WALKING_MONITOR_TIMEOUT_MS = 5 * 60 * 1000L

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

        /** 确认自动计时 */
        fun confirmAutoStart(context: Context) {
            val intent = Intent(context, TimerForegroundService::class.java).apply {
                action = ACTION_CONFIRM_AUTO
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
    // 用户解锁后是否已返回（用于通知提示确认）
    private var hasUserReturned: Boolean = false
    // 息屏自动计时 — 延迟等待阶段
    private var isPendingAutoStart = false
    private var pendingAutoStartRunnable: Runnable? = null

    // 广播接收器
    private var screenOffReceiver: BroadcastReceiver? = null
    private var screenOnReceiver: BroadcastReceiver? = null
    private var userPresentReceiver: BroadcastReceiver? = null

    // 计步器防误触 — 滚动时间窗口算法
    private var stepSensorManager: SensorManager? = null
    private var stepListener: SensorEventListener? = null
    /** 步伐时间戳列表，用于滚动窗口步频计算 */
    private val stepTimestamps = mutableListOf<Long>()
    /** 计步器启动时刻（用于判断采样时长是否已满足最小值） */
    private var stepDetectionStartTime: Long = 0L

    // 步行监控 — 检测到步行后持续监控，停止步行后重新触发延迟等待
    private var isWalkingMonitoring = false
    private var walkingCheckRunnable: Runnable? = null
    private var walkingMonitorStartTime = 0L
    private var walkingStoppedCount = 0
    /** 当前延迟等待周期的秒数，用于步行结束后重新进入延迟 */
    private var pendingAutoStartDelaySeconds = 0
    /** 当前生效的步行判定阈值（步/分钟），从用户配置加载 */
    private var activeStepWalkingThreshold = STEP_WALKING_THRESHOLD_PER_MINUTE

    // 触发来源 & 确认类型追踪（用于保存历史记录元数据）
    private var startTriggerType: String = DanceRecord.TRIGGER_MANUAL
    private var autoConfirmResult: String? = null
    private var startScreenOffDelay: Int = 0

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
            ACTION_CONFIRM_AUTO -> handleConfirmAuto()
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
        cancelWalkingMonitoring()
        cancelPendingAutoStart()
        unregisterScreenOffReceiver()
        unregisterUserPresentReceiver()
        stopStepDetection()
        releaseMediaSession()
        SilentAudioPlayer.stop()
        stopTicking()
        releaseWakeLock()
        isStandbyActive = false
        isAutoStarted = false
        hasUserReturned = false
        startTriggerType = DanceRecord.TRIGGER_MANUAL
        autoConfirmResult = null
        startScreenOffDelay = 0
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

        // 如果有延迟等待中的自动计时，取消它（用户已手动启动或延迟到期）
        if (isPendingAutoStart) {
            cancelPendingAutoStart()
        }

        isAutoStarted = isAuto
        hasUserReturned = false
        startTriggerType = if (isAuto) DanceRecord.TRIGGER_AUTO else DanceRecord.TRIGGER_MANUAL
        autoConfirmResult = null
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

            // 自动计时：读取并快照延迟配置（在记录中保存触发时的配置值）
            if (isAuto) {
                startScreenOffDelay = UserPreferencesManager(applicationContext).autoStartDelaySeconds.first()
            } else {
                startScreenOffDelay = 0
            }

            // 自动计时：将起始时间回溯延迟秒数，使 elapsed 从延迟值开始（息屏等待期间即为跳舞时间）
            val autoOffsetMs = if (isAuto) startScreenOffDelay * 1000L else 0L
            startElapsedRealtime = SystemClock.elapsedRealtime() - autoOffsetMs
            startWallClock = System.currentTimeMillis() - autoOffsetMs
            chronometerBase = startWallClock

            val initialElapsed = if (isAuto) startScreenOffDelay else 0
            lastReachedSongIndex = CostCalculator.getCurrentSongIndex(initialElapsed, tiers)
            lastNotifiedCost = 0f
            lastNotifiedSongCount = -1
            lastNotifiedInGrace = false
            lastNotifiedMinute = -1
            pausedElapsedSeconds = 0

            // 获取 WakeLock
            acquireWakeLock()

            val initCost = CostCalculator.calculate(initialElapsed, tiers)
            val initSongCount = CostCalculator.getSongCount(initialElapsed, tiers)
            val initSongIndex = CostCalculator.getCurrentSongIndex(initialElapsed, tiers)

            // 切换前台通知：先移除待命通知，再启动计时通知
            // 使用不同 NOTIFICATION_ID 避免 OEM（OPPO/ColorOS/MIUI）渠道切换失败
            val nm = getSystemService(NotificationManager::class.java)
            nm.cancel(NOTIFICATION_ID_STANDBY)
            Log.d(TAG, "待命通知已取消(ID=$NOTIFICATION_ID_STANDBY), 准备发送计时通知(ID=$NOTIFICATION_ID_RUNNING)")
            startForeground(NOTIFICATION_ID_RUNNING, buildRunningNotification(initialElapsed, initCost, initSongCount, isAutoStarted = isAuto))
            Log.d(TAG, "计时通知已发送, channel=$CHANNEL_ID_RUNNING, initialElapsed=${initialElapsed}s")

            // 更新 MediaSession 元数据（锁屏/状态胶囊显示计时信息）
            updateMediaSessionForRunning(initCost)

            // 震动反馈：开始
            if (isAuto) {
                // 自动计时 → 强化震动（两段式）确保用户感知到
                vibrateAutoStartAlert(applicationContext)
                // 发送高优先级 heads-up 弹头通知
                fireAutoStartAlertNotification()
            } else {
                VibrationHelper.vibrateFeedback(applicationContext)
            }

            // 更新状态
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
                isAutoStarted = isAuto
            )

            // 启动计时机制 — Handler 主循环 + AlarmManager 备份唤醒
            startTicking()

            // 自动计时：注册用户解锁感知，用于亮屏时提醒确认
            if (isAuto) {
                registerUserPresentReceiver()
            }
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

        // 自动计时：首次计费时自动确认（不同规则歌曲时长不同，比固定时间更自适应）
        if (isAutoStarted && cost > 0f) {
            Log.d(TAG, "自动计时到达首次计费，自动确认")
            isAutoStarted = false
            hasUserReturned = false
            autoConfirmResult = DanceRecord.RESULT_CONFIRMED_AUTO
            stopStepDetection()
            unregisterUserPresentReceiver()

            // 取消弹头提醒通知
            getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID_ALERT)
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
        hasUserReturned = false
        stopStepDetection()
        unregisterUserPresentReceiver()

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
                triggerType = startTriggerType,
                autoStartResult = autoConfirmResult,
                screenOffDelaySeconds = startScreenOffDelay
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
        // 取消延迟等待阶段
        if (isPendingAutoStart) {
            cancelPendingAutoStart()
            return
        }

        val current = _timerState.value
        if (current !is TimerState.Running || !current.isAutoStarted) return

        Log.d(TAG, "取消自动计时（误触发）")
        stopTicking()
        isAutoStarted = false
        hasUserReturned = false
        stopStepDetection()
        unregisterUserPresentReceiver()

        // 取消弹头提醒通知
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID_ALERT)

        // 保存误计时记录供用户查看（cost=0，标记为已取消）
        if (startWallClock > 0L) {
            val endWallClock = System.currentTimeMillis()
            val elapsed = current.elapsedSeconds
            serviceScope.launch {
                val record = DanceRecord(
                    startTime = startWallClock,
                    endTime = endWallClock,
                    durationSeconds = elapsed,
                    cost = 0f,
                    pricingRuleName = ruleName,
                    pricingRuleId = ruleId,
                    triggerType = DanceRecord.TRIGGER_AUTO,
                    autoStartResult = DanceRecord.RESULT_CANCELLED,
                    cancelledDurationSeconds = elapsed,
                    screenOffDelaySeconds = startScreenOffDelay
                )
                AppDatabase.getInstance(applicationContext).danceRecordDao().insert(record)
            }
        }

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
        cancelWalkingMonitoring()
        cancelPendingAutoStart()
        unregisterScreenOffReceiver()
        unregisterUserPresentReceiver()
        stopStepDetection()
        releaseMediaSession()
        SilentAudioPlayer.stop()
        serviceScope.cancel()
        releaseWakeLock()
        isStandbyActive = false
        isAutoStarted = false
        hasUserReturned = false
        startTriggerType = DanceRecord.TRIGGER_MANUAL
        autoConfirmResult = null
        startScreenOffDelay = 0
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

        val displayText = contentText ?: "长按音量+ 开始 · 长按音量- 停止"
        return NotificationCompat.Builder(this, CHANNEL_ID_STANDBY)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("计时器待命中 (保持通知开启)")
            .setContentText(displayText)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(if (contentText != null) contentText else "长按音量+ 开始 · 长按音量- 停止。\n请勿关闭本通知，否则锁屏后将无法通过音量键控制。"))
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
        isAutoStarted: Boolean = this.isAutoStarted,
        hasUserReturned: Boolean = this.hasUserReturned
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

        val confirmAutoIntent = PendingIntent.getService(
            this, 5,
            Intent(this, TimerForegroundService::class.java).apply {
                action = ACTION_CONFIRM_AUTO
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
        val contentText = if (isInGrace) "🛡️ 缓冲 ${graceRemaining}s" else null
        // Chronometer 基准：SystemUI 渲染，进程冻结也能正常走秒
        val chronometerBase = System.currentTimeMillis() - elapsedSeconds * 1000L

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID_RUNNING)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        if (isAutoStarted && hasUserReturned) {
            // 用户已解锁返回 — 显示确认/取消按钮
            val confirmAction = Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_play_notification),
                "继续计时",
                confirmAutoIntent
            ).build()
            val cancelAction = Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stop_notification),
                "取消",
                cancelAutoIntent
            ).build()
            builder.setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setSubText("⚠ 已自动计时 ${totalMinutes}分钟 — 确认或取消")
                .setOngoing(true)
                .setOnlyAlertOnce(false) // 允许每次提醒
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_STOPWATCH)
                .setUsesChronometer(true)
                .setWhen(chronometerBase)
                .setShowWhen(true)
                .setContentIntent(contentIntent)
                .addAction(confirmAction)
                .addAction(cancelAction)
                .setColor(0xFFE65100.toInt())
        } else if (isAutoStarted) {
            // 锁屏中 — 显示取消/停止按钮
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
        if (!isStandbyActive) return

        val current = _timerState.value

        // Case 1: 未确认的自动计时 + 用户再次锁屏
        if (current is TimerState.Running && isAutoStarted) {
            Log.d(TAG, "handleScreenOff Case 1: isAutoStarted=true, hasUserReturned=$hasUserReturned")
            if (hasUserReturned) {
                // 用户已解锁看到过提示但未操作 → 保持计时，下次解锁再次提醒
                // 理由：用户已知晓计时存在，未取消 ≠ 误触，保留记录不重置
                Log.d(TAG, "用户已看到提示但未确认，再次锁屏 → 继续计时，下次解锁重新提醒")
                hasUserReturned = false // 重置，下次解锁时再次震动提醒
                return
            }

            // 用户从未解锁看到过提示（屏幕未亮起或通知未展示）→ 重置重计
            serviceScope.launch {
                val prefs = UserPreferencesManager(applicationContext)
                val autoStartEnabled = prefs.autoStartOnScreenOff.first()
                if (!autoStartEnabled) return@launch

                Log.d(TAG, "未确认自动计时期间再次锁屏（用户未看到提示）→ 重置重计")
                stopTicking()
                isAutoStarted = false
                hasUserReturned = false
                cancelWalkingMonitoring()
                stopStepDetection()
                unregisterUserPresentReceiver()
                _timerState.value = TimerState.Idle
                releaseWakeLock()

                lastNotifiedCost = -1f
                lastNotifiedSongCount = -1
                lastNotifiedInGrace = false
                lastNotifiedMinute = -1

                DanceTimerWidgetReceiver.requestUpdate(applicationContext)

                val nm = getSystemService(NotificationManager::class.java)
                nm.cancel(NOTIFICATION_ID_RUNNING)
                nm.cancel(NOTIFICATION_ID_ALERT)
                startForeground(NOTIFICATION_ID_STANDBY, buildStandbyNotification())
                resetMediaSessionForStandby()

                // 重新进入延迟等待
                val delaySeconds = prefs.autoStartDelaySeconds.first()
                enterPendingAutoStart(delaySeconds)
            }
            return
        }

        // Case 2: 已确认的计时（手动或自动确认后）→ 忽略
        if (current is TimerState.Running) return

        // Case 3: 完成状态 → 忽略（用户在查看结果）
        if (current is TimerState.Finished) return

        // Case 4: 空闲 → 进入延迟等待
        if (isPendingAutoStart) return // 已在等待中

        serviceScope.launch {
            val prefs = UserPreferencesManager(applicationContext)
            val autoStartEnabled = prefs.autoStartOnScreenOff.first()
            if (!autoStartEnabled) return@launch

            val delaySeconds = prefs.autoStartDelaySeconds.first()
            Log.d(TAG, "息屏检测: 进入延迟等待阶段 (${delaySeconds}秒)")
            enterPendingAutoStart(delaySeconds)
        }
    }

    // ===== 智能防误触 — 延迟等待 + 确认 + 计步器 =====
    //
    // 状态流: Idle → Pending(延迟等待) → Unconfirmed Running → Confirmed Running
    //
    // Pending: 锁屏后等待用户配置的延迟秒数。如果期间亮屏或检测到步行则取消。
    // Unconfirmed Running: 计时已开始但未确认。用户解锁时震动+通知提醒。
    //   - 用户确认 → 正常计时
    //   - 用户取消 → 回到 Idle
    //   - 用户忽略并再次锁屏 → 取消当前计时，重新进入 Pending
    //   - 首次计费（cost > 0）→ 自动确认
    // 计步器: 步频 > 用户配置阈值 = 走路（非跳舞）→ 取消 pending

    /**
     * 进入延迟等待阶段 — 锁屏后不立即计时，等待指定秒数
     */
    private fun enterPendingAutoStart(delaySeconds: Int) {
        if (isPendingAutoStart) return
        isPendingAutoStart = true
        pendingAutoStartDelaySeconds = delaySeconds
        stepTimestamps.clear()
        stepDetectionStartTime = SystemClock.elapsedRealtime()

        Log.d(TAG, "进入延迟等待阶段: ${delaySeconds}秒后自动计时")

        // 注册亮屏接收器 — 如果期间亮屏则取消
        registerScreenOnReceiver()

        // 启动计步器（仅当用户开启实验性功能时）
        serviceScope.launch {
            val prefs = UserPreferencesManager(applicationContext)
            val stepEnabled = prefs.stepDetectionEnabled.first()
            if (stepEnabled) {
                // 加载用户配置的步行阈值
                activeStepWalkingThreshold = prefs.stepWalkingThreshold.first()
                startStepDetection()
            } else {
                Log.d(TAG, "计步器防误触未开启，跳过")
            }
        }

        // 延迟后启动计时
        pendingAutoStartRunnable = Runnable {
            if (isPendingAutoStart) {
                isPendingAutoStart = false
                unregisterScreenOnReceiver()

                // 检查步频是否为走路
                if (isWalkingDetected()) {
                    Log.d(TAG, "延迟等待期间检测到步行，进入步行监控模式")
                    // 不立即放弃：持续监控，步行停止后重新进入延迟等待
                    startWalkingMonitor(delaySeconds)
                    return@Runnable
                }
                stopStepDetection()

                Log.d(TAG, "延迟等待结束，启动自动计时")
                handleStart(isAuto = true)
            }
        }
        handler.postDelayed(pendingAutoStartRunnable!!, delaySeconds * 1000L)
    }

    /**
     * 取消延迟等待 — 亮屏、用户操作、服务退出时调用
     */
    private fun cancelPendingAutoStart() {
        if (!isPendingAutoStart) return
        Log.d(TAG, "取消延迟等待")
        isPendingAutoStart = false
        pendingAutoStartRunnable?.let { handler.removeCallbacks(it) }
        pendingAutoStartRunnable = null
        unregisterScreenOnReceiver()
        stopStepDetection()
    }

    /**
     * 步行监控模式 — 延迟到期检测到步行时进入。
     * 每 [WALKING_CHECK_INTERVAL_MS] 毫秒检测一次步频：
     *   - 仍在步行 → 继续等待（最长 [WALKING_MONITOR_TIMEOUT_MS]）
     *   - 连续 [WALKING_STOPPED_CONFIRM_COUNT] 次非步行 → 重新进入延迟等待
     *   - 期间亮屏 → screenOnReceiver 仍注册，触发后 cancelWalkingMonitoring()
     */
    private fun startWalkingMonitor(retryDelaySeconds: Int) {
        if (isWalkingMonitoring) return
        isWalkingMonitoring = true
        walkingMonitorStartTime = SystemClock.elapsedRealtime()
        walkingStoppedCount = 0
        Log.d(TAG, "进入步行监控模式，等待步行停止后自动重试")
        updateStandbyNotificationText("检测到步行，停下后将自动计时")

        // 重新注册亮屏接收器 — 步行监控阶段亮屏同样应取消自动计时
        registerScreenOnReceiver()

        walkingCheckRunnable = object : Runnable {
            override fun run() {
                if (!isWalkingMonitoring) return

                // 超时保障：超过上限后放弃，避免传感器长期驻留
                val elapsed = SystemClock.elapsedRealtime() - walkingMonitorStartTime
                if (elapsed > WALKING_MONITOR_TIMEOUT_MS) {
                    Log.d(TAG, "步行监控超时 (${WALKING_MONITOR_TIMEOUT_MS / 60000}分钟)，放弃自动计时")
                    cancelWalkingMonitoring()
                    return
                }

                if (isWalkingDetected()) {
                    // 仍在步行 — 继续等待
                    walkingStoppedCount = 0
                    handler.postDelayed(this, WALKING_CHECK_INTERVAL_MS)
                } else {
                    walkingStoppedCount++
                    if (walkingStoppedCount >= WALKING_STOPPED_CONFIRM_COUNT) {
                        // 已确认停止步行 → 重新进入延迟等待
                        Log.d(TAG, "步行已停止 (连续${WALKING_STOPPED_CONFIRM_COUNT}次检测)，重新进入延迟等待 (${retryDelaySeconds}秒)")
                        cancelWalkingMonitoring()
                        // 清空旧步伐数据，让新延迟周期重新采样
                        stepTimestamps.clear()
                        stepDetectionStartTime = SystemClock.elapsedRealtime()
                        enterPendingAutoStart(retryDelaySeconds)
                    } else {
                        handler.postDelayed(this, WALKING_CHECK_INTERVAL_MS)
                    }
                }
            }
        }
        handler.postDelayed(walkingCheckRunnable!!, WALKING_CHECK_INTERVAL_MS)
    }

    /**
     * 取消步行监控 — 亮屏、超时、成功停止步行后调用
     */
    private fun cancelWalkingMonitoring() {
        if (!isWalkingMonitoring) return
        isWalkingMonitoring = false
        walkingCheckRunnable?.let { handler.removeCallbacks(it) }
        walkingCheckRunnable = null
        walkingStoppedCount = 0
        stopStepDetection()
        unregisterScreenOnReceiver()
        Log.d(TAG, "步行监控已取消")
    }

    /**
     * 更新待命通知正文 — 用于步行检测反馈，不影响其他字段
     */
    private fun updateStandbyNotificationText(contentText: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID_STANDBY, buildStandbyNotification(contentText))
    }

    /**
     * 用户确认自动计时 — 从通知或App内点击"继续计时"触发
     */
    private fun handleConfirmAuto() {
        val current = _timerState.value
        if (current !is TimerState.Running || !isAutoStarted) return

        Log.d(TAG, "用户确认自动计时")
        isAutoStarted = false
        hasUserReturned = false
        autoConfirmResult = DanceRecord.RESULT_CONFIRMED_USER
        stopStepDetection()
        unregisterUserPresentReceiver()

        // 取消弹头提醒通知
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID_ALERT)

        // 震动确认
        VibrationHelper.vibrateFeedback(applicationContext)

        // 更新状态（清除 isAutoStarted）
        _timerState.value = current.copy(isAutoStarted = false)

        // 更新通知为正常样式
        val elapsed = current.elapsedSeconds
        val inGrace = current.isInGracePeriod
        val graceRemaining = if (inGrace) CostCalculator.getGraceRemainingSeconds(elapsed, tiers) else 0
        val notification = buildRunningNotification(elapsed, current.cost, current.songCount, inGrace, graceRemaining)
        startForeground(NOTIFICATION_ID_RUNNING, notification)
        updateMediaSessionForRunning(current.cost)
    }

    // ===== 亮屏/解锁感知广播接收器 =====

    private fun registerScreenOnReceiver() {
        if (screenOnReceiver != null) return
        screenOnReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_SCREEN_ON) {
                    Log.d(TAG, "亮屏 → 取消自动计时准备（延迟等待 / 步行监控）")
                    // 两种准备状态都需取消；各自内置幂等守卫，双调安全
                    cancelPendingAutoStart()
                    cancelWalkingMonitoring()
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOnReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenOnReceiver, filter)
        }
    }

    private fun unregisterScreenOnReceiver() {
        screenOnReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        screenOnReceiver = null
    }

    private fun registerUserPresentReceiver() {
        if (userPresentReceiver != null) return
        userPresentReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> handleScreenOn()
                    Intent.ACTION_USER_PRESENT -> handleUserPresent()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(userPresentReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(userPresentReceiver, filter)
        }
    }

    private fun unregisterUserPresentReceiver() {
        userPresentReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        userPresentReceiver = null
    }

    /**
     * 屏幕亮起 — 设置 hasUserReturned 标记
     * 作用：OPPO/ColorOS 等设备可能不发送 ACTION_USER_PRESENT（尤其是指纹/面部解锁），
     * 但 ACTION_SCREEN_ON 在所有设备上都可靠发送。
     * 屏幕亮起 = 用户大概率看到了通知栏的自动计时提醒，不应视为误触。
     */
    private fun handleScreenOn() {
        val current = _timerState.value
        if (current !is TimerState.Running || !isAutoStarted) return
        if (hasUserReturned) return

        Log.d(TAG, "屏幕亮起，标记 hasUserReturned=true（用户可能已看到自动计时通知）")
        hasUserReturned = true
        // 不震动 — 等 ACTION_USER_PRESENT 解锁后再震动提醒
    }

    /**
     * 用户解锁返回 — 震动提醒 + 更新通知为确认样式
     */
    private fun handleUserPresent() {
        val current = _timerState.value
        if (current !is TimerState.Running || !isAutoStarted) return

        Log.d(TAG, "用户解锁（ACTION_USER_PRESENT），hasUserReturned=$hasUserReturned")

        // 确保标记已设置（SCREEN_ON 可能已经设过，这里兜底）
        hasUserReturned = true

        // 震动提醒
        VibrationHelper.vibrateFeedback(applicationContext)

        // 更新通知为确认样式
        val elapsed = ((SystemClock.elapsedRealtime() - startElapsedRealtime) / 1000).toInt()
        val cost = CostCalculator.calculate(elapsed, tiers)
        val songCount = CostCalculator.getSongCount(elapsed, tiers)
        val inGrace = CostCalculator.isInGracePeriod(elapsed, tiers)
        val graceRemaining = if (inGrace) CostCalculator.getGraceRemainingSeconds(elapsed, tiers) else 0
        val notification = buildRunningNotification(elapsed, cost, songCount, inGrace, graceRemaining)
        startForeground(NOTIFICATION_ID_RUNNING, notification)
    }

    // ===== 计步器防误触 =====

    /**
     * 启动计步器 — 使用 TYPE_STEP_DETECTOR 检测步行
     * 跳舞时身体原地律动，步数低。走路/游走时步数高。
     * 因此 高步频 = 非跳舞 → 取消自动计时。
     */
    private fun startStepDetection() {
        if (stepSensorManager != null) return // already active

        // API 29+ 需要 ACTIVITY_RECOGNITION 权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "缺少 ACTIVITY_RECOGNITION 权限，跳过计步检测")
                return
            }
        }

        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val detector = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: run {
            Log.d(TAG, "设备不支持 TYPE_STEP_DETECTOR，跳过计步检测")
            return
        }

        stepSensorManager = sm
        stepTimestamps.clear()
        stepDetectionStartTime = SystemClock.elapsedRealtime()

        stepListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
                    stepTimestamps.add(SystemClock.elapsedRealtime())
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sm.registerListener(stepListener, detector, SensorManager.SENSOR_DELAY_NORMAL)
        Log.d(TAG, "计步器已启动")
    }

    private fun stopStepDetection() {
        stepListener?.let { listener ->
            stepSensorManager?.unregisterListener(listener)
        }
        stepListener = null
        stepSensorManager = null
        stepTimestamps.clear()
    }

    /**
     * 判断是否检测到步行 — 步频 > [STEP_WALKING_THRESHOLD_PER_MINUTE] 步/分钟视为走路
     *
     * 使用滚动时间窗口（固定 [STEP_DETECTION_WINDOW_SECONDS] 秒），
     * 与延迟配置长短无关，精度稳定。
     */
    private fun isWalkingDetected(): Boolean {
        if (stepSensorManager == null) return false // 计步器未启动，不做判断

        val now = SystemClock.elapsedRealtime()

        // 最小采样时长检查 — 数据太少时不做判断（返回 false = 不阻止计时）
        val sinceStartMs = now - stepDetectionStartTime
        if (sinceStartMs < STEP_DETECTION_MIN_SAMPLE_MS) {
            Log.d(TAG, "计步: 采样时长不足 (${sinceStartMs}ms < ${STEP_DETECTION_MIN_SAMPLE_MS}ms)，跳过判断")
            return false
        }

        // 滚动窗口：仅统计最近 STEP_DETECTION_WINDOW_SECONDS 秒内的步伐
        val windowMs = STEP_DETECTION_WINDOW_SECONDS * 1000L
        val windowStartMs = now - windowMs
        val recentSteps = stepTimestamps.count { it >= windowStartMs }

        // 有效窗口时长：取「自检测启动以来经过的时间」与「完整窗口」的较小值
        val effectiveWindowMs = minOf(sinceStartMs, windowMs)
        val effectiveWindowSec = effectiveWindowMs / 1000.0

        val stepsPerMinute = if (effectiveWindowSec > 0) (recentSteps / effectiveWindowSec) * 60.0 else 0.0
        val isWalking = stepsPerMinute > activeStepWalkingThreshold
        Log.d(TAG, "计步(滚动${STEP_DETECTION_WINDOW_SECONDS}s窗口): ${recentSteps}步/${effectiveWindowSec.toInt()}秒, ${stepsPerMinute.toInt()}步/分, 阈值=${activeStepWalkingThreshold}, 走路=$isWalking")
        return isWalking
    }

    // ===== 自动计时启动提醒 =====

    /**
     * 强化震动 — 两段式脉冲，确保息屏状态下用户能感知到
     */
    private fun vibrateAutoStartAlert(context: Context) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 间隔: 0ms等待, 300ms震, 200ms停, 300ms震
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 300, 200, 300), -1)
        }
    }

    /**
     * 发送高优先级 heads-up 弹头通知 — 即使锁屏也能弹出
     * 通知自动 5 秒后消失
     */
    private fun fireAutoStartAlertNotification() {
        val contentIntent = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelAutoIntent = PendingIntent.getService(
            this, 11,
            Intent(this, TimerForegroundService::class.java).apply {
                action = ACTION_CANCEL_AUTO
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID_ALERT)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("⏱ 已自动开始计时")
            .setContentText("息屏触发 · 误触请点击取消")
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000) // 15秒后自动消失
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_ALARM)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stop_notification),
                    "取消计时",
                    cancelAutoIntent
                ).build()
            )
            .setColor(0xFFE65100.toInt())
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID_ALERT, notification)

        Log.d(TAG, "已发送自动计时弹头提醒通知")
    }
}
