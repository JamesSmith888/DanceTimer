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
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_BATTERY_GUIDE_SHOWN = booleanPreferencesKey("battery_guide_shown")
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
        val value = prefs[KEY_THEME_MODE] ?: ThemeMode.DARK.name
        try { ThemeMode.valueOf(value) } catch (_: Exception) { ThemeMode.DARK }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = mode.name
        }
    }
}
