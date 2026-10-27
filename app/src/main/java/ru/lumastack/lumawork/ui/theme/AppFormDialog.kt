package ru.lumastack.lumawork.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import ru.lumastack.lumawork.ui.adaptive.appFormHasPinnedActions

/** Keep actions fixed when there is room; short windows scroll the entire form. */
@Composable
fun AppFormDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    isBusy: Boolean = false,
    confirmEnabled: Boolean = true,
    dismissLabel: String = "Закрыть",
    maxWidth: Dp = 720.dp,
    errorMessage: String? = null,
    errorTitle: String? = null,
    onBack: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    AppModalSheet(onDismiss = onDismiss, maxWidth = maxWidth, dismissEnabled = !isBusy) {
        val listState = rememberLazyListState()
        var shownError by rememberSaveable { mutableStateOf<String?>(null) }
        LaunchedEffect(errorMessage) {
            if (shownError != errorMessage && !errorMessage.isNullOrBlank()) listState.scrollToItem(0)
            shownError = errorMessage
        }
        val body: LazyListScope.() -> Unit = {
            if (!errorMessage.isNullOrBlank()) {
                item(key = "app-form:error") { FormError(errorMessage, errorTitle) }
            }
            content()
        }
        BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight(.94f).padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            val pinned = appFormHasPinnedActions(maxHeight.value.roundToInt(), LocalDensity.current.fontScale)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (pinned) FormHeader(title, dismissLabel, isBusy, onDismiss, onBack)
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(), state = listState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (!pinned) {
                        item(key = "app-form:header") { FormHeader(title, dismissLabel, isBusy, onDismiss, onBack) }
                    }
                    body()
                    if (!pinned) {
                        item(key = "app-form:actions") { FormConfirm(confirmLabel, isBusy, confirmEnabled, onConfirm) }
                    }
                }
                if (pinned) FormConfirm(confirmLabel, isBusy, confirmEnabled, onConfirm)
            }
        }
    }
}

@Composable
private fun FormHeader(title: String, dismissLabel: String, isBusy: Boolean, onDismiss: () -> Unit, onBack: (() -> Unit)?) {
    AppModalHeader(title, onDismiss, closeEnabled = !isBusy, closeLabel = dismissLabel, onBack = onBack)
}

@Composable
private fun FormConfirm(label: String, isBusy: Boolean, enabled: Boolean, onConfirm: () -> Unit) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    AppButton(
        onClick = {
            focusManager.clearFocus()
            keyboard?.hide()
            onConfirm()
        },
        enabled = enabled && !isBusy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isBusy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = androidx.compose.material3.LocalContentColor.current)
            Spacer(Modifier.width(8.dp))
        }
        Text(label)
    }
}

@Composable
private fun FormError(message: String, title: String?) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            title?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer) }
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}
