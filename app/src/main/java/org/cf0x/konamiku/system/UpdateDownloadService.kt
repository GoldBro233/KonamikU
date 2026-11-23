package org.cf0x.konamiku.system

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.cf0x.konamiku.MainActivity
import org.cf0x.konamiku.R
import org.cf0x.konamiku.system.UpdateTask.FailureKind
import org.cf0x.konamiku.system.UpdateTask.Phase
import org.cf0x.konamiku.util.localized
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Downloads the update APK and hands it to [UpdateInstaller].
 *
 * It runs as a foreground service because the download can take minutes on a
 * cold mirror and the app may be backgrounded meanwhile. Each start is one
 * task with its own id, connection and file; a task that has been cancelled
 * or superseded can no longer publish state, post notifications, fall back
 * to the mirror, or commit a session.
 */
class UpdateDownloadService : Service() {

    companion object {
        private const val TAG = "KonamikU-Download"

        private const val ACTION_START = "org.cf0x.konamiku.action.START_DOWNLOAD"
        private const val ACTION_CANCEL = "org.cf0x.konamiku.action.CANCEL_DOWNLOAD"

        private const val EXTRA_URL = "url"
        private const val EXTRA_FALLBACK_URL = "fallback_url"
        private const val EXTRA_VERSION = "version"
        private const val EXTRA_TASK_ID = "task_id"

        private const val CONNECT_TIMEOUT_MS = 15_000
        /** Per-read timeout: bounds how long a cancelled read can linger. */
        private const val READ_TIMEOUT_MS = 15_000
        /** Abort when the progress bar has not advanced for this long. */
        private const val STALL_TIMEOUT_MS = 30_000L
        /** Hard ceiling for a single transfer attempt. */
        private const val TOTAL_TIMEOUT_MS = 10 * 60_000L
        private const val BUFFER_SIZE = 64 * 1024

        /** Seeded from the clock so file names never repeat across processes. */
        private val nextTaskId = AtomicLong(System.currentTimeMillis())

        /** Started by the UI with the resolved (possibly mirrored) URL. */
        fun start(context: Context, request: UpdateTask.Request) {
            if (request.url.isBlank()) {
                Log.e(TAG, "No URL supplied")
                return
            }
            // A previous attempt's session must not be able to install behind
            // this one, nor its result overwrite this task's state.
            UpdateTask.discard(context)
            val intent = Intent(context, UpdateDownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URL, request.url)
                .putExtra(EXTRA_FALLBACK_URL, request.fallbackUrl)
                .putExtra(EXTRA_VERSION, request.version)
            context.startForegroundService(intent)
        }

        /** Cancels [taskId] unless its session has already been committed. */
        fun cancel(context: Context, taskId: Long) {
            context.startService(cancelIntent(context, taskId))
        }

        private fun cancelIntent(context: Context, taskId: Long) =
            Intent(context, UpdateDownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_TASK_ID, taskId)
    }

