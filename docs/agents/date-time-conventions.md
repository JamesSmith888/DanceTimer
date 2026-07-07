# 日期时间规范 — DanceTimer

> 返回索引：[AGENTS.md](../../AGENTS.md)

这是本项目最重要的技术规范之一，记录了统计 bug 的根因分析与系统性修复方案。

---

## 历史 Bug：本周金额 > 本月金额

### 现象

`HistoryScreen` 统计卡片中"本周"金额高于"本月"金额。

### 根因 1：跨月周边界（Cross-Month Week Boundary）

`java.util.Calendar` 实现的 `startOfWeek()` 在月初部分场景下返回**上月日期**：

```
示例场景：设备语言区域 firstDayOfWeek = SUNDAY (1)
今日：2026-07-01（周三）
日历周：2026-06-28（日）→ 2026-07-04（六）

startOfWeek()  = 2026-06-28 00:00:00  ← 早于当月第一天！
startOfMonth() = 2026-07-01 00:00:00

结果：weekCost 包含 6/28~6/30 的记录，这些记录不在 monthCost 中
     ∴ weekCost > monthCost 成立
```

### 根因 2：静态时间边界（Stale Date Ranges）

原始代码将时间戳作为**构造时快照**传入 Room 查询：

```kotlin
// ❌ 旧代码 —— Long 值在 ViewModel 初始化时固定，永不刷新
val weekCost: Flow<Float> = dao.getCostInRange(startOfWeek(), endOfToday())
val monthCost: Flow<Float> = dao.getCostInRange(startOfMonth(), endOfToday())
```

Room Flow 对数据库变化是响应式的，但 `WHERE startTime >= :from AND startTime < :to`
中的 `from`/`to` 是固定的 `Long`。若 ViewModel 实例跨越午夜/新周/新月仍存活（App 长期驻后台），
所有统计数据将持续基于过期时间范围，后续新增记录可能被完全遗漏。

### 根因 3：`java.util.Calendar` 字段歧义

`Calendar.set(DAY_OF_WEEK, firstDayOfWeek)` 与已存在的 `DAY_OF_MONTH`、`WEEK_OF_MONTH`
之间存在字段优先级歧义（参见 Java 文档中的字段组合规则），在边界日期易产生静默错误。

---

## 修复方案

### 1. 引入 `DateRangeCalculator`（`util/DateRangeCalculator.kt`）

使用 `java.time`（AGP Core Library Desugaring，支持 API 24+），集中管理所有日期边界计算：

| 方法 | 语义 |
|------|------|
| `startOfToday()` | 今日 00:00:00.000 |
| `startOfTomorrow()` | 明日 00:00:00.000（今日查询的不含上界） |
| `startOfWeek()` | 当前日历周第一天（遵循语言区域） |
| `startOfMonth()` | 当月第一天 |
| `startOfWeekInMonth()` | `max(startOfWeek, startOfMonth)` ← 核心修复 |
| `msUntilTomorrow()` | 距下一午夜的毫秒数（用于调度刷新） |

**`startOfWeekInMonth()` 的语义**：当周跨越月边界时，将"本周"下界收窄至当月第一天，
确保 `weekCost ≤ monthCost` 在任意日期下恒成立。

### 2. 响应式日期刷新（`HistoryViewModel.dateTicker`）

```kotlin
// ✅ 新代码 —— 时间边界在每次 emit 时动态计算
private val dateTicker: Flow<Unit> = flow {
    while (true) {
        emit(Unit)
        delay(DateRangeCalculator.msUntilTomorrow().coerceAtLeast(60_000L))
    }
}.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

val weekCost: Flow<Float> = dateTicker.flatMapLatest {
    dao.getCostInRange(
        from = DateRangeCalculator.startOfWeekInMonth(),  // 每次调用时重新计算
        to   = DateRangeCalculator.startOfTomorrow()
    )
}
```

`dateTicker` 立即发射一次（初始数据），之后每个自然日午夜再次发射，触发 `flatMapLatest`
重新以当前日期计算边界并重新查询数据库。

---

## 使用规范

### ✅ 正确做法

```kotlin
// 在 ViewModel 的 flatMapLatest lambda 内调用 DateRangeCalculator
val monthCost: Flow<Float> = dateTicker.flatMapLatest {
    dao.getCostInRange(DateRangeCalculator.startOfMonth(), DateRangeCalculator.startOfTomorrow())
}
```

### ❌ 禁止做法

```kotlin
// 禁止：在 ViewModel 属性初始化时固定时间戳
val monthCost = dao.getCostInRange(startOfMonth(), endOfToday()) // 永不刷新！

// 禁止：在 ViewModel 内直接使用 Calendar
val cal = Calendar.getInstance().apply { ... }

// 禁止：硬编码时间偏移
val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 3600 * 1000L
```

### DAO 接口规范

所有时间范围查询使用左闭右开区间，`to` 参数语义为"不含上界"：

```kotlin
// ✅ 正确语义：[from, to)
@Query("SELECT ... WHERE startTime >= :from AND startTime < :to")
fun getCostInRange(from: Long, to: Long): Flow<Float>

// to 始终传入 DateRangeCalculator.startOfTomorrow()，而非 endOfToday()
// startOfTomorrow() = 明日 00:00:00，能完整覆盖今日最后一毫秒
```

---

## 测试思路

单元测试 `DateRangeCalculator` 时，需 mock `LocalDate.now()` 以固定日期，
覆盖以下边界场景：

1. 今日 = 月第一天（周在上月开始） → `startOfWeekInMonth()` 应返回 `startOfMonth()`
2. 今日 = 月第一天（周也在当月开始） → `startOfWeekInMonth()` 应返回 `startOfWeek()`
3. 今日 = 月最后一天 → 断言各边界不溢出
4. 夏令时切换日 → 验证 `msUntilTomorrow()` ≥ 0 且 ≤ 90,000 s
