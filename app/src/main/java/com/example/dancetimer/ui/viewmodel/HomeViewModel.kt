package com.example.dancetimer.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dancetimer.data.db.AppDatabase
import com.example.dancetimer.data.model.PricingRuleWithTiers
import com.example.dancetimer.data.preferences.TriggerMode
import com.example.dancetimer.data.preferences.UserPreferencesManager
import com.example.dancetimer.service.TimerForegroundService
import com.example.dancetimer.service.TimerState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = UserPreferencesManager(application)
    private val db = AppDatabase.getInstance(application)

    val timerState: StateFlow<TimerState> = TimerForegroundService.timerState

    val defaultRule: Flow<PricingRuleWithTiers?> = db.pricingRuleDao().getDefaultRuleWithTiersFlow()

    val triggerMode: Flow<TriggerMode> = prefs.triggerMode

    fun startTimer() {
        TimerForegroundService.startTimer(getApplication())
    }

    fun stopTimer() {
        TimerForegroundService.stopTimer(getApplication())
    }

    fun pauseTimer() {
        TimerForegroundService.pauseTimer(getApplication())
    }

    fun resumeTimer() {
        TimerForegroundService.resumeTimer(getApplication())
    }

    fun dismissResult() {
        TimerForegroundService.resetToIdle()
    }

    fun cancelAutoStart() {
        TimerForegroundService.cancelAutoStart(getApplication())
    }
}
