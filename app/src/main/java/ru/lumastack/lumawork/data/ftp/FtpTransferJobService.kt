package ru.lumastack.lumawork.data.ftp

import android.app.DownloadManager
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*
import ru.lumastack.lumawork.LumaWorkApplication

/** A system job finishes copies into SAF folders after DownloadManager completes, even without an open screen. */
class FtpTransferJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = java.util.concurrent.ConcurrentHashMap<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        synchronized(lock) { active = true; requested = false }
        lateinit var task: Job
        task = scope.launch(start = CoroutineStart.LAZY) {
            var retry = false
            try {
                val container = (application as LumaWorkApplication).container
                retry = !container.ftpTransfers.finishPending()
                container.authRepository.session?.user?.id?.let { owner ->
                    val uri = android.provider.DocumentsContract.buildChildDocumentsUri("$packageName.ftp.documents", "root:${ftpOwnerKey(owner)}")
                    contentResolver.notifyChange(uri, null)
                }
            }
            catch (_: CancellationException) { retry = true }
            catch (_: Exception) { retry = true }
            finally { synchronized(lock) {
                if (running.remove(params.jobId, task)) {
                    jobFinished(params, retry || requested)
                    active = false
                }
            } }
        }
        running[params.jobId] = task
        task.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        synchronized(lock) { running.remove(params.jobId)?.let { it.cancel(); active = false } }
        return true
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        fun schedule(context: Context): Boolean = synchronized(lock) {
            if (active) { requested = true; return@synchronized true }
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, FtpTransferJobService::class.java))
                .setOverrideDeadline(0).setBackoffCriteria(10_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
            scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        }
        private val lock = Any()
        private var active = false
        private var requested = false
        private const val JOB_ID = 0x465450
    }
}
class FtpDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DownloadManager.ACTION_DOWNLOAD_COMPLETE -> FtpTransferJobService.schedule(context)
            DownloadManager.ACTION_NOTIFICATION_CLICKED -> try {
                context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: android.content.ActivityNotFoundException) {
                context.startActivity(Intent(context, ru.lumastack.lumawork.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
