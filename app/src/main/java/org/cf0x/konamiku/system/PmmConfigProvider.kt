package org.cf0x.konamiku.system

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Process
import android.os.UserManager
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.cf0x.konamiku.data.AppDataStore

/**
 * Cross-process ContentProvider that exposes the persisted PMm Tool setting.
 *
 * The Xposed module (running in `com.android.nfc`) queries it to decide
 * whether to inject libpmm.so. The answer is read from [AppDataStore] on every
 * query, so it is right even when this process was just started for the
 * query and the UI never ran.
 *
 * URI: `content://org.cf0x.konamiku.pmmconfig/pmm_enabled`
 *  - one row, column "enabled" (1 or 0): the stored value, or the product
 *    default (enabled) when the user never changed it;
 *  - no cursor: the setting cannot be read right now (user not unlocked yet,
 *    storage error, query from another user). The module treats that as
 *    "not yet known" and asks again later instead of guessing.
 */
class PmmConfigProvider : ContentProvider() {

    companion object {
        private const val TAG = "KonamikU-PmmConfig"
        private const val AUTHORITY = "org.cf0x.konamiku.pmmconfig"
        private const val PATH_PMM_ENABLED = "pmm_enabled"
        private const val CODE_PMM_ENABLED = 1

        /** Bounds the binder thread the module is blocked on. */
        private const val READ_TIMEOUT_MS = 2_000L

        private val URI_MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_PMM_ENABLED, CODE_PMM_ENABLED)
        }

        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_PMM_ENABLED")
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (URI_MATCHER.match(uri) != CODE_PMM_ENABLED) return null
        val enabled = readPmmEnabled() ?: return null
        return MatrixCursor(arrayOf("enabled")).apply { addRow(arrayOf(if (enabled) 1 else 0)) }
    }

    private fun readPmmEnabled(): Boolean? {
        val ctx = context ?: return null
        // Each user has its own copy of this setting; never answer one user's
        // NFC stack with another user's switch.
        if (Binder.getCallingUserHandle() != Process.myUserHandle()) {
            Log.w(TAG, "Query from another user (${Binder.getCallingUserHandle()}); not answering")
            return null
        }
        // DataStore lives in credential-encrypted storage, unreadable before
        // the first unlock after boot.
        if (ctx.getSystemService(UserManager::class.java)?.isUserUnlocked != true) {
            Log.i(TAG, "User locked; setting unavailable")
            return null
        }
        // The caller is blocked on this binder call, so the read is bounded.
        // clearCallingIdentity: DataStore reads our own files, not the caller's.
        val token = Binder.clearCallingIdentity()
        return try {
            runCatching {
                runBlocking {
                    withTimeoutOrNull(READ_TIMEOUT_MS) { AppDataStore(ctx).pmmEnabled.first() }
                }
            }.onFailure { Log.w(TAG, "Cannot read setting: ${it.message}") }.getOrNull()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    /** The setting is written through DataStore; the provider never accepts writes. */
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    override fun getType(uri: Uri): String? =
        if (URI_MATCHER.match(uri) == CODE_PMM_ENABLED)
            "vnd.android.cursor.item/vnd.konamiku.pmm_enabled"
        else null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
