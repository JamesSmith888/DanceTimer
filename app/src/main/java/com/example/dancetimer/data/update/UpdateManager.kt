package com.example.dancetimer.data.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import com.google.gson.reflect.TypeToken
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
        private const val REPO_OWNER = "orgYangxin"

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
     * 获取当前实际安装的版本号。
     *
     * 优先通过 [PackageManager] 读取系统包数据库中的版本，
     * 落底使用 [BuildConfig.VERSION_NAME]。
     *
     * 说明：`BuildConfig.VERSION_NAME` 是编译时常量，若 APK 构建时未及时更新
     * versionName，则与发布标签不一致。PackageManager 读取的是同一个值，
     * 但在某些设备上应用内更新后进程未重启时，PackageManager 能返回新包的版本。
     */
    fun getInstalledVersionName(): String {
        return try {
            val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pkgInfo.versionName ?: BuildConfig.VERSION_NAME
        } catch (e: Exception) {
            Log.w(TAG, "获取 PackageManager 版本失败，回退到 BuildConfig", e)
            BuildConfig.VERSION_NAME
        }
    }

    /**
     * 请求远端最新 Release 信息。
     *
     * @param lastInstalledUpdateVersion 用户上次通过应用内更新安装的版本号（可为null）。
     *   若远端版本等于此值，表示用户已通过应用内更新安装过该版本，视为已是最新。
     * @return [AppUpdateInfo] 如果有更新可用；`null` 如果已是最新；抛异常表示请求失败。
     */
    suspend fun checkForUpdate(
        lastInstalledUpdateVersion: String? = null
    ): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val response = fetchLatestRelease()
        val remoteVersion = response.tagName.trimStart('v', 'V').trim()
        val installedVersion = getInstalledVersionName().trimStart('v', 'V').trim()
        val buildConfigVersion = BuildConfig.VERSION_NAME.trimStart('v', 'V').trim()

        Log.d(TAG, "远端版本: '$remoteVersion', " +
                "PackageManager版本: '$installedVersion', " +
                "BuildConfig版本: '$buildConfigVersion', " +
                "上次应用内更新版本: '${lastInstalledUpdateVersion ?: ""}'")

        // 优先使用 PackageManager 版本（更可靠）
        val currentVersion = installedVersion

        // 若远端版本等于用户上次通过应用内更新安装的版本，视为已安装
        if (!lastInstalledUpdateVersion.isNullOrEmpty()) {
            val lastInstalled = lastInstalledUpdateVersion.trimStart('v', 'V').trim()
            if (compareVersions(remoteVersion, lastInstalled) <= 0) {
                Log.d(TAG, "远端版本 '$remoteVersion' <= 上次安装版本 '$lastInstalled'，视为已是最新")
                return@withContext null
            }
        }

        if (compareVersions(remoteVersion, currentVersion) > 0) {
            Log.d(TAG, "发现新版本: '$remoteVersion' > '$currentVersion'")
            response.toAppUpdateInfo()
        } else {
            Log.d(TAG, "已是最新版本: '$remoteVersion' <= '$currentVersion'")
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

        // 删除同名旧文件，避免 DownloadManager 追加序号或复用缓存
        try {
            val target = java.io.File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                fileName
            )
            if (target.exists()) {
                target.delete()
                Log.d(TAG, "已删除旧文件: $fileName")
            }
        } catch (e: Exception) {
            Log.w(TAG, "清理旧文件失败（忽略）: ${e.message}")
        }

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
                // Gitee 的 /releases/latest 不一定返回最高版本，改用列表接口后在客户端找最新
                "https://gitee.com/api/v5/repos/$REPO_OWNER/$REPO_NAME/releases?page=1&per_page=100&direction=desc"
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
                Platform.GITEE -> parseGiteeReleases(body)
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

    /**
     * 解析 Gitee 的 releases 列表响应，找到版本号最高的非 pre-release 版本。
     *
     * Gitee 的 `/releases/latest` 接口并不可靠地返回最新版本，
     * 因此改用列表接口 + 客户端版本号比较来筛选。
     */
    private fun parseGiteeReleases(json: String): ReleaseResponse {
        val type = object : TypeToken<List<GiteeRelease>>() {}.type
        val releases: List<GiteeRelease> = gson.fromJson(json, type)

        if (releases.isEmpty()) {
            throw UpdateException("未找到任何 Release")
        }

        // 过滤掉 prerelease，按版本号降序排列，取版本最高的
        val best = releases
            .filter { !it.prerelease }
            .maxByOrNull { release ->
                val ver = release.tagName.trimStart('v', 'V').trim()
                ver.split(".").map { it.toIntOrNull() ?: 0 }
                    .let { parts ->
                        // 转换为可比较的整数：major*1000000 + minor*1000 + patch
                        parts.getOrElse(0) { 0 } * 1_000_000L +
                        parts.getOrElse(1) { 0 } * 1_000L +
                        parts.getOrElse(2) { 0 }
                    }
            }
            ?: releases.first() // 如果全是 prerelease，取第一个

        Log.d(TAG, "Gitee releases 共 ${releases.size} 个，版本最高: ${best.tagName}")

        return ReleaseResponse(
            tagName = best.tagName,
            name = best.name ?: best.tagName,
            body = best.body ?: "",
            assets = best.assets.map { asset ->
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
        val prerelease: Boolean = false,
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

    /**
     * 判断给定的版本号是否严格新于当前应用版本。
     *
     * 同时比较 PackageManager 版本和 BuildConfig 版本，取较高者。
     * 可用于 ViewModel 层做二次校验，防止因构建/发布流程问题导致误报。
     */
    fun isNewerThanCurrent(versionName: String): Boolean {
        val remote = versionName.trimStart('v', 'V').trim()
        val pkgVersion = getInstalledVersionName().trimStart('v', 'V').trim()
        val buildVersion = BuildConfig.VERSION_NAME.trimStart('v', 'V').trim()
        // 取 PackageManager 和 BuildConfig 中较高的版本作为当前版本
        val current = if (compareVersions(pkgVersion, buildVersion) >= 0) pkgVersion else buildVersion
        return compareVersions(remote, current) > 0
    }

    /** 更新相关的异常 */
    class UpdateException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
