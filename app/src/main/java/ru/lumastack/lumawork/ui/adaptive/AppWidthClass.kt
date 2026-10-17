package ru.lumastack.lumawork.ui.adaptive

import kotlin.math.roundToInt

enum class AppWidthClass {
    Compact,
    Medium,
    Expanded,
}

fun appWidthClass(widthDp: Int): AppWidthClass = when {
    widthDp < MEDIUM_WIDTH_DP -> AppWidthClass.Compact
    widthDp < EXPANDED_WIDTH_DP -> AppWidthClass.Medium
    else -> AppWidthClass.Expanded
}

fun appContentWidthClass(widthDp: Int, heightDp: Int, fontScale: Float = 1f): AppWidthClass {
    if (heightDp < 480) return AppWidthClass.Compact
    val textScale = fontScale.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
    return appWidthClass((widthDp / textScale).roundToInt())
}

fun appFormHasPinnedActions(availableHeightDp: Int, fontScale: Float = 1f): Boolean {
    val textScale = fontScale.takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 1f
    return availableHeightDp >= MIN_PINNED_FORM_HEIGHT_DP * textScale
}

private const val MEDIUM_WIDTH_DP = 600
private const val EXPANDED_WIDTH_DP = 840
private const val MIN_PINNED_FORM_HEIGHT_DP = 360
