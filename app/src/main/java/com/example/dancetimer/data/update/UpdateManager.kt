package com.example.dancetimer.data.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.example.dancetimer.BuildConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用版本更新管理器。
 *
 * 职责：
 * 1. 请求远端 Release API 获取最新版本信息
 * 2. 比较版本号判断是否需要更新
 * 3. 通过系统 [DownloadManager] 下载 APK
 * 4. 监听下载进度并触发安装
 *
 * 当前使用 **GitHub Releases API**；后续切换 Gitee 只需修改 [releaseUrl]。
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"

        // ====== 远端仓库配置（切换平台时只改这里） ======

        /** 平台类型：GITHUB / GITEE */
        private val PLATFORM = Platform.GITEE

        /** 仓库所有者 */
        private const val REPO_OWNER = "JamesSmith888"

        /** 仓库名 */
        private const val REPO_NAME = "DanceTimer"

        /** 请求超时（毫秒） */
        private const val CONNECT_TIMEOUT = 15_000
        private const val READ_TIMEOUT = 15_000

        /** 下载进度轮询间隔（毫秒） */
        private const val PROGRESS_POLL_INTERVAL = 500L
    }

    /** 支持的代码托管平台 */
    enum class Platform {
        GITHUB, GITEE
    }

    private val gson = Gson()

    /** 当前活跃的 download ID，-1 表示无 */
    private var activeDownloadId: Long = -1L

    /** 下载完成广播接收器 */
    private var downloadReceiver: BroadcastReceiver? = null

    // ---------- 公开 API ----------

    /**
     * 请求远端最新 Release 信息。
     *
     * @return [AppUpdateInfo] 如果有更新可用；`null` 如果已是最新；抛异常表示请求失败。
     */
    suspend fun checkForUpdate(): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val response = fetchLatestRelease()
        val remoteVersion = response.tagName.trimStart('v', 'V')
        val currentVersion = BuildConfig.VERSION_NAME

        Log.d(TAG, "远端版本: $remoteVersion, 当前版本: $currentVersion")

        if (compareVersions(remoteVersion, currentVersion) > 0) {
            response.toAppUpdateInfo()
        } else {
            null
        }
    }

    /**
     * 通过系统 DownloadManager 下载 APK。
     *
     * @param url APK 下载直链
     * @param fileName 保存的文件名
     * @return download ID
     */
    fun startDownload(url: String, fileName: String): Long {
        cancelActiveDownload()

        val dm = context.getSystemService<DownloadManager>()
            ?: throw IllegalStateException("DownloadManager 不可用")

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("DanceTimer 更新")
            setDescription("正在下载新版本…")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
        }

        activeDownloadId = dm.enqueue(request)
        Log.d(TAG, "开始下载: id=$activeDownloadId, url=$url")
        return activeDownloadId
    }

    /**
     * 轮询当前下载进度，通过 [StateFlow] 发射 0–100 或 -1（未知）。
     * 在下载完成或失败时自动终止。
     */
    suspend fun observeDownloadProgress(
        downloadId: Long,
        onProgress: (progress: Int) -> Unit,
        onComplete: () -> Unit,
        onFailed: (reason: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val dm = context.getSystemService<DownloadManager>() ?: return@withContext

        while (isActive) {
            val query = DownloadManager.Query().setFilterById(downloadId)
            val cursor: Cursor? = dm.query(query)

            if (cursor == null || !cursor.moveToFirst()) {
                cursor?.close()
                onFailed("下载任务已丢失")
                return@withContext
            }

            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            when (status) {
                DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING -> {
                    val total = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    )
                    val downloaded = cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    )
                    val progress = if (total > 0) ((downloaded * 100) / total).toInt() else -1
                    withContext(Dispatchers.Main) { onProgress(progress) }
                }

                DownloadManager.STATUS_SUCCESSFUL -> {
                    cursor.close()
                    withContext(Dispatchers.Main) {
                        onProgress(100)
                        onComplete()
                    }
                    return@withContext
                }

                DownloadManager.STATUS_FAILED -> {
                    val reason = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                    )
                    cursor.close()
                    withContext(Dispatchers.Main) { onFailed("下载失败 (错误码: $reason)") }
                    return@withContext
                }
            }
            cursor.close()
            delay(PROGRESS_POLL_INTERVAL)
        }
    }

    /**
     * 触发 APK 安装。
     *
     * @param downloadId DownloadManager 返回的下载 ID
     */
    fun installApk(downloadId: Long) {
        val dm = context.getSystemService<DownloadManager>() ?: return
        val uri = dm.getUriForDownloadedFile(downloadId) ?: run {
            Log.e(TAG, "无法获取下载文件 URI, downloadId=$downloadId")
            return
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    /**
     * 检查是否拥有安装未知来源应用的权限（API 26+）。
     */
    fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * 跳转到"安装未知应用"权限设置页。
     */
    fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    /** 取消当前活跃的下载任务 */
    fun cancelActiveDownload() {
        if (activeDownloadId != -1L) {
            context.getSystemService<DownloadManager>()?.remove(activeDownloadId)
            activeDownloadId = -1L
        }
    }

    /** 清理资源（Activity/ViewModel onCleared 时调用） */
    fun cleanup() {
        cancelActiveDownload()
        downloadReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (_: Exception) { }
        }
        downloadReceiver = null
    }

    // ---------- 内部实现 ----------

    /** 根据平台类型构建 Release API URL */
    private val releaseUrl: String
        get() = when (PLATFORM) {
            Platform.GITHUB ->
                "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
            Platform.GITEE ->
                "https://gitee.com/api/v5/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
        }

    /**
     * HTTP GET 请求远端 Release API，返回平台无关的内部响应对象。
     */
    private fun fetchLatestRelease(): ReleaseResponse {
        val url = URL(releaseUrl)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            setRequestProperty("Accept", "application/json")
            // GitHub API 推荐使用 User-Agent
            setRequestProperty("User-Agent", "DanceTimer-Android/${BuildConfig.VERSION_NAME}")
        }

        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val errorBody = conn.errorStream?.bufferedReader()?.readText() ?: ""
                throw UpdateException("服务器返回 $code: $errorBody")
            }

            val body = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use {
                it.readText()
            }

            return when (PLATFORM) {
                Platform.GITHUB -> parseGitHubRelease(body)
                Platform.GITEE -> parseGiteeRelease(body)
            }
        } finally {
            conn.disconnect()
        }
    }

    // ---- GitHub JSON 解析 ----

    private fun parseGitHubRelease(json: String): ReleaseResponse {
        val gh = gson.fromJson(json, GitHubRelease::class.java)
        return ReleaseResponse(
            tagName = gh.tagName,
            name = gh.name ?: gh.tagName,
            body = gh.body ?: "",
            assets = gh.assets.map { asset ->
                ReleaseAsset(
                    name = asset.name,
                    downloadUrl = asset.browserDownloadUrl
                )
            }
        )
    }

    /** GitHub Releases API 响应（部分字段） */
    private data class GitHubRelease(
        @SerializedName("tag_name") val tagName: String,
        val name: String?,
        val body: String?,
        val assets: List<GitHubAsset>
    )

    private data class GitHubAsset(
        val name: String,
        @SerializedName("browser_download_url") val browserDownloadUrl: String
    )

    // ---- Gitee JSON 解析 ----

    private fun parseGiteeRelease(json: String): ReleaseResponse {
        val gt = gson.fromJson(json, GiteeRelease::class.java)
        return ReleaseResponse(
            tagName = gt.tagName,
            name = gt.name ?: gt.tagName,
            body = gt.body ?: "",
            assets = gt.assets.map { asset ->
                ReleaseAsset(
                    name = asset.name,
                    downloadUrl = asset.browserDownloadUrl
                )
            }
        )
    }

    /** Gitee Releases API 响应（部分字段） */
    private data class GiteeRelease(
        @SerializedName("tag_name") val tagName: String,
        val name: String?,
        val body: String?,
        val assets: List<GiteeAsset>
    )

    private data class GiteeAsset(
        val name: String,
        @SerializedName("browser_download_url") val browserDownloadUrl: String
    )

    // ---- 平台无关的内部数据 ----

    private data class ReleaseResponse(
        val tagName: String,
        val name: String,
        val body: String,
        val assets: List<ReleaseAsset>
    ) {
        fun toAppUpdateInfo() = AppUpdateInfo(
            tagName = tagName,
            name = name,
            body = body,
            assets = assets.map { AppUpdateInfo.Asset(it.name, it.downloadUrl) }
        )
    }

    private data class ReleaseAsset(
        val name: String,
        val downloadUrl: String
    )

    // ---- 版本号比较 ----

    /**
     * 语义化版本比较。
     *
     * 支持格式：`1.0`、`1.0.1`、`1.0.0.1` 等任意长度的 dot-separated 数字。
     *
     * @return 正数表示 [v1] > [v2]；0 表示相等；负数表示 [v1] < [v2]
     */
    internal fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(parts1.size, parts2.size)

        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1 - p2
        }
        return 0
    }

    /** 更新相关的异常 */
    class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