    /** Below API 33 a Service is outside AppCompat's per-app language. */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base.localized())
    }

    private sealed interface Transfer {
        data object Done : Transfer
        data object Cancelled : Transfer
        data class Failed(val kind: FailureKind) : Transfer
    }

    private class Task(
        val id: Long,
        val request: UpdateTask.Request,
        val file: File
    ) : UpdateInstaller.CommitGate {
        private val stage = AtomicInteger(RUNNING)

        @Volatile
        var connection: HttpURLConnection? = null
        var job: Job? = null

        override val isCancelled: Boolean get() = stage.get() == CANCELLED

        /** False once the installer owns the session. */
        fun tryCancel(): Boolean = stage.compareAndSet(RUNNING, CANCELLED)

        override fun tryClaimCommit(): Boolean = stage.compareAndSet(RUNNING, COMMITTING)

        private companion object {
            const val RUNNING = 0
            const val CANCELLED = 1
            const val COMMITTING = 2
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Guards [current] together with the progress notification and state
     * writes, so a cancel accepted under it is never followed by a publish
     * from the task it cancelled.
     */
    private val lock = Any()

    /** The task the foreground notification belongs to; written on the main thread. */
    @Volatile
    private var current: Task? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val request = UpdateTask.Request(
                    url = intent.getStringExtra(EXTRA_URL).orEmpty(),
                    fallbackUrl = intent.getStringExtra(EXTRA_FALLBACK_URL).orEmpty(),
                    version = intent.getStringExtra(EXTRA_VERSION).orEmpty()
                )
                val id = nextTaskId.incrementAndGet()
                val task = Task(id, request, File(cacheDir, "update-$id.apk"))
                synchronized(lock) {
                    // A retry while the old task still runs: silence the old
                    // one before this one takes over the notification.
                    current?.let { abort(it) }
                    current = task
                    UpdateTask.set(
                        UpdateTask.State(
                            taskId = id,
                            phase = Phase.DOWNLOADING,
                            sourceUrl = request.url,
                            request = request
                        )
                    )
                }
                // Always called after startForegroundService, before any return.
                startForegroundCompat(task)
                task.job = scope.launch { run(task) }
            }
            ACTION_CANCEL -> {
                val id = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                val task = current
                if (task != null && task.id == id && abort(task)) {
                    Log.i(TAG, "Task $id cancelled by user")
                    release(task)
                } else if (task == null) {
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Accepts a cancel for [task] unless its session is already committed.
     * The blocking read is unblocked by closing the connection; it may still
     * linger up to the read timeout, but can no longer publish anything.
     */
    private fun abort(task: Task): Boolean {
        synchronized(lock) {
            if (!task.tryCancel()) return false
            UpdateTask.resetIfOwner(task.id)
        }
        task.job?.cancel()
        task.connection?.let { conn -> scope.launch { runCatching { conn.disconnect() } } }
        return true
    }

    /** Drops the foreground notification and stops, if [task] still owns them. Main thread. */
    private fun release(task: Task) {
        synchronized(lock) {
            if (current !== task) return
            current = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    private fun run(task: Task) {
        try {
            execute(task)
        } finally {
            runCatching { task.file.delete() }
            mainHandler.post { release(task) }
        }
    }

    private fun execute(task: Task) {
        val request = task.request
        if (!UpdateInstaller.canInstall(this)) {
            fail(task, FailureKind.PERMISSION, request.url)
            UpdateInstaller.requestInstallPermission(this)
            return
        }

        var attemptedUrl = request.url
        var transfer = download(task, request.url, retrying = false)
        if (transfer is Transfer.Failed &&
            request.fallbackUrl.isNotBlank() && request.fallbackUrl != request.url
        ) {
            // Mirror unreachable (or truncated): retry once against the
            // direct link before surfacing the failure. Never after a cancel.
            Log.w(TAG, "Primary URL failed (${transfer.kind}), retrying fallback")
            attemptedUrl = request.fallbackUrl
            transfer = download(task, request.fallbackUrl, retrying = true)
        }
        when (transfer) {
            Transfer.Cancelled -> return
            is Transfer.Failed -> {
                fail(task, transfer.kind, attemptedUrl)
                return
            }
            Transfer.Done -> Unit
        }

        if (!UpdateInstaller.canInstall(this)) {
            fail(task, FailureKind.PERMISSION, attemptedUrl)
            UpdateInstaller.requestInstallPermission(this)
            return
        }
        val expectedCode = UpdateInstaller.archiveVersionCode(this, task.file)
        if (expectedCode == null) {
            fail(task, FailureKind.INSTALL, attemptedUrl)
            return
        }

        publish(task, getString(R.string.update_installing), -1) {
            it.copy(phase = Phase.INSTALLING, percent = -1)
        }

        val result = UpdateInstaller.install(this, task.file, task) { sessionId ->
            UpdateInstaller.saveRecord(
                this,
                UpdateInstaller.Record(sessionId, expectedCode, request)
            )
            UpdateTask.updateIfOwner(task.id) {
                it.copy(phase = Phase.INSTALLING, cancellable = false, sessionId = sessionId)
            }
        }
        when (result) {
            is UpdateInstaller.Result.Committed -> Unit // The receiver reports the outcome.
            UpdateInstaller.Result.Cancelled -> Unit
            is UpdateInstaller.Result.Failed -> {
                // The commit itself threw: no result will ever arrive for it.
                UpdateTask.discard(this)
                failCommitted(task, result.detail)
            }
        }
    }

    /** Publishes a terminal failure, unless [task] was cancelled or superseded. */
    private fun fail(task: Task, kind: FailureKind, url: String, detail: String? = null) {
        synchronized(lock) {
            if (task.isCancelled || current !== task) return
            UpdateTask.updateIfOwner(task.id) {
                it.copy(
                    phase = Phase.FAILED,
                    kind = kind,
                    detail = detail,
                    sourceUrl = url,
                    cancellable = true
                )
            }
        }
        Log.e(TAG, "Task ${task.id} failed: $kind")
        UpdateTask.notifyFailure(this, kind, detail)
    }

    /** Like [fail], for a task that already won the commit and so cannot be cancelled. */
    private fun failCommitted(task: Task, detail: String?) {
        synchronized(lock) {
            if (current !== task) return
            UpdateTask.updateIfOwner(task.id) {
                it.copy(phase = Phase.FAILED, kind = FailureKind.INSTALL, detail = detail)
            }
        }
        UpdateTask.notifyFailure(this, FailureKind.INSTALL, detail)
    }

    /** Posts progress for [task] and applies [transform], unless it no longer owns them. */
    private fun publish(
        task: Task,
        text: String,
        percent: Int,
        transform: (UpdateTask.State) -> UpdateTask.State
    ) {
        synchronized(lock) {
            if (task.isCancelled || current !== task) return
            runCatching {
                NotificationManagerCompat.from(this)
                    .notify(UpdateTask.NOTIF_ID_PROGRESS, buildNotification(task, text, percent))
            }
            UpdateTask.updateIfOwner(task.id, transform)
        }
    }

    private fun progressText(percent: Int, retrying: Boolean): String = when {
        retrying && percent >= 0 -> getString(R.string.update_downloading_retry, percent)
        retrying -> getString(R.string.update_downloading_retry_unknown)
        percent >= 0 -> getString(R.string.update_downloading, percent)
        else -> getString(R.string.update_downloading_unknown)
    }

    private fun download(task: Task, url: String, retrying: Boolean): Transfer {
        if (task.isCancelled) return Transfer.Cancelled
        publish(task, progressText(-1, retrying), -1) {
            it.copy(phase = Phase.DOWNLOADING, percent = -1, retryingDirect = retrying, sourceUrl = url)
        }

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/octet-stream")
            }
            task.connection = conn
            // A cancel that landed before the connection was visible cannot
            // have closed it.
            if (task.isCancelled) return Transfer.Cancelled
            val code = conn.responseCode
            if (task.isCancelled) return Transfer.Cancelled
            if (code != 200) {
                Log.w(TAG, "HTTP $code for $url")
                return Transfer.Failed(
                    if (code == 404 || code == 410) FailureKind.NOT_FOUND else FailureKind.HTTP
                )
            }

            val total = conn.contentLengthLong.takeIf { it > 0L }
            var written = 0L
            var lastPercent = -1
            val startedAt = System.currentTimeMillis()
            // Watch the *displayed* progress, not raw byte arrival: a mirror
            // that trickles a few bytes at a time keeps the socket alive but
            // leaves the bar frozen, which is exactly what the user sees.
            var lastProgressAt = startedAt

            conn.inputStream.use { input ->
                task.file.outputStream().use { out ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (task.isCancelled) return Transfer.Cancelled
                        val now = System.currentTimeMillis()
                        if (now - lastProgressAt > STALL_TIMEOUT_MS) {
                            Log.e(TAG, "Progress stalled at $lastPercent% ($written bytes)")
                            return Transfer.Failed(FailureKind.TIMEOUT)
                        }
                        if (now - startedAt > TOTAL_TIMEOUT_MS) {
                            Log.e(TAG, "Overall timeout after ${TOTAL_TIMEOUT_MS}ms")
                            return Transfer.Failed(FailureKind.TIMEOUT)
                        }

                        val n = input.read(buffer)
                        if (task.isCancelled) return Transfer.Cancelled
                        if (n <= 0) break
                        val arrivedAt = System.currentTimeMillis()
                        out.write(buffer, 0, n)
                        written += n

                        // Without a Content-Length there is no percentage to
                        // watch, so byte arrival feeds the stall watchdog.
                        if (total == null) {
                            lastProgressAt = arrivedAt
                            continue
                        }
                        val percent = ((written * 100) / total).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            lastProgressAt = arrivedAt
                            publish(task, progressText(percent, retrying), percent) {
                                it.copy(phase = Phase.DOWNLOADING, percent = percent)
                            }
                        }
                    }
                }
            }

            if (total != null && written != total) {
                Log.e(TAG, "Truncated: $written of $total bytes")
                return Transfer.Failed(FailureKind.TIMEOUT)
            }
            Log.i(TAG, "Downloaded $written bytes to ${task.file.name}")
            Transfer.Done
        } catch (e: Exception) {
            // Closing the connection to cancel surfaces here as an IOException.
            if (task.isCancelled) {
                Transfer.Cancelled
            } else {
                Log.e(TAG, "Download error: ${e.javaClass.simpleName}: ${e.message}")
                Transfer.Failed(FailureKind.NETWORK)
            }
        } finally {
            task.connection = null
            conn?.disconnect()
        }
    }

    private fun startForegroundCompat(task: Task) {
        UpdateTask.ensureChannels(this)
        ServiceCompat.startForeground(
            this,
            UpdateTask.NOTIF_ID_PROGRESS,
            buildNotification(task, progressText(-1, false), -1),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun buildNotification(task: Task, text: String, percent: Int) =
        NotificationCompat.Builder(this, UpdateTask.CHANNEL_DOWNLOAD)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(getString(R.string.update_title))
            .setContentText(text)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .addAction(
                0,
                getString(R.string.update_cancel),
                // One PendingIntent per task: a cancel tapped on a stale
                // notification names a task that no longer exists.
                PendingIntent.getService(
                    this,
                    task.id.toInt(),
                    cancelIntent(this, task.id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    override fun onDestroy() {
        // Only a task that can still be cancelled loses its state here; a
        // committed one is reported by the receiver.
        current?.let { abort(it) }
        current = null
        scope.cancel()
        super.onDestroy()
    }
}
