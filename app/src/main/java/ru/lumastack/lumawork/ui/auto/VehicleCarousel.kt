package ru.lumastack.lumawork.ui.auto

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import ru.lumastack.lumawork.R
import ru.lumastack.lumawork.data.auto.VehicleImageRepository
import ru.lumastack.lumawork.domain.auto.Vehicle
import ru.lumastack.lumawork.ui.theme.AppCard

@Composable
internal fun VehicleSelector(
    state: AutoUiState, imageRepository: VehicleImageRepository,
    onSelect: (String) -> Unit, onAdd: () -> Unit, onEdit: (Vehicle) -> Unit, tablet: Boolean = false,
) {
    val vehicles = state.snapshot.vehicles
    val ids = vehicles.map(Vehicle::id)
    val pager = rememberPagerState(initialPage = ids.indexOf(state.snapshot.selectedVehicleId).coerceAtLeast(0)) { vehicles.size + 1 }
    val selected by rememberUpdatedState(state.snapshot.selectedVehicleId)
    val select by rememberUpdatedState(onSelect)
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(ids) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().drop(1).collect { page ->
            vehicles.getOrNull(page)?.let { if (it.id != selected) select(it.id) }
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    LaunchedEffect(ids, state.snapshot.selectedVehicleId) {
        val desired = ids.indexOf(state.snapshot.selectedVehicleId)
        if (desired >= 0 && desired != pager.settledPage && !pager.isScrollInProgress) pager.scrollToPage(desired)
    }
    val displayed = vehicles.getOrNull(pager.currentPage)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.fillMaxWidth().heightIn(min = 82.dp).graphicsLayer { alpha = if (pager.isScrollInProgress) .2f else 1f },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (displayed == null) "Добавьте автомобиль" else displayed.displayName + if (displayed.isPrimary) "  ★" else "",
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            displayed?.licensePlate?.takeIf(String::isNotBlank)?.let { PlateBadge(it) }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            HorizontalPager(state = pager, contentPadding = PaddingValues(horizontal = maxWidth * .07f), pageSpacing = maxWidth * .12f,
                beyondViewportPageCount = 1, modifier = Modifier.fillMaxWidth().height(if (tablet) 170.dp else 190.dp), key = { ids.getOrNull(it) ?: "add-car" }) { page ->
                val vehicle = vehicles.getOrNull(page)
                val imageModifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = 1.46f
                    scaleY = 1.46f
                }
                if (vehicle == null) Image(painterResource(R.drawable.car_placeholder), "Добавить автомобиль",
                    imageModifier.clickable(onClick = onAdd), contentScale = ContentScale.Fit)
                else VehicleImage(vehicle, imageRepository, imageModifier)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.semantics { contentDescription = "Автомобиль ${pager.currentPage + 1} из ${vehicles.size + 1}" }) {
            vehicles.forEachIndexed { index, _ -> Box(Modifier.size(if (index == pager.currentPage) 8.dp else 6.dp)
                .background(if (index == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .25f), CircleShape)) }
            Text("+", color = if (displayed == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        }
        AppCard(onClick = { displayed?.let(onEdit) ?: onAdd() }) {
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (displayed == null) "Добавить по госномеру" else "Данные автомобиля", fontWeight = FontWeight.Bold)
                    Text(if (displayed == null) "Пошаговое заполнение" else "Изменить параметры и основное авто", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("›", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
