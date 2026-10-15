package ru.lumastack.lumawork.ui.adaptive

enum class AppWidthClass {
    Compact,
    Medium,
    Expanded,
}

enum class AppNavigationLayout {
    Drawer,
    Rail,
    Sidebar,
}

fun appWidthClass(widthDp: Int): AppWidthClass = when {
    widthDp < MEDIUM_WIDTH_DP -> AppWidthClass.Compact
    widthDp < EXPANDED_WIDTH_DP -> AppWidthClass.Medium
    else -> AppWidthClass.Expanded
}

fun appNavigationLayout(widthDp: Int): AppNavigationLayout = when {
    widthDp < MEDIUM_WIDTH_DP -> AppNavigationLayout.Drawer
    widthDp < WIDE_SIDEBAR_WIDTH_DP -> AppNavigationLayout.Rail
    else -> AppNavigationLayout.Sidebar
}

private const val MEDIUM_WIDTH_DP = 600
private const val EXPANDED_WIDTH_DP = 840
private const val WIDE_SIDEBAR_WIDTH_DP = 1100
