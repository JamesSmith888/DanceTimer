# AGENTS.md — DanceTimer

AI coding agent index for the **DanceTimer** Android project.
This file is the entry point. Load sub-documents progressively as tasks require.

---

## Project Overview

DanceTimer 是一款 Android 计费计时器 App，面向舞蹈房/练功房场景，
支持手动计时、息屏自动计时、弹性计价规则和历史统计。

- **Language**: Kotlin
- **UI**: Jetpack Compose + Material3
- **Arch**: Single-Activity + Compose Navigation + MVVM (ViewModel + Room Flow)
- **Min SDK**: 24 · **Target SDK**: 35 · **Compile SDK**: 35

---

## Quick Commands

```bash
# Debug build & install
./gradlew :app:installDebug

# Release build (signed with debug key, distributable)
./gradlew :app:assembleRelease

# Run unit tests
./gradlew :app:testDebugUnitTest

# Lint
./gradlew :app:lintDebug
```

---

## Sub-document Index

| 文档 | 适用场景 |
|------|---------|
| [docs/agents/architecture.md](docs/agents/architecture.md) | 模块结构、数据流、导航图、关键类概览 |
| [docs/agents/data-layer.md](docs/agents/data-layer.md) | Room 数据库、DAO 规范、Migration 策略 |
| [docs/agents/date-time-conventions.md](docs/agents/date-time-conventions.md) | **日期边界计算规范**（统计 bug 根因 & 修复方案记录） |
| [docs/agents/ui-patterns.md](docs/agents/ui-patterns.md) | Compose 屏幕结构、ViewModel 绑定、状态管理 |
| [docs/agents/build-and-release.md](docs/agents/build-and-release.md) | 签名配置、构建变体、版本号管理 |

---

## Key Conventions (快速参考)

- **日期统计**：所有时间边界通过 `DateRangeCalculator`（`util/`）计算，禁止在 ViewModel 内直接使用 `Calendar` 或硬编码时间偏移。详见 [date-time-conventions.md](docs/agents/date-time-conventions.md)。
- **Flow 响应式**：ViewModel 的日期相关 Flow 通过 `dateTicker + flatMapLatest` 驱动，确保跨日/周/月存活时自动刷新。
- **费用计算**：所有费用逻辑集中在 `CostCalculator`（`util/`），半曲中点计费 + 停止缓冲期。
- **DAO 命名**：`getCostInRange(from, to)` 使用左闭右开区间 `[from, to)`，`to` 始终传入 `DateRangeCalculator.startOfTomorrow()`。
