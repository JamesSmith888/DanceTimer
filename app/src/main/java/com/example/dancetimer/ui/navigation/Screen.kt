package com.example.dancetimer.ui.navigation

/**
 * App 导航路由定义
 */
sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object PricingRules : Screen("pricing_rules")
    data object EditRule : Screen("edit_rule/{ruleId}") {
        fun createRoute(ruleId: Long = -1L) = "edit_rule/$ruleId"
    }
    data object History : Screen("history")
    data object RecordDetail : Screen("record_detail/{recordId}") {
        fun createRoute(recordId: Long) = "record_detail/$recordId"
    }
    data object Settings : Screen("settings")
}
