package com.example.dancetimer.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dancetimer.data.db.AppDatabase
import com.example.dancetimer.data.model.PricingRuleWithTiers
import com.example.dancetimer.data.model.ScreenLockEvent
import com.example.dancetimer.data.preferences.TriggerMode
import com.example.dancetimer.data.preferences.UserPreferencesManager
import com.example.dancetimer.service.TimerForegroundService
import com.example.dancetimer.service.TimerState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /** 锁屏事件历史页每页加载条数 */
        private const val LOCK_EVENTS_PAGE_SIZE = 50
    }

    private val prefs = UserPreferencesManager(application)
    private val db = AppDatabase.getInstance(application)

    val timerState: StateFlow<TimerState> = TimerForegroundService.timerState

    val defaultRule: Flow<PricingRuleWithTiers?> = db.pricingRuleDao().getDefaultRuleWithTiersFlow()

    val triggerMode: Flow<TriggerMode> = prefs.triggerMode

    // ── 锁屏事件记录 ──

    /** 锁屏事件记录功能是否启用 */
    val lockEventRecordEnabled: Flow<Boolean> = prefs.lockEventRecordEnabled

    /** 最近 1 小时的锁屏事件（按时间降序） */
    val recentLockEvents: Flow<List<ScreenLockEvent>> =
        db.screenLockEventDao().getRecentEvents(
            sinceTimestamp = System.currentTimeMillis() - 3600 * 1000L
        )

    /** 全部锁屏事件（历史页用） */
    val allLockEvents: Flow<List<ScreenLockEvent>> =
        db.screenLockEventDao().getAllEvents()

    // ── 锁屏事件分页 ──

    /** 分页加载的锁屏事件列表 */
    private val _pagedLockEvents = MutableStateFlow<List<ScreenLockEvent>>(emptyList())
    val pagedLockEvents: StateFlow<List<ScreenLockEvent>> = _pagedLockEvents.asStateFlow()

    /** 是否正在加载更多 */
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    /** 是否还有更多数据 */
    private val _hasMoreEvents = MutableStateFlow(true)
    val hasMoreEvents: StateFlow<Boolean> = _hasMoreEvents.asStateFlow()

    /** 锁屏事件总数 */
    val totalLockEventCount: Flow<Int> = db.screenLockEventDao().getTotalEventCount()

    private var lockEventsPage = 0

    /**
     * 加载锁屏事件分页。
     * @param reset 是否重置（首次进入 / 下拉刷新）
     */
    fun loadLockEventsPage(reset: Boolean = false) {
        if (!reset && (_isLoadingMore.value || !_hasMoreEvents.value)) return
        viewModelScope.launch {
            _isLoadingMore.value = true
            if (reset) {
                lockEventsPage = 0
            }
            val offset = lockEventsPage * LOCK_EVENTS_PAGE_SIZE
            val events = db.screenLockEventDao().getEventsPaged(LOCK_EVENTS_PAGE_SIZE, offset)
            _pagedLockEvents.value = if (reset) events else _pagedLockEvents.value + events
            _hasMoreEvents.value = events.size >= LOCK_EVENTS_PAGE_SIZE
            lockEventsPage++
            _isLoadingMore.value = false
        }
    }

    /** 所有规则（供锁屏事件列表切换用） */
    val allRulesWithTiers: Flow<List<PricingRuleWithTiers>> =
        db.pricingRuleDao().getAllRulesWithTiers()

    /** 当前选中用于锁屏事件计算的规则 ID（默认跟随 defaultRuleId） */
    private val _selectedLockEventRuleId = MutableStateFlow<Long?>(null)
    val selectedLockEventRuleId: StateFlow<Long?> = _selectedLockEventRuleId.asStateFlow()

    fun setSelectedLockEventRuleId(ruleId: Long) {
        _selectedLockEventRuleId.value = ruleId
    }

    /** 从锁屏事件回溯启动计时 */
    fun startTimerFromLockEvent(event: ScreenLockEvent) {
        TimerForegroundService.startFromLockEvent(getApplication(), event)
    }

    /** 以锁屏事件直接计费（跳过计时中，直接进入计时结束页） */
    fun finishFromLockEvent(event: ScreenLockEvent) {
        viewModelScope.launch {
            TimerForegroundService.finishFromLockEvent(getApplication(), event)
        }
    }

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

    fun confirmAutoTimer() {
        TimerForegroundService.confirmAutoStart(getApplication())
    }

    /** 删除单条锁屏事件 */
    fun deleteLockEvent(id: Long) {
        viewModelScope.launch {
            db.screenLockEventDao().deleteById(id)
            // 同步更新分页内存列表
            _pagedLockEvents.value = _pagedLockEvents.value.filter { it.id != id }
        }
    }

    /** 清除全部锁屏事件 */
    fun clearAllLockEvents() {
        viewModelScope.launch {
            db.screenLockEventDao().deleteAll()
            // 重置分页状态
            _pagedLockEvents.value = emptyList()
            _hasMoreEvents.value = false
            lockEventsPage = 0
        }
    }
}
