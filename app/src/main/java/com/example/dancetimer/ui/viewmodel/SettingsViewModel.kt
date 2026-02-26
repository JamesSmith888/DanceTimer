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

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = UserPreferencesManager(application)
    private val updateManager = UpdateManager(application)

    val triggerMode: Flow<TriggerMode> = prefs.triggerMode

    val vibrateOnTier: Flow<Boolean> = prefs.vibrateOnTier

    val autoStartOnScreenOff: Flow<Boolean> = prefs.autoStartOnScreenOff

    val autoStartDelaySeconds: Flow<Int> = prefs.autoStartDelaySeconds

    val stepDetectionEnabled: Flow<Boolean> = prefs.stepDetectionEnabled

    val stepWalkingThreshold: Flow<Int> = prefs.stepWalkingThreshold

    val themeMode: Flow<ThemeMode> = prefs.themeMode

    // ---- 版本更新 ----

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    /** 检查更新 */
    fun checkForUpdate() {
        if (_updateState.value is UpdateState.Checking) return // 防止重复点击

        _updateState.value = UpdateState.Checking
        viewModelScope.launch {
            try {
                val info = updateManager.checkForUpdate()
                _updateState.value = if (info != null) {
                    UpdateState.Available(info)
                } else {
                    UpdateState.UpToDate
                }
            } catch (e: Exception) {
                Log.e("SettingsViewModel", "检查更新失败", e)
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
        updateManager.installApk(downloadId)
    }

    /** 取消下载 */
    fun cancelDownload() {
        updateManager.cancelActiveDownload()
        _updateState.value = UpdateState.Idle
    }

    /** 重置更新状态（关闭对话框等） */
    fun dismissUpdate() {
        // 如果正在下载，先取消
        if (_updateState.value is UpdateState.Downloading) {
            updateManager.cancelActiveDownload()
        }
        _updateState.value = UpdateState.Idle
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
