# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ---- DanceTimer: 版本更新 Gson 反序列化 ----
# 保留 AppUpdateInfo 及其内部类（Gson 通过反射读取字段名）
-keep class com.example.dancetimer.data.update.AppUpdateInfo { *; }
-keep class com.example.dancetimer.data.update.AppUpdateInfo$Asset { *; }
# 保留 UpdateManager 内部的 API 响应 DTO（Gson @SerializedName 注解依赖反射）
-keep class com.example.dancetimer.data.update.UpdateManager$*Release { *; }
-keep class com.example.dancetimer.data.update.UpdateManager$*Asset { *; }