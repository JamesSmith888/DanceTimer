package com.example.dancetimer.data.update

/**
 * 从远端 Release API 解析出的版本更新信息。
 *
 * 字段名与 GitHub / Gitee Releases API 的 JSON key 保持一致，
 * 由 Gson 直接反序列化。若后续切换数据源，只需调整字段映射即可。
 */
data class AppUpdateInfo(
    /** Release 标签，如 "v1.1" */
    val tagName: String,
    /** Release 标题 */
    val name: String,
    /** 更新日志（Markdown 格式） */
    val body: String,
    /** Release 附件列表 */
    val assets: List<Asset>
) {
    /**
     * Release 附件（APK 文件等）
     */
    data class Asset(
        /** 文件名，如 "DanceTimer-v1.1-release.apk" */
        val name: String,
        /** 浏览器可直接下载的 URL */
        val browserDownloadUrl: String
    )

    /**
     * 从 assets 中找到第一个 .apk 文件的下载链接。
     * 若无匹配返回 null。
     */
    fun findApkDownloadUrl(): String? =
        assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            ?.browserDownloadUrl

    /**
     * 提取纯版本号字符串（去掉前缀 "v" / "V"）。
     * 例如 "v1.2.3" → "1.2.3"
     */
    fun versionName(): String = tagName.trimStart('v', 'V')
}
