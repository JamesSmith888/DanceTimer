# UI Patterns — DanceTimer

> 返回索引：[AGENTS.md](../../AGENTS.md)

---

## 屏幕结构

每个路由对应一个顶层 `@Composable` 函数（文件位于 `ui/screen/`），
通过 `viewModel()` 绑定对应 ViewModel：

```kotlin
@Composable
fun XxxScreen(
    navController: NavHostController,
    viewModel: XxxViewModel = viewModel()
) { ... }
```

可复用 UI 组件放在 `ui/screen/components/`，特定屏幕的复杂内容块放在 `ui/screen/content/`。

---

## ViewModel 状态管理规范

### Flow → State 转换

```kotlin
// 在 Composable 内收集 Flow
val records by viewModel.allRecords.collectAsState(initial = emptyList())
```

### 日期相关 Flow（重要）

所有日期边界敏感的 Flow 必须通过 `dateTicker + flatMapLatest` 驱动，
**不得**在 ViewModel 属性初始化时固定时间戳。详见 [date-time-conventions.md](date-time-conventions.md)。

### StateFlow vs Flow

- 对 UI 频繁读取的状态（计时器状态、分页列表）使用 `StateFlow`
- 对数据库来源的响应式数据使用 `Flow`，在 Composable 内 `collectAsState`

### 跨 Composition 存活的业务状态（重要）

**禁止**将需要跨 Composition 生命周期存活的业务状态存储在 Composable `remember {}` 中。

**场景**：当 UI 状态机切换（如 `timerState: Idle → Running → Idle`）会导致某 Composable 离开并重新进入 Composition 树时，其内部所有 `remember {}` 状态会被销毁并重置。

**规范**：此类状态必须提升到 ViewModel（`MutableStateFlow`），由 ViewModel 持有，生命周期与 Activity 绑定。

```kotlin
// ✅ 正确：在 ViewModel 持有跨 Composition 状态
private val _usedLockEventIds = MutableStateFlow(emptySet<Long>())
val usedLockEventIds: StateFlow<Set<Long>> = _usedLockEventIds.asStateFlow()
fun markLockEventUsed(id: Long) { _usedLockEventIds.update { it + id } }

// ❌ 错误：存在 Composable remember 中，Composition 重建后丢失
var usedEventIds by remember { mutableStateOf(emptySet<Long>()) }
```

`remember {}` 仅适用于纯 UI 临时状态（弹窗展开/折叠、动画触发等），不适用于需持久到下次 Idle 进入的业务标记。

---

## 导航规范

路由定义在 `ui/navigation/Screen.kt`（密封类），导航图在 `AppNavigation.kt`。

```kotlin
// 跳转带参数路由
navController.navigate(Screen.RecordDetail.createRoute(record.id)) {
    launchSingleTop = true
}
```

---

## 主题

`ui/theme/` 下定义 Material3 主题，颜色、排版、形状均通过 `MaterialTheme` 访问，
禁止在 Composable 内硬编码颜色值。
