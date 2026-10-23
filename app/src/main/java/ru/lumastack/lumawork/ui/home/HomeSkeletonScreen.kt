package ru.lumastack.lumawork.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import ru.lumastack.lumawork.ui.theme.AppCard
import ru.lumastack.lumawork.ui.theme.appScreenBackground

/** Same three loading sections as iOS, fitted to the Android home layout. */
@Composable
fun HomeSkeletonScreen() {
    val transition = rememberInfiniteTransition(label = "home-loading")
    // InfiniteTransition respects the system animation duration scale, including disabled animations.
    val phase = transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1550, easing = LinearEasing), RepeatMode.Restart), label = "shimmer")
    BoxWithConstraints(Modifier.fillMaxSize().background(appScreenBackground()).safeDrawingPadding()
        .clearAndSetSemantics { contentDescription = "Загружается главная" }, contentAlignment = Alignment.TopCenter) {
        val tablet = maxWidth >= 600.dp
        Column(Modifier.widthIn(max = 1200.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SkeletonBlock(phase, Modifier.size(32.dp), round = true)
                Spacer(Modifier.weight(1f))
                SkeletonBlock(phase, Modifier.width(96.dp).height(18.dp))
                Spacer(Modifier.weight(1f))
                SkeletonBlock(phase, Modifier.size(40.dp), round = true)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                if (tablet) SkeletonDay(phase)
                SkeletonRequests(phase)
                if (!tablet) SkeletonDay(phase)
                AppCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SkeletonBlock(phase, Modifier.width(86.dp).height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            repeat(2) { SkeletonBlock(phase, Modifier.weight(1f).height(52.dp)) }
                        }
                    }
                }
            }
            if (!tablet) SkeletonBlock(phase, Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                .fillMaxWidth().height(50.dp), round = true)
        }
    }
}

@Composable
private fun SkeletonRequests(phase: State<Float>) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SkeletonBlock(phase, Modifier.width(154.dp).height(20.dp))
            SkeletonBlock(phase, Modifier.width(104.dp).height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) { SkeletonBlock(phase, Modifier.weight(1f).height(68.dp)) }
            }
            SkeletonBlock(phase, Modifier.fillMaxWidth().height(16.dp))
            SkeletonBlock(phase, Modifier.fillMaxWidth(.7f).height(16.dp))
        }
    }
}

@Composable
private fun SkeletonDay(phase: State<Float>) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SkeletonBlock(phase, Modifier.weight(1f).height(28.dp))
                SkeletonBlock(phase, Modifier.width(150.dp).height(34.dp), round = true)
            }
            SkeletonBlock(phase, Modifier.fillMaxWidth().height(44.dp))
            SkeletonBlock(phase, Modifier.fillMaxWidth(.65f).height(18.dp))
        }
    }
}

@Composable
private fun SkeletonBlock(phase: State<Float>, modifier: Modifier, round: Boolean = false) {
    val base = MaterialTheme.colorScheme.onSurface.copy(alpha = .08f)
    val highlight = MaterialTheme.colorScheme.surface.copy(alpha = .65f)
    Box(modifier.clip(RoundedCornerShape(if (round) 50.dp else 7.dp)).drawWithCache {
        onDrawBehind {
            drawRect(base)
            // Read animation in draw, so moving the highlight does not recompose or relayout the screen.
            val x = (phase.value * 2.6f - .8f) * size.width
            drawRect(Brush.linearGradient(listOf(base.copy(alpha = 0f), highlight, base.copy(alpha = 0f)),
                start = Offset(x, 0f), end = Offset(x + size.width * .72f, size.height)))
        }
    })
}
