package ru.lumastack.lumawork.data.notifications

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ru.lumastack.lumawork.LumaWorkApplication

/** Android decides when a network-constrained periodic refresh can run. */
class RequestNotificationsJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var task: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val runtime = RequestNotificationsRuntime.get(this)
        task = scope.launch(start = CoroutineStart.LAZY) {
            var retry = false
            try {
                val container = (application as LumaWorkApplication).container
                val session = container.authRepository.session
                val owner = params.extras.getString(OWNER)
                val fingerprint = params.extras.getString(FINGERPRINT)
                if (session == null || session.user.id != owner) { cancelScheduled(this@RequestNotificationsJobService); return@launch }
                val previous = container.simpleOneRepository.readSnapshot(session.user.id)
                if (previous?.sessionFingerprint != fingerprint) { cancelScheduled(this@RequestNotificationsJobService); return@launch }
                runtime.repository.loadLocal(session)
                if (!runtime.repository.settings(session.user.id).value.preferences.anyEnabled) { cancelScheduled(this@RequestNotificationsJobService); return@launch }
                runtime.repository.synchronize(session)
                val snapshot = container.simpleOneRepository.refresh(session.user.id, previous)
                runtime.repository.process(session, snapshot, offline = false, deliverLocal = runtime.delivery::deliver)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { retry = true }
            finally { jobFinished(params, retry) }
        }
        task?.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean { task?.cancel(); task = null; return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val JOB_ID = 0x4E4F54
        private const val OWNER = "owner"
        private const val FINGERPRINT = "fingerprint"

        fun schedule(context: Context, owner: String, fingerprint: String) {
            if (owner.isBlank() || fingerprint.isBlank()) return
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val existing = scheduler.getPendingJob(JOB_ID)
            if (existing?.extras?.getString(OWNER) == owner && existing.extras.getString(FINGERPRINT) == fingerprint) return
            val extras = PersistableBundle().apply { putString(OWNER, owner); putString(FINGERPRINT, fingerprint) }
            scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, RequestNotificationsJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15 * 60_000L)
                .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).setExtras(extras).build())
        }
        fun cancelScheduled(context: Context) { context.getSystemService(JobScheduler::class.java).cancel(JOB_ID) }
    }
}
