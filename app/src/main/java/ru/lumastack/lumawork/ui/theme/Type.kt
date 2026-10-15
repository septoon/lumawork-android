package ru.lumastack.lumawork.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val LumaTypography = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold, fontSize = 34.sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

