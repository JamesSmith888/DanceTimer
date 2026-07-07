package com.example.dancetimer.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.app.PendingIntent
import com.example.dancetimer.MainActivity
import com.example.dancetimer.R
import com.example.dancetimer.service.TimerForegroundService
import com.example.dancetimer.service.TimerState
import com.example.dancetimer.util.CostCalculator

/**
 * 桌面小组件 — 显示计时器状态，点击进入 App
 */
class DanceTimerWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // 每次收到广播时更新所有 widget
        if (intent.action == ACTION_UPDATE) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = android.content.ComponentName(context, DanceTimerWidgetReceiver::class.java)
            val ids = appWidgetManager.getAppWidgetIds(componentName)
            for (id in ids) {
                updateWidget(context, appWidgetManager, id)
            }
        }
    }

    companion object {
        const val ACTION_UPDATE = "com.example.dancetimer.WIDGET_UPDATE"

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // 根据计时器状态设置文字
            when (val state = TimerForegroundService.timerState.value) {
                is TimerState.Idle -> {
                    views.setTextViewText(R.id.widget_status, "准备就绪")
                    views.setTextViewText(R.id.widget_detail, "点击打开")
                }
                is TimerState.Running -> {
                    val time = CostCalculator.formatDuration(state.elapsedSeconds)
                    val cost = CostCalculator.formatCost(state.cost)
                    val totalMin = state.elapsedSeconds / 60
                    val prefix = when {
                        state.isPaused -> "⏸"
                        else -> "⏱"
                    }
                    views.setTextViewText(R.id.widget_status, "$prefix $time · $cost")
                    val songLabel = if (state.songCount > 0) "已计${state.songCount}曲" else "未满1曲"
                    views.setTextViewText(R.id.widget_detail, "${totalMin}分钟 · $songLabel · ${state.ruleName}")
                }
                is TimerState.Finished -> {
                    val cost = CostCalculator.formatCost(state.cost)
                    val totalMin = state.durationSeconds / 60
                    views.setTextViewText(R.id.widget_status, "✅ $cost · ${totalMin}分钟")
                    val finishedSongLabel = if (state.songCount > 0) "已计${state.songCount}曲" else "未满1曲"
                    views.setTextViewText(R.id.widget_detail, "$finishedSongLabel · ${state.ruleName}")
                }
            }

            // 点击打开 App
            val intent = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_status, intent)
            views.setOnClickPendingIntent(R.id.widget_detail, intent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /** 发送广播让所有 widget 刷新 */
        fun requestUpdate(context: Context) {
            val intent = Intent(context, DanceTimerWidgetReceiver::class.java).apply {
                action = ACTION_UPDATE
            }
            context.sendBroadcast(intent)
        }
    }
}
