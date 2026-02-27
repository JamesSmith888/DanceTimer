package com.example.dancetimer.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 触发方式枚举 */
enum class TriggerMode {
    /** 长按音量键 1.5 秒 */
    LONG_PRESS,
    /** 500ms 内连按 3 次 */
    TRIPLE_CLICK
}

/** 主题模式枚举 */
enum class ThemeMode {
    LIGHT,
    DARK,
    SYSTEM
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "dance_timer_prefs")

/**
 * 用户偏好设置管理器（DataStore）
 */
class UserPreferencesManager(private val context: Context) {

    companion object {
        private val KEY_TRIGGER_MODE = stringPreferencesKey("trigger_mode")
        private val KEY_VIBRATE_ON_TIER = booleanPreferencesKey("vibrate_on_tier")
        private val KEY_DEFAULT_RULE_ID = longPreferencesKey("default_rule_id")
        private val KEY_FIRST_LAUNCH = booleanPreferencesKey("first_launch")
        private val KEY_AUTO_START_ON_SCREEN_OFF = booleanPreferencesKey("auto_start_on_screen_off")
        private val KEY_AUTO_START_DELAY_SECONDS = intPreferencesKey("auto_start_delay_seconds")
        private val KEY_STEP_DETECTION_ENABLED = booleanPreferencesKey("step_detection_enabled")
        private val KEY_STEP_WALKING_THRESHOLD = intPreferencesKey("step_walking_threshold")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_BATTERY_GUIDE_SHOWN = booleanPreferencesKey("battery_guide_shown")
        private val KEY_LOCK_EVENT_RECORD_ENABLED = booleanPreferencesKey("lock_event_record_enabled")

        // ---- 版本更新相关 ----
        private val KEY_LAST_AUTO_CHECK_TIME = longPreferencesKey("last_auto_check_time")
        private val KEY_SKIPPED_VERSION = stringPreferencesKey("skipped_version")
        private val KEY_SNOOZE_UNTIL_TIME = longPreferencesKey("snooze_until_time")
        private val KEY_LAST_INSTALLED_UPDATE_VERSION = stringPreferencesKey("last_installed_update_version")
    }

    // ---- 触发方式 ----

    val triggerMode: Flow<TriggerMode> = context.dataStore.data.map { prefs ->
        val value = prefs[KEY_TRIGGER_MODE] ?: TriggerMode.LONG_PRESS.name
        TriggerMode.valueOf(value)
    }

    suspend fun setTriggerMode(mode: TriggerMode) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TRIGGER_MODE] = mode.name
        }
    }

    // ---- 震动提醒 ----

    val vibrateOnTier: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_VIBRATE_ON_TIER] ?: true
    }

    suspend fun setVibrateOnTier(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_VIBRATE_ON_TIER] = enabled
        }
    }

    // ---- 默认规则 ID ----

    val defaultRuleId: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_RULE_ID] ?: -1L
    }

    suspend fun setDefaultRuleId(ruleId: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEFAULT_RULE_ID] = ruleId
        }
    }

    // ---- 首次启动 ----

    val isFirstLaunch: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_FIRST_LAUNCH] ?: true
    }

    suspend fun setFirstLaunchDone() {
        context.dataStore.edit { prefs ->
            prefs[KEY_FIRST_LAUNCH] = false
        }
    }

    // ---- 息屏自动计时 ----

    val autoStartOnScreenOff: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_START_ON_SCREEN_OFF] ?: false
    }

    suspend fun setAutoStartOnScreenOff(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_START_ON_SCREEN_OFF] = enabled
        }
    }

    // ---- 自动计时延迟秒数 ----

    val autoStartDelaySeconds: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_START_DELAY_SECONDS] ?: 180
    }

    suspend fun setAutoStartDelaySeconds(seconds: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_START_DELAY_SECONDS] = seconds
        }
    }

    // ---- 计步器防误触（实验性） ----

    val stepDetectionEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_STEP_DETECTION_ENABLED] ?: false
    }

    suspend fun setStepDetectionEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_STEP_DETECTION_ENABLED] = enabled
        }
    }

    // ---- 步行检测阈值（步/分钟） ----

    /** 步行判定阈值（步/分钟），默认 80 */
    val stepWalkingThreshold: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_STEP_WALKING_THRESHOLD] ?: 80
    }

    suspend fun setStepWalkingThreshold(threshold: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_STEP_WALKING_THRESHOLD] = threshold
        }
    }

    // ---- 锁屏事件记录 ----

    /** 是否启用锁屏事件记录（默认开启） */
    val lockEventRecordEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_LOCK_EVENT_RECORD_ENABLED] ?: true
    }

    suspend fun setLockEventRecordEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LOCK_EVENT_RECORD_ENABLED] = enabled
        }
    }

    // ---- 后台运行引导 ----

    val batteryGuideShown: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_BATTERY_GUIDE_SHOWN] ?: false
    }

    suspend fun setBatteryGuideShown() {
        context.dataStore.edit { prefs ->
            prefs[KEY_BATTERY_GUIDE_SHOWN] = true
        }
    }

    // ---- 主题模式 ----

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        val value = prefs[KEY_THEME_MODE] ?: ThemeMode.SYSTEM.name
        try { ThemeMode.valueOf(value) } catch (_: Exception) { ThemeMode.SYSTEM }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode.name
        }
    }

    // ---- 版本更新偏好 ----

    /** 上次自动检查更新的时间戳（毫秒） */
    val lastAutoCheckTime: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_AUTO_CHECK_TIME] ?: 0L
    }

    suspend fun setLastAutoCheckTime(timeMillis: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_AUTO_CHECK_TIME] = timeMillis
        }
    }

    /** 用户选择跳过的版本号（如 "0.1.1"），空字符串表示未跳过 */
    val skippedVersion: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SKIPPED_VERSION] ?: ""
    }

    suspend fun setSkippedVersion(version: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SKIPPED_VERSION] = version
        }
    }

    /** "稍后提醒"的截止时间戳（毫秒），0 表示未设置 */
    val snoozeUntilTime: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_SNOOZE_UNTIL_TIME] ?: 0L
    }

    suspend fun setSnoozeUntilTime(timeMillis: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SNOOZE_UNTIL_TIME] = timeMillis
        }
    }
    /** 用户上次通过应用内更新安装的版本号（如 "0.1.7"），空字符串表示未记录 */
    val lastInstalledUpdateVersion: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_INSTALLED_UPDATE_VERSION] ?: ""
    }

    suspend fun setLastInstalledUpdateVersion(version: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_INSTALLED_UPDATE_VERSION] = version
        }
    }
    /** 重置所有更新偏好（新版本发布时自动清除旧的跳过/延迟记录） */
    suspend fun clearUpdateSnooze() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_SKIPPED_VERSION)
            prefs.remove(KEY_SNOOZE_UNTIL_TIME)
        }
    }

    /** 清除上次应用内更新记录（当有更新的版本时调用） */
    suspend fun clearLastInstalledUpdateVersion() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_LAST_INSTALLED_UPDATE_VERSION)
        }
    }
}
