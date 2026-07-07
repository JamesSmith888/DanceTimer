# Data Layer — DanceTimer

> 返回索引：[AGENTS.md](../../AGENTS.md)

---

## Room 数据库

**类**：`AppDatabase`（`data/db/AppDatabase.kt`）  
**版本**：参见 `@Database(version = N)` 注解  
**单例**：通过 `AppDatabase.getInstance(context)` 获取，不得直接构造

### 实体（Entities）

| Entity | 表名 | 说明 |
|--------|------|------|
| `DanceRecord` | `dance_records` | 每次计时记录，含费用快照、触发方式、计价规则快照 |
| `PricingRule` | `pricing_rules` | 计价规则元数据（名称、默认标志） |
| `PriceTier` | `price_tiers` | 价格档位（关联 PricingRule） |
| `ScreenLockEvent` | `screen_lock_events` | 息屏事件记录（用于自动触发回溯） |

---

## DAO 规范

### DanceRecordDao

| 方法 | 说明 |
|------|------|
| `getAll(): Flow<List<DanceRecord>>` | 全量记录（倒序），UI 列表用 |
| `getCostInRange(from, to): Flow<Float>` | 左闭右开时间范围费用汇总，**to = `DateRangeCalculator.startOfTomorrow()`** |
| `getByDateRange(start, end)` | 按日期范围筛选记录列表 |
| `insert`, `delete`, `deleteById`, `deleteAll` | 写操作，均为 `suspend` |

> ⚠️ `getTodayCost` 已删除（原无上界查询，被 `getCostInRange` 统一替代）。

### 时间范围约定

- 所有时间字段存储**毫秒时间戳**（`System.currentTimeMillis()`）
- 查询区间统一使用 **左闭右开**：`startTime >= :from AND startTime < :to`
- `to` 统一传入 `DateRangeCalculator.startOfTomorrow()`，不得使用 `endOfToday()` 或硬编码 23:59:59

---

## Migration 策略

- Schema 变更必须添加 Room Migration，禁止在生产版本使用 `fallbackToDestructiveMigration()`
- Migration 文件命名：`MigrationX_Y.kt`（X → Y 版本号）
- 每次 Migration 后更新 `AppDatabase` 的 `exportSchema = true` 导出文件（位于 `schemas/`）

---

## DataStore

**类**：`UserPreferencesManager`（`data/preferences/`）  
存储用户偏好：计价规则选择、触发模式、锁屏事件记录开关等。  
使用 `Preferences DataStore`（Proto DataStore 暂未引入）。
