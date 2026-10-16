package ru.lumastack.lumawork.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRefreshContainer(
    modifier: Modifier = Modifier,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    enabled: Boolean = true,
    contentAlignment: Alignment = Alignment.TopCenter,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    Box(
        modifier.pullToRefresh(isRefreshing = isRefreshing, state = state, enabled = enabled,
            onRefresh = { if (enabled && !isRefreshing) onRefresh() }),
        contentAlignment = contentAlignment,
    ) {
        content()
        if (enabled || isRefreshing) PullToRefreshDefaults.Indicator(
            state = state, isRefreshing = isRefreshing, modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}
