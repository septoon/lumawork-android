package ru.lumastack.lumawork.ui.notifications

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.lumastack.lumawork.data.notifications.RequestNotificationsJobService
import ru.lumastack.lumawork.data.notifications.RequestNotificationsRuntime
import ru.lumastack.lumawork.domain.model.AppSession
import ru.lumastack.lumawork.domain.simpleone.SimpleOneSnapshot

@Composable
fun RequestNotificationsHost(session: AppSession, offline: Boolean, snapshot: SimpleOneSnapshot, authorized: Boolean) {
    val context = LocalContext.current
    val runtime = remember(context) { RequestNotificationsRuntime.get(context) }
    val state by runtime.repository.settings(session.user.id).collectAsStateWithLifecycle()
    val currentSnapshot by rememberUpdatedState(snapshot)

    LaunchedEffect(session.token, authorized, offline, snapshot.sessionFingerprint) {
        if (!authorized || offline || snapshot.sessionFingerprint.isBlank()) return@LaunchedEffect
        runtime.repository.observeSnapshots(session, snapshotFlow { currentSnapshot }, runtime.delivery::deliver)
    }
    LaunchedEffect(session.user.id, authorized, offline, snapshot.sessionFingerprint, state.ready, state.preferences.anyEnabled) {
        if (authorized && !offline && state.ready && state.preferences.anyEnabled && snapshot.sessionFingerprint.isNotBlank()) {
            RequestNotificationsJobService.schedule(context, session.user.id, snapshot.sessionFingerprint)
        } else RequestNotificationsJobService.cancelScheduled(context)
    }
}
