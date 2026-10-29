package ru.lumastack.lumawork.ui.theme

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import ru.lumastack.lumawork.ui.shell.AppBackButton

/** Phone sheets span the window in either orientation; tablets keep a readable width. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppModalSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 760.dp,
    dismissEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val canDismiss by rememberUpdatedState(dismissEnabled)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { target ->
            if (target == SheetValue.Hidden) {
                // The owner may show a discard confirmation and keep this sheet open.
                if (canDismiss) currentOnDismiss()
                false
            } else true
        })
    val configuration = LocalConfiguration.current
    val phone = minOf(configuration.screenWidthDp, configuration.screenHeightDp) < 600
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ModalBottomSheet(
        onDismissRequest = {},
        modifier = modifier,
        sheetState = state,
        sheetMaxWidth = if (phone) Dp.Unspecified else maxWidth,
        sheetGesturesEnabled = dismissEnabled,
        shape = shape,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        scrimColor = AppSurfaces.scrim,
        dragHandle = null,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false, shouldDismissOnClickOutside = dismissEnabled),
    ) {
        // Keep Back under owner control: a discarded attempt must not leave a predictive
        // Back transform behind. Nested screen handlers can override this handler.
        BackHandler { if (canDismiss) currentOnDismiss() }
        Column(Modifier.fillMaxWidth().background(appScreenBackground(), shape)) {
            Box(Modifier.align(Alignment.CenterHorizontally).clickable(enabled = canDismiss, role = Role.Button,
                onClickLabel = "Закрыть", onClick = { currentOnDismiss() })) {
                BottomSheetDefaults.DragHandle()
            }
            Column(Modifier.fillMaxWidth().nestedScroll(SheetContentScrollConnection)) {
                content()
            }
        }
    }
}

internal object SheetContentScrollConnection : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput) Offset(0f, available.y) else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = Velocity(0f, available.y)
}

@Composable
fun AppModalHeader(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    closeEnabled: Boolean = true,
    closeLabel: String = "Закрыть",
    onBack: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null) AppBackButton(onBack, enabled = closeEnabled)
        else ModalCloseButton(onClose, closeEnabled, closeLabel)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (onBack != null) ModalCloseButton(onClose, closeEnabled, closeLabel)
        else Spacer(Modifier.size(48.dp))
    }
}

@Composable
private fun ModalCloseButton(onClose: () -> Unit, enabled: Boolean, label: String) {
    IconButton(onClick = onClose, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Surface(Modifier.size(40.dp), shape = CircleShape, color = AppSurfaces.card, border = BorderStroke(1.dp, AppSurfaces.border)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Close, label, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
            }
        }
    }
}
