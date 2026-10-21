package ru.lumastack.lumawork.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val LumaTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp),
        bodyLarge = bodyLarge.copy(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
        bodyMedium = bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        bodySmall = bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        labelMedium = labelMedium.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
        labelSmall = labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
    )
}

