package com.example.dancetimer.data.update

/**
 * 版本更新流程的 UI 状态。
 */
sealed interface UpdateState {

    /** 空闲 — 未进行任何更新操作 */
    data object Idle : UpdateState

    /** 正在请求远端版本信息 */
    data object Checking : UpdateState

    /** 已是最新版本 */
    data object UpToDate : UpdateState

    /** 检测到可用新版本 */
    data class Available(val info: AppUpdateInfo) : UpdateState

    /** APK 下载中 */
    data class Downloading(
        val info: AppUpdateInfo,
        /** 0 – 100；-1 表示进度未知 */
        val progress: Int = -1
    ) : UpdateState

    /** 下载完成，等待用户确认安装 */
    data class ReadyToInstall(
        val info: AppUpdateInfo,
        val downloadId: Long
    ) : UpdateState

    /** 出错（网络异常、解析失败等） */
    data class Error(val message: String) : UpdateState
}
