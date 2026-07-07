# Architecture — DanceTimer

> 返回索引：[AGENTS.md](../../AGENTS.md)

---

## 模块结构

```
app/src/main/java/com/example/dancetimer/
├── DanceTimerApp.kt              # Application 类（Room 初始化）
├── MainActivity.kt               # 单 Activity 宿主
├── LockScreenTimerActivity.kt    # 锁屏计时覆盖层 Activity
│
├── data/
│   ├── db/
│   │   ├── AppDatabase.kt        # Room 数据库单例
│   │   ├── DanceRecordDao.kt     # 跳舞记录查询
│   │   ├── PricingRuleDao.kt     # 计价规则查询
│   │   └── ScreenLockEventDao.kt # 锁屏事件查询
│   ├── model/                    # Room Entity + 数据模型
│   ├── preferences/              # DataStore 用户偏好
│   └── update/                   # 应用更新检查
│
├── service/
│   └── TimerForegroundService.kt # 前台计时服务（持有全局 TimerState）
│
├── ui/
│   ├── navigation/               # Compose 导航图 + Screen 密封类
│   ├── screen/                   # 各页面 Composable
│   │   └── components/           # 可复用 UI 组件
│   ├── viewmodel/                # ViewModel（每屏一个）
│   └── theme/                    # Material3 主题
│
├── util/
│   ├── CostCalculator.kt         # 费用计算（纯函数，可单元测试）
│   ├── DateRangeCalculator.kt    # 日期边界计算（统计查询专用）
│   ├── SilentAudioPlayer.kt
│   ├── VibrationHelper.kt
│   └── VolumeKeyDetector.kt
│
└── widget/
    └── DanceTimerWidgetReceiver.kt # Glance 小组件
```

---

## 数据流

```
TimerForegroundService  ──StateFlow(TimerState)──▶  HomeViewModel
                                                         │
                                                         ▼
Room (AppDatabase)  ──Flow<List<DanceRecord>>──▶  HistoryViewModel
                    ──Flow<Float>(CostInRange)──▶  (via dateTicker + flatMapLatest)
```

- **计时状态**：`TimerForegroundService.timerState` 是一个进程级 `MutableStateFlow`，UI 层通过 `HomeViewModel.timerState` 只读访问。
- **历史统计**：Room Flow 对数据库变化自动响应；时间边界通过 `dateTicker` 每日刷新，避免陈旧数据。

---

## 导航路由

| Screen | Route | ViewModel |
|--------|-------|-----------|
| HomeScreen | `home` | HomeViewModel |
| HistoryScreen | `history` | HistoryViewModel |
| RecordDetailScreen | `record_detail/{id}` | — (直接读 DAO) |
| PricingRulesScreen | `pricing_rules` | PricingRuleViewModel |
| EditRuleScreen | `edit_rule/{ruleId}` | PricingRuleViewModel |
| SettingsScreen | `settings` | SettingsViewModel |
| LockEventHistoryScreen | `lock_event_history` | HomeViewModel (shared) |
| StatsScreen | `stats` | StatsViewModel |

---

## 关键单例

| 类 | 作用 | 生命周期 |
|----|------|---------|
| `AppDatabase` | Room 数据库，伴生对象单例 | Application |
| `TimerForegroundService.timerState` | 全局计时状态 | 进程 |
| `UserPreferencesManager` | DataStore 封装 | Application |
| `DateRangeCalculator` | 无状态 object，纯函数 | — |
| `CostCalculator` | 无状态 object，纯函数 | — |
