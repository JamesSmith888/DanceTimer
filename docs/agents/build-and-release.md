# Build & Release — DanceTimer

> 返回索引：[AGENTS.md](../../AGENTS.md)

---

## 环境要求

- Android Studio Hedgehog+ 或命令行 Gradle 8.x
- JDK 11（`compileOptions` 中已固定）
- AGP 8.8.2 · Kotlin 2.0.0

---

## 构建命令

```bash
# Debug 安装到已连接设备
./gradlew :app:installDebug

# Release APK（使用 debug keystore 签名，无需额外配置）
./gradlew :app:assembleRelease
# 输出：app/build/outputs/apk/release/app-release.apk

# 单元测试
./gradlew :app:testDebugUnitTest

# Lint 检查（release 构建不检查，已在 build.gradle.kts 中配置）
./gradlew :app:lintDebug

# 清理构建缓存
./gradlew :app:clean
```

---

## 签名配置

Release 构建使用系统 debug keystore 签名（`~/.android/debug.keystore`），
无需额外证书即可分发安装。配置位于 `app/build.gradle.kts` 的 `signingConfigs.releaseWithDebugKey`。

> ⚠️ 此方案仅适用于个人/内测分发，不适用于 Google Play 上架。

---

## 版本号管理

在 `app/build.gradle.kts` 的 `defaultConfig` 中维护：

```kotlin
versionCode = N        // 每次发布递增（整数）
versionName = "X.Y.Z"  // 语义化版本号
```

---

## Core Library Desugaring

项目已启用 AGP Core Library Desugaring（`isCoreLibraryDesugaringEnabled = true`），
支持在 API 24+ 设备上使用 `java.time` API。
依赖版本在 `gradle/libs.versions.toml` 的 `desugarJdkLibs` 中维护。
