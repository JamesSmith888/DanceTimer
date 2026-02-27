package com.example.dancetimer.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dancetimer.data.preferences.ThemeMode
import com.example.dancetimer.data.preferences.TriggerMode
import com.example.dancetimer.data.preferences.UserPreferencesManager
import com.example.dancetimer.data.update.AppUpdateInfo
import com.example.dancetimer.data.update.UpdateManager
import com.example.dancetimer.data.update.UpdateState
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        /** "稍后提醒"延迟：3 天 */
        private const val SNOOZE_DURATION_MS = 3 * 24 * 60 * 60 * 1000L
    }

    private val prefs = UserPreferencesManager(application)
    private val updateManager = UpdateManager(application)

    val triggerMode: Flow<TriggerMode> = prefs.triggerMode

    val vibrateOnTier: Flow<Boolean> = prefs.vibrateOnTier

    val autoStartOnScreenOff: Flow<Boolean> = prefs.autoStartOnScreenOff

    val autoStartDelaySeconds: Flow<Int> = prefs.autoStartDelaySeconds

    val stepDetectionEnabled: Flow<Boolean> = prefs.stepDetectionEnabled

    val stepWalkingThreshold: Flow<Int> = prefs.stepWalkingThreshold

    val lockEventRecordEnabled: Flow<Boolean> = prefs.lockEventRecordEnabled

    val themeMode: Flow<ThemeMode> = prefs.themeMode

    // ---- 版本更新 ----

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    /** 检测到的待处理更新信息（即使对话框被关闭，只要用户未“跳过”就保留，用于设置页标记显示） */
    private val _pendingUpdateInfo = MutableStateFlow<AppUpdateInfo?>(null)

    /**
     * 对外暴露的 pendingUpdateInfo，增加安全过滤：
     * 若 pending 版本号不严格新于当前版本，则返回 null。
     * 防止因构建/发布流程问题导致“发现新版本 vX.X.X”而实际已是当前版本。
     */
    val pendingUpdateInfo: StateFlow<AppUpdateInfo?> = _pendingUpdateInfo
        .map { info ->
            if (info != null && updateManager.isNewerThanCurrent(info.tagName)) info else null
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** 防止 autoCheckForUpdate 被多处重复调用 */
    private val autoCheckDone = AtomicBoolean(false)

    /** 检查更新（手动触发） */
    fun checkForUpdate() {
        if (_updateState.value is UpdateState.Checking) return // 防止重复点击

        _updateState.value = UpdateState.Checking
        viewModelScope.launch {
            try {
                val lastInstalled = prefs.lastInstalledUpdateVersion.first()
                val info = updateManager.checkForUpdate(
                    lastInstalledUpdateVersion = lastInstalled.ifEmpty { null }
                )
                if (info != null) {
                    _pendingUpdateInfo.value = info
                    _updateState.value = UpdateState.Available(info)
                } else {
                    _pendingUpdateInfo.value = null
                    _updateState.value = UpdateState.UpToDate
                }
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "检查更新失败", e)
                _pendingUpdateInfo.value = null     // 手动检查失败时清除旧的 pending 标记
                _updateState.value = UpdateState.Error(
                    e.localizedMessage ?: "检查更新失败，请稍后重试"
                )
            }
        }
    }

    /** 开始下载 APK */
    fun startDownload(info: AppUpdateInfo) {
        val url = info.findApkDownloadUrl()
        if (url == null) {
            _updateState.value = UpdateState.Error("未找到可下载的 APK 文件")
            return
        }

        val fileName = "DanceTimer-${info.versionName()}.apk"
        try {
            val downloadId = updateManager.startDownload(url, fileName)
            _updateState.value = UpdateState.Downloading(info, 0)

            // 启动进度观测协程
            viewModelScope.launch {
                updateManager.observeDownloadProgress(
                    downloadId = downloadId,
                    onProgress = { progress ->
                        _updateState.value = UpdateState.Downloading(info, progress)
                    },
                    onComplete = {
                        _updateState.value = UpdateState.ReadyToInstall(info, downloadId)
                    },
                    onFailed = { reason ->
                        _updateState.value = UpdateState.Error(reason)
                    }
                )
            }
        } catch (e: Exception) {
            Log.e("SettingsViewModel", "启动下载失败", e)
            _updateState.value = UpdateState.Error(
                e.localizedMessage ?: "下载失败，请稍后重试"
            )
        }
    }

    /** 安装已下载的 APK */
    fun installApk(downloadId: Long) {
        if (!updateManager.canInstallPackages()) {
            updateManager.requestInstallPermission()
            return
        }
        // 记录即将安装的版本号，下次检查时若远端版本等于此值则视为已是最新
        val pendingVersion = _pendingUpdateInfo.value?.versionName()
        if (!pendingVersion.isNullOrEmpty()) {
            viewModelScope.launch {
                prefs.setLastInstalledUpdateVersion(pendingVersion)
                Log.d("SettingsViewModel", "记录即将安装的版本: $pendingVersion")
            }
        }
        updateManager.installApk(downloadId)
    }

    /** 取消下载 */
    fun cancelDownload() {
        updateManager.cancelActiveDownload()
        _updateState.value = UpdateState.Idle
    }

    /** 重置更新状态（关闭对话框，保留 pending 标记） */
    fun dismissUpdate() {
        // 如果正在下载，先取消
        if (_updateState.value is UpdateState.Downloading) {
            updateManager.cancelActiveDownload()
        }
        _updateState.value = UpdateState.Idle
        // 注意：不清除 _pendingUpdateInfo，以便设置页仍能显示新版本标记
    }

    /**
     * 用户点击"稍后提醒"：延迟 [SNOOZE_DURATION_MS]（3 天）后再提醒。
     */
    fun snoozeUpdate() {
        if (_updateState.value is UpdateState.Downloading) {
            updateManager.cancelActiveDownload()
        }
        viewModelScope.launch {
            prefs.setSnoozeUntilTime(System.currentTimeMillis() + SNOOZE_DURATION_MS)
        }
        _updateState.value = UpdateState.Idle
        // 不清除 _pendingUpdateInfo，设置页仍显示新版本标记
    }

    /**
     * 用户点击"跳过此版本"：记录被跳过的版本号，不再自动提醒。
     */
    fun skipVersion(versionName: String) {
        if (_updateState.value is UpdateState.Downloading) {
            updateManager.cancelActiveDownload()
        }
        viewModelScope.launch {
            prefs.setSkippedVersion(versionName)
        }
        _pendingUpdateInfo.value = null   // 跳过后清除待处理标记
        _updateState.value = UpdateState.Idle
    }

    /**
     * 应用启动时自动检查更新（静默）。
     *
     * 策略：
     * 1. 用户设置了"稍后提醒"且尚未到期 → 跳过弹窗
     * 2. 检测到新版本但该版本已被用户"跳过" → 不弹窗（但保留 pending 标记）
     * 3. 检查失败 → 静默忽略（不打扰用户）
     */
    fun autoCheckForUpdate() {
        // 防止 MainActivity + HomeScreen 重复调用
        if (!autoCheckDone.compareAndSet(false, true)) return

        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()

                // 0) 版本升级后清除旧的跳过/延迟记录
                val skipped = prefs.skippedVersion.first()
                if (skipped.isNotEmpty() && !updateManager.isNewerThanCurrent(skipped)) {
                    // skipped 版本已等于或低于当前版本，清除过时记录
                    prefs.clearUpdateSnooze()
                }

                // 1) 检查延迟提醒是否仍在生效
                val snoozeUntil = prefs.snoozeUntilTime.first()
                if (snoozeUntil > 0 && now < snoozeUntil) {
                    Log.d("SettingsViewModel", "延迟提醒中，跳过自动检查弹窗")
                    return@launch
                }

                // 执行检查
                val lastInstalled = prefs.lastInstalledUpdateVersion.first()
                val info = updateManager.checkForUpdate(
                    lastInstalledUpdateVersion = lastInstalled.ifEmpty { null }
                )

                if (info != null) {
                    _pendingUpdateInfo.value = info

                    // 2) 检查该版本是否被用户跳过
                    val skipped = prefs.skippedVersion.first()
                    if (skipped == info.versionName()) {
                        Log.d("SettingsViewModel", "版本 ${info.versionName()} 已被用户跳过，不弹窗但保留标记")
                        return@launch
                    }
                    // 有新版本且未被跳过 → 弹窗
                    _updateState.value = UpdateState.Available(info)
                } else {
                    _pendingUpdateInfo.value = null
                }
                // 无更新 → 静默，不设置 UpToDate（避免在 HomeScreen 显示无意义信息）
            } catch (e: Exception) {
                // 自动检查失败 → 静默忽略
                Log.d("SettingsViewModel", "自动检查更新失败（静默忽略）", e)
            }
        }
    }

    // ---- 原有设置方法 ----

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

    fun setAutoStartDelaySeconds(seconds: Int) {
        viewModelScope.launch {
            prefs.setAutoStartDelaySeconds(seconds)
        }
    }

    fun setStepDetectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setStepDetectionEnabled(enabled)
        }
    }

    fun setStepWalkingThreshold(threshold: Int) {
        viewModelScope.launch {
            prefs.setStepWalkingThreshold(threshold)
        }
    }

    fun setLockEventRecordEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setLockEventRecordEnabled(enabled)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            prefs.setThemeMode(mode)
        }
    }

    override fun onCleared() {
        super.onCleared()
        updateManager.cleanup()
    }
}
