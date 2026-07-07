# DanceTimer

舞厅按曲计费计时器，支持音量键快捷操作、息屏自动计时、通知栏实时显示，即使在锁屏或后台也能稳定运行。


## 计费说明

采用 **半曲中点计费** 模式：

| 场景（4分钟/曲 · ¥20） | 已跳时间 | 计费曲数 | 费用 |
|---|---|---|---|
| 刚开始 | 0 ~ 1:59 | 0 曲 | ¥0 |
| 跳过半曲 | 2:00 ~ 5:59 | 1 曲 | ¥20 |
| 跳过一曲半 | 6:00 ~ 9:59 | 2 曲 | ¥40 |

公式：`计费曲数 = floor(已跳秒数 / 每曲秒数 + 0.5)`


## 项目结构

```
app/src/main/java/com/example/dancetimer/
├── data/
│   ├── db/           # Room 数据库（计价规则、历史记录）
│   ├── model/        # 数据实体（PricingRule, PriceTier, DanceRecord）
│   └── preferences/  # DataStore 用户偏好
├── service/
│   ├── TimerState.kt               # 计时器状态（Idle / Running / Finished）
│   └── TimerForegroundService.kt   # 核心前台服务
├── ui/
│   ├── navigation/   # 导航路由
│   ├── theme/        # 主题色彩 / 字体
│   ├── viewmodel/    # ViewModel 层
│   └── screen/       # Compose 页面（首页、历史、规则编辑、设置等）
├── util/             # 计费引擎、音量键检测、震动、静音播放
└── widget/           # 桌面小组件
```

## 构建与运行

### 环境要求

- Android Studio Hedgehog（2023.1.1）或更高版本
- JDK 11+（`compileOptions` 固定 Java 11）
- Android SDK 35

### 常用命令

```bash
./gradlew installDebug

```bash
# 编译 Debug APK（不安装）
./gradlew :app:assembleDebug
# 输出：app/build/outputs/apk/debug/app-debug.apk

# 编译并直接安装到已连接的 USB 设备（推荐开发流程）
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk

# 编译 Release APK（debug keystore 签名，可直接分发安装）
./gradlew :app:assembleRelease
# 输出：app/build/outputs/apk/release/app-release.apk

# 安装 Release APK 到设备
adb install -r app/build/outputs/apk/release/app-release.apk

# 单元测试
./gradlew :app:testDebugUnitTest

# Lint 检查
./gradlew :app:lintDebug

# 查看已连接设备
adb devices

# 启动 App（安装后）
adb shell am start -n com.example.dancetimer/.MainActivity
```

### USB 调试步骤

1. 手机进入 **设置 → 开发者选项**，开启 **USB 调试**
2. USB 连接电脑后，手机上点击 **"允许 USB 调试"** 弹窗
3. 运行 `adb devices`，确认设备状态为 `device`（非 `unauthorized`）
4. 执行上方安装命令

## 发版流程（Gitee Release）

### 1. 更新版本号

编辑 `app/build.gradle.kts`：

```kotlin
versionCode = N        // 每次发布递增（整数）
versionName = "X.Y.Z"  // 语义化版本号，对应 Gitee Tag
```

### 2. 打包 Release APK

```bash
./gradlew :app:assembleRelease
# 输出：app/build/outputs/apk/release/app-release.apk
```

签名使用系统 debug keystore（`~/.android/debug.keystore`），无需额外证书即可安装分发。

### 3. 在 Gitee 创建 Release

1. 进入仓库 → **发行版 → 创建 Release**
2. Tag 名格式：`vX.Y.Z`（必须以 `v` 开头，如 `v0.1.11`）
3. 将 APK 重命名后上传为 Release Asset，如 `DanceTimer-v0.1.11-release.apk`
4. **不要**勾选 prerelease（客户端会跳过预发布版本）
5. 发布

### 检查更新机制

- 平台：Gitee（`orgYangxin/DanceTimer`）
- 接口：`/api/v5/repos/.../releases?page=1&per_page=100&direction=desc`
- 逻辑：获取所有非 prerelease Release → 客户端按版本号选最高版本 → 与本地版本对比 → 有新版则提示下载安装

---

## OEM 兼容性

针对国产手机（OPPO / realme / OnePlus / 小米等）的后台限制做了专项适配：

- **双重计时机制** — Handler（1 秒主循环）+ AlarmManager（30 秒备份唤醒），防止进程被冻结后计时停滞
- **静音音频流** — 零振幅 PCM 播放保持 MediaSession 激活，确保锁屏音量键路由到 App
- **电池优化引导** — 内置图文指引，帮助用户关闭电池优化以保证后台稳定运行

## License

MIT
