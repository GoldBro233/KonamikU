package org.cf0x.konamiku.system

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import java.io.File

/**
 * Hands a downloaded APK to [PackageInstaller] for an in-app install, and
 * tracks the session until the system reports what happened to it.
 *
 * We use a [PackageInstaller.Session] (streaming write + commit) rather than
 * `ACTION_VIEW` on a file:// URI: the latter dumps the user into a browser or
 * a file manager and loses the changelog context entirely.
 *
 * A commit only hands the session to the system; it is not an install. The
 * verdict arrives later through [UpdateInstallReceiver], possibly in another
 * process, so the session is recorded on disk and [reconcile] re-derives the
 * outcome when the app comes back without it.
 */
object UpdateInstaller {

    const val TAG = "KonamikU-Installer"

    /** Broadcast action that [UpdateInstallReceiver] listens for. */
    const val ACTION_INSTALL_RESULT = "org.cf0x.konamiku.ACTION_INSTALL_RESULT"

    private const val PREFS = "update_install"
    private const val KEY_SESSION = "session_id"
    private const val KEY_EXPECTED_CODE = "expected_version_code"
    private const val KEY_URL = "url"
    private const val KEY_FALLBACK = "fallback_url"
    private const val KEY_VERSION = "version"
    private const val KEY_STATUS = "status"
    private const val KEY_MESSAGE = "message"
    private const val NO_STATUS = Int.MIN_VALUE

    /** Arbitrates between a user cancelling the download and the commit. */
    interface CommitGate {
        val isCancelled: Boolean

        /** Atomically takes the right to commit; false when a cancel got there first. */
        fun tryClaimCommit(): Boolean
    }

    sealed interface Result {
        data class Committed(val sessionId: Int) : Result
        data object Cancelled : Result
        data class Failed(val detail: String?) : Result
    }

    /** A committed session whose outcome has not been shown to the user yet. */
    data class Record(
        val sessionId: Int,
        val expectedVersionCode: Long,
        val request: UpdateTask.Request,
        /** Last status the receiver saw, or null before any arrived. */
        val status: Int? = null,
        val message: String? = null
    )

