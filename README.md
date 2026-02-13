# DanceTimer

舞厅按曲计费计时器，专为舞厅场景设计。支持音量键快捷操作、息屏自动计时、通知栏实时显示，即使在锁屏或后台也能稳定运行。

## 功能亮点

- **按曲计费** — 自定义计价规则（每曲时长 + 单价），支持多套规则切换
- **半曲中点计费** — 超过半曲才计费，避免刚开始就扣费（如 4 分钟/曲，跳满 2 分钟才算 1 曲）
- **停止缓冲** — 每曲结束后 30 秒内停止不多扣，防止操作延迟导致多收费
- **音量键控制** — 锁屏状态下长按音量键即可开始/停止，无需亮屏解锁
- **息屏自动计时** — 开启后息屏即自动开始，15 秒内可取消（防误触）
- **通知栏实时显示** — 显示已跳分钟、曲数、费用，Chronometer 系统级走秒不受进程冻结影响
- **历史记录** — 按日期分组，支持今日/本周/本月费用统计
- **桌面小组件** — 主屏幕一览计时状态
- **深色/浅色主题** — 支持跟随系统或手动切换

## 计费说明

采用 **半曲中点计费** 模式：

| 场景（4分钟/曲 · ¥20） | 已跳时间 | 计费曲数 | 费用 |
|---|---|---|---|
| 刚开始 | 0 ~ 1:59 | 0 曲 | ¥0 |
| 跳过半曲 | 2:00 ~ 5:59 | 1 曲 | ¥20 |
| 跳过一曲半 | 6:00 ~ 9:59 | 2 曲 | ¥40 |

公式：`计费曲数 = floor(已跳秒数 / 每曲秒数 + 0.5)`

## 技术栈

| 类别 | 技术选型 |
|---|---|
| UI | Jetpack Compose + Material 3 |
| 架构 | MVVM（ViewModel + StateFlow） |
| 数据库 | Room |
| 偏好设置 | DataStore Preferences |
| 后台服务 | Foreground Service + WakeLock |
| 锁屏控制 | MediaSession + VolumeProvider |
| 小组件 | AppWidgetProvider + RemoteViews |
| 语言 | Kotlin |
| 最低版本 | Android 7.0（API 24） |

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

```bash
# 克隆项目
git clone https://github.com/JamesSmith888/DanceTimer.git
cd DanceTimer

# 编译 Debug APK
./gradlew assembleDebug

# 安装到设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**环境要求：**
- Android Studio Ladybug 或更高版本
- JDK 11+
- Android SDK 35

## OEM 兼容性

针对国产手机（OPPO / realme / OnePlus / 小米等）的后台限制做了专项适配：

- **双重计时机制** — Handler（1 秒主循环）+ AlarmManager（30 秒备份唤醒），防止进程被冻结后计时停滞
- **静音音频流** — 零振幅 PCM 播放保持 MediaSession 激活，确保锁屏音量键路由到 App
- **电池优化引导** — 内置图文指引，帮助用户关闭电池优化以保证后台稳定运行

## License

MIT
