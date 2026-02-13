package com.example.dancetimer.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dancetimer.data.preferences.ThemeMode
import com.example.dancetimer.data.preferences.TriggerMode
import com.example.dancetimer.data.preferences.UserPreferencesManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = UserPreferencesManager(application)

    val triggerMode: Flow<TriggerMode> = prefs.triggerMode

    val vibrateOnTier: Flow<Boolean> = prefs.vibrateOnTier

    val autoStartOnScreenOff: Flow<Boolean> = prefs.autoStartOnScreenOff

    val themeMode: Flow<ThemeMode> = prefs.themeMode

    fun setTriggerMode(mode: TriggerMode) {
        viewModelScope.launch {
            prefs.setTriggerMode(mode)
        }
    }

    fun setVibrateOnTier(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setVibrateOnTier(enabled)
        }
    }

    fun setAutoStartOnScreenOff(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setAutoStartOnScreenOff(enabled)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            prefs.setThemeMode(mode)
        }
    }
}