    /**
     * Whether the user has granted this app permission to install packages.
     */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the user can allow installs from this app. */
    fun requestInstallPermission(context: Context) {
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "Cannot open unknown-sources settings: ${it.message}") }
    }

    /** versionCode of [apk], or null when it is not a parseable package of ours. */
    fun archiveVersionCode(context: Context, apk: File): Long? {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return null
        if (info.packageName != context.packageName) {
            Log.e(TAG, "APK is ${info.packageName}, not ${context.packageName}")
            return null
        }
        return info.longVersionCode
    }

    /**
     * Writes [apk] into a new session and commits it, unless [gate] is
     * cancelled first. [onClaimed] runs after the commit right is taken and
     * before the commit, so the session is on record before any result can
     * arrive for it.
     */
    fun install(
        context: Context,
        apk: File,
        gate: CommitGate,
        onClaimed: (sessionId: Int) -> Unit
    ): Result {
        if (!apk.isFile || apk.length() <= 0L) {
            Log.e(TAG, "APK missing or empty: $apk")
            return Result.Failed(null)
        }

        var session: PackageInstaller.Session? = null
        var committed = false
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
            }

            val sessionId = installer.createSession(params)
            session = installer.openSession(sessionId)

            session.openWrite("base.apk", 0L, apk.length()).use { out ->
                apk.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (gate.isCancelled) return Result.Cancelled
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                    }
                }
                session.fsync(out)
            }

            if (!gate.tryClaimCommit()) return Result.Cancelled
            onClaimed(sessionId)

            val resultIntent = Intent(context, UpdateInstallReceiver::class.java)
                .setAction(ACTION_INSTALL_RESULT)
                .setPackage(context.packageName)
            // The system reports the status by filling extras into this
            // intent, which an immutable PendingIntent refuses (and API 35+
            // rejects the commit outright). The intent is explicit, which is
            // what makes FLAG_MUTABLE acceptable on API 34+.
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE
            } else {
                0
            }
            val pending = PendingIntent.getBroadcast(
                context,
                sessionId,
                resultIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or mutable
            )

            session.commit(pending.intentSender)
            committed = true
            Log.i(TAG, "Session $sessionId committed for $apk")
            Result.Committed(sessionId)
        } catch (e: Exception) {
            Log.e(TAG, "Install failed: ${e.javaClass.simpleName}: ${e.message}")
            Result.Failed(e.message)
        } finally {
            if (!committed) runCatching { session?.abandon() }
            runCatching { session?.close() }
        }
    }

    // ── Session record ───────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveRecord(context: Context, record: Record) {
        prefs(context).edit(commit = true) {
            putInt(KEY_SESSION, record.sessionId)
            putLong(KEY_EXPECTED_CODE, record.expectedVersionCode)
            putString(KEY_URL, record.request.url)
            putString(KEY_FALLBACK, record.request.fallbackUrl)
            putString(KEY_VERSION, record.request.version)
            putInt(KEY_STATUS, record.status ?: NO_STATUS)
            putString(KEY_MESSAGE, record.message)
        }
    }

    fun loadRecord(context: Context): Record? {
        val p = prefs(context)
        if (!p.contains(KEY_SESSION)) return null
        return Record(
            sessionId = p.getInt(KEY_SESSION, -1),
            expectedVersionCode = p.getLong(KEY_EXPECTED_CODE, Long.MAX_VALUE),
            request = UpdateTask.Request(
                url = p.getString(KEY_URL, null).orEmpty(),
                fallbackUrl = p.getString(KEY_FALLBACK, null).orEmpty(),
                version = p.getString(KEY_VERSION, null).orEmpty()
            ),
            status = p.getInt(KEY_STATUS, NO_STATUS).takeIf { it != NO_STATUS },
            message = p.getString(KEY_MESSAGE, null)
        )
    }

    private fun clearRecord(context: Context) {
        prefs(context).edit(commit = true) { clear() }
    }

    /**
     * Forgets the recorded session and abandons it if the system still holds
     * it, so an old confirmation can no longer install behind a newer attempt.
     */
    fun discardRecord(context: Context) {
        val record = loadRecord(context) ?: return
        val installer = context.packageManager.packageInstaller
        if (installer.getSessionInfo(record.sessionId) != null) {
            runCatching { installer.abandonSession(record.sessionId) }
                .onFailure { Log.w(TAG, "Cannot abandon ${record.sessionId}: ${it.message}") }
        }
        clearRecord(context)
    }

    // ── Results ──────────────────────────────────────────────────────────

    /** Called by [UpdateInstallReceiver] for every status the system reports. */
    fun handleResult(
        context: Context,
        sessionId: Int,
        status: Int,
        message: String?,
        confirm: Intent?
    ) {
        val record = loadRecord(context)
        if (record == null || record.sessionId != sessionId) {
            // An abandoned or superseded session: the user already moved on.
            Log.i(TAG, "Ignoring status $status for untracked session $sessionId")
            return
        }
        saveRecord(context, record.copy(status = status, message = message))
        val request = record.request

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm == null) {
                    fail(context, request, UpdateTask.FailureKind.INSTALL, message)
                    return
                }
                Log.i(TAG, "Session $sessionId needs user confirmation")
                UpdateTask.set(
                    UpdateTask.State(
                        taskId = sessionId.toLong(),
                        phase = UpdateTask.Phase.AWAITING_USER,
                        cancellable = false,
                        request = request,
                        sessionId = sessionId,
                        confirmIntent = confirm
                    )
                )
                // A background app cannot start activities, and a visible one
                // may still be refused by the OEM; the dialog and the
                // notification are what the user can always act on.
                val launched = UpdateTask.uiVisible && runCatching {
                    context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure {
                    Log.w(TAG, "Cannot launch confirmation: ${it.message}")
                }.isSuccess
                if (!launched) UpdateTask.notifyConfirm(context, sessionId, confirm, request.version)
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Session $sessionId installed")
                clearRecord(context)
                UpdateTask.cancelOutcomeNotification(context)
                UpdateTask.set(UpdateTask.State())
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.i(TAG, "Session $sessionId aborted by the user")
                fail(context, request, UpdateTask.FailureKind.ABORTED, null)
            }

            else -> {
                Log.e(TAG, "Session $sessionId failed: status=$status msg=$message")
                fail(context, request, UpdateTask.FailureKind.INSTALL, message)
            }
        }
    }

    private fun fail(
        context: Context,
        request: UpdateTask.Request,
        kind: UpdateTask.FailureKind,
        detail: String?
    ) {
        UpdateTask.set(
            UpdateTask.State(
                phase = UpdateTask.Phase.FAILED,
                kind = kind,
                detail = detail,
                sourceUrl = request.fallbackUrl.ifBlank { request.url },
                request = request
            )
        )
        UpdateTask.notifyFailure(context, kind, detail)
    }

    /**
     * Re-derives the outcome of a recorded session when this process has no
     * live state for it: the result arrived in a process that died, or never
     * arrived. Does blocking binder calls; call off the main thread.
     */
    fun reconcile(context: Context) {
        val record = loadRecord(context) ?: return
        if (UpdateTask.state.value.phase != UpdateTask.Phase.IDLE) return

        val installer = context.packageManager.packageInstaller
        val sessionAlive = installer.getSessionInfo(record.sessionId) != null
        val installedCode = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)

        when {
            installedCode >= record.expectedVersionCode -> {
                Log.i(TAG, "Session ${record.sessionId} is installed ($installedCode)")
                clearRecord(context)
                UpdateTask.cancelOutcomeNotification(context)
            }
            sessionAlive && record.status == null -> {
                // Committed and still with the system; its result is pending.
                if (UpdateTask.hiddenSessionId == record.sessionId) return
                UpdateTask.set(
                    UpdateTask.State(
                        taskId = record.sessionId.toLong(),
                        phase = UpdateTask.Phase.INSTALLING,
                        cancellable = false,
                        request = record.request,
                        sessionId = record.sessionId
                    )
                )
            }
            sessionAlive -> {
                // Waiting for a confirmation whose intent died with the
                // process that received it.
                fail(context, record.request, UpdateTask.FailureKind.INTERRUPTED, null)
            }
            record.status == PackageInstaller.STATUS_FAILURE_ABORTED ->
                fail(context, record.request, UpdateTask.FailureKind.ABORTED, null)
            record.status == null || record.status == PackageInstaller.STATUS_PENDING_USER_ACTION ->
                fail(context, record.request, UpdateTask.FailureKind.INTERRUPTED, null)
            else ->
                fail(context, record.request, UpdateTask.FailureKind.INSTALL, record.message)
        }
    }
}
