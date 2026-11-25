package org.cf0x.konamiku.xposed

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

class KonamikuModule : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        Log.i("KonamikU", "Loaded in ${param.processName}")
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName == "com.android.nfc") {
            Log.i("KonamikU", "NFC process found: pid=${android.os.Process.myPid()}")
            hookNfcValidation(param)
            hookNfcAppForPmmtool(param)
        }
    }

    /** Current hook state; published to the app via the hooked broadcast. Guarded by [pmmLock]. */
    @Volatile
    var pmmState: PmmState = PmmState.NOT_ATTEMPTED
        private set

    /**
     * Whether libpmm.so is loaded in this process. Kept apart from [pmmState]:
     * a loaded library can still be waiting for the NCI stack, and a failed
     * load must be retried rather than recorded as an attempt.
     */
    private var libraryLoaded = false

    /** Set once the user's setting has been read and said "off". */
    private var disabledByUser = false

    /** One state transition in flight per NFC process. */
    private val pmmLock = Any()

    /** Order of the reports this process sends; the app drops older ones. */
    private var reportSeq = 0L

    /** Identifies this NFC process instance across restarts that reuse a pid. */
    private val processInstance: String by lazy {
        "${android.os.Process.myPid()}:${android.os.Process.getStartUptimeMillis()}"
    }

    /**
     * Reads the PMm Tool setting from the app's [org.cf0x.konamiku.system.PmmConfigProvider].
     *
     * @return the setting, or null when it cannot be read right now (app
     *         storage locked before the first unlock, provider missing,
     *         query failed). Null is not an answer: the caller asks again on
     *         the next activation instead of turning the hook on or off.
     */
    private fun readPmmEnabled(): Boolean? = runCatching {
        val ctx = nfcContext() ?: return@runCatching null
        ctx.contentResolver.query(
            Uri.parse("content://org.cf0x.konamiku.pmmconfig/pmm_enabled"),
            null, null, null, null
        )?.use { if (it.moveToFirst()) it.getInt(0) == 1 else null }
    }.onFailure {
        Log.w("KonamikU", "PMm setting unavailable: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    /** @return the Context of the current process (com.android.nfc) */
    private fun nfcContext(): Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication").invoke(null) as? Context
    }.getOrNull()

    private fun hookNfcValidation(param: PackageLoadedParam) {
        runCatching {
            // Loaded from the boot class path; resolve through the same loader
            // the framework uses so we hook the instance the server calls.
            val cls = Class.forName(
                "android.nfc.cardemulation.NfcFCardEmulation",
                false,
                param.defaultClassLoader
            )
            Log.i("KonamikU", "validation hook: class loader=${cls.classLoader}")

            hook(cls.getDeclaredMethod("isValidSystemCode", String::class.java)).intercept { chain ->
                val arg = chain.args.firstOrNull() as? String
                Log.i("KonamikU", "isValidSystemCode($arg) -> true (hooked)")
                // Binder thread, called while an app registers a system
                // code: the provider query is bounded by the app's own read.
                ensurePmm(param, nfcContext(), onlyOnChange = true)
                true
            }

            hook(cls.getDeclaredMethod("isValidNfcid2", String::class.java)).intercept { chain ->
                Log.i("KonamikU", "isValidNfcid2(${chain.args.firstOrNull()}) -> true (hooked)")
                true
            }
            Log.i("KonamikU", "Validation hooks installed")
        }.onFailure { e ->
            Log.e("KonamikU", "Validation hook fail: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun hookNfcAppForPmmtool(param: PackageLoadedParam) {
        val candidates = listOf(
            "com.android.nfc.NfcApplication",
            "com.miui.nfc.MiuiNfcApplication",
            "com.android.nfc.NfcService"
        )
        var hooked = false

        for (className in candidates) {
            val result = runCatching {
                val cls = param.defaultClassLoader.loadClass(className)
                hook(cls.getDeclaredMethod("onCreate")).intercept { chain ->
                    val res = chain.proceed()
                    ensurePmmAsync(param, chain.thisObject as? Context)
                    res
                }
                true
            }
            if (result.getOrDefault(false)) {
                hooked = true
                break
            }
        }

        if (!hooked) {
            runCatching {
                hook(android.app.Application::class.java.getDeclaredMethod("onCreate")).intercept { chain ->
                    val res = chain.proceed()
                    ensurePmmAsync(param, chain.thisObject as? Context)
                    res
                }
            }
        }
    }

    /**
     * Application.onCreate runs on the NFC main thread, and reading the
     * setting is a binder call that may first have to start the app process.
     * The first attempt therefore runs off that thread.
     */
    private fun ensurePmmAsync(param: PackageLoadedParam, ctx: Context?) {
        Thread({ ensurePmm(param, ctx, onlyOnChange = false) }, "KonamikU-pmm").start()
    }

    /**
     * Hook state. Codes 0-4 are returned by libpmm.so and mirror the
     * PMM_STATE_* constants in bridge.c; changing either side requires
     * changing both. Codes 5 and up are decided here, before or instead of
     * asking the library.
     *
     * These are deliberately not collapsed into a boolean: WAITING_NCI clears
     * itself on the next tag contact, NO_SYMBOL never will, a user who turned
     * the tool off must not be told it failed, and the UI has to say which
     * one the user is looking at.
     */
    enum class PmmState(val code: Int) {
        /** No attempt reported yet. */
        NOT_ATTEMPTED(0),
        /** Hook is live. */
        INSTALLED(1),
        /** Loader has no NCI stack mapped yet; retries resolve this. */
        WAITING_NCI(2),
        /** An NCI stack is mapped and nothing loaded exports the symbol. */
        NO_SYMBOL(3),
        /** Symbol resolved but DobbyHook refused. */
        HOOK_FAILED(4),
        /** The PMm Tool setting was off when this NFC process read it. */
        DISABLED(5),
        /** libpmm.so could not be loaded or does not answer. */
        LOAD_FAILED(6),
        /** The setting could not be read yet; asked again on the next attempt. */
        SETTING_UNAVAILABLE(7);

        companion object {
            fun fromCode(code: Int): PmmState =
                entries.firstOrNull { it.code == code } ?: NOT_ATTEMPTED
        }
    }

    /**
     * Makes sure libpmm.so is loaded and its hook is installed, retrying the
     * hook on later calls when the first attempt found the NCI stack missing
     * or the setting could not be read yet, then reports the state.
     *
     * Serialised on [pmmLock]: callers come from the NFC main thread's helper
     * and from binder threads, and only one transition may be in flight. Once
     * INSTALLED, nothing here can move the state back.
     */
    private fun ensurePmm(param: PackageLoadedParam, ctx: Context?, onlyOnChange: Boolean) {
        synchronized(pmmLock) {
            val before = pmmState
            if (before != PmmState.INSTALLED && !disabledByUser) {
                pmmState = advancePmm(param)
            }
            if (!onlyOnChange || pmmState != before) {
                sendHookedBroadcast(ctx, pmmState, ++reportSeq)
            }
        }
    }

    private fun advancePmm(param: PackageLoadedParam): PmmState {
        if (!libraryLoaded) {
            when (readPmmEnabled()) {
                null -> {
                    Log.i("KonamikU", "ensurePmm: setting unavailable; will ask again")
                    return PmmState.SETTING_UNAVAILABLE
                }
                false -> {
                    Log.i("KonamikU", "pmmtool disabled by user config")
                    disabledByUser = true
                    return PmmState.DISABLED
                }
                true -> Unit
            }
            // Library loading and hook install are separate outcomes:
            // loading may succeed while the symbol is not yet resolvable.
            libraryLoaded = injectPmmtool(param)
            if (!libraryLoaded) return PmmState.LOAD_FAILED
        }
        return tryInstallPmmHook().also { state ->
            if (state == PmmState.INSTALLED) Log.i("KonamikU", "pmmtool hook live")
        }
    }

    private fun sendHookedBroadcast(ctx: Context?, state: PmmState, seq: Long) {
        ctx?.sendBroadcast(
            android.content.Intent("org.cf0x.konamiku.ACTION_NFC_HOOKED")
                .setPackage("org.cf0x.konamiku")
                .putExtra("pmmtool_active", state == PmmState.INSTALLED)
                .putExtra("pmmtool_state", state.code)
                .putExtra("nfc_instance", processInstance)
                .putExtra("report_seq", seq)
        )
    }

    /**
     * Loads libpmm.so and asks it whether the hook actually took.
     *
     * `nativeLoad` only reports that the library was dlopen'd — JNI_OnLoad
     * still succeeds when the target symbol is missing. Reporting `true` on
     * dlopen alone made a dead hook look active in the UI, so we trust only
     * [nativePmmHooked].
     *
     * The NCI stack is loaded lazily: on Android 14+ it lives in
     * libnfc_nci_jni.so, which the NFC process may not have mapped yet at the
     * moment we inject. A first miss is therefore not fatal - the next
     * [tryInstallPmmHook] re-resolves once the library is present.
     */
    private fun injectPmmtool(param: PackageLoadedParam): Boolean {
        // libpmm.so must be bound to *this* module's class loader: natives for
        // KonamikuModule.nativePmmHooked are looked up through the loader that
        // defined the class, so loading it against param.defaultClassLoader
        // leaves every external fun unresolved.
        //
        // The APK is built with extractNativeLibs=false, so nativeLibraryDir is
        // empty and "$nativeLibraryDir/libpmm.so" does not exist. The library
        // lives uncompressed inside the APK and has to be addressed with the
        // linker's "base.apk!/lib/<abi>/" syntax - which System.loadLibrary
        // gets from the module class loader, and System.load accepts directly.
        // Both throw on failure, unlike Runtime.nativeLoad, which returns the
        // dlopen error as a String and so looked like success.
        val loadError = runCatching { System.loadLibrary("pmm") }.exceptionOrNull()
        if (loadError == null) {
            Log.i("KonamikU", "pmmtool library loaded via System.loadLibrary")
        } else {
            val apk = runCatching { getModuleApplicationInfo()?.sourceDir }.getOrNull()
            if (apk.isNullOrBlank()) {
                Log.e(
                    "KonamikU",
                    "injectPmmtool: System.loadLibrary failed (${loadError.message}) " +
                        "and the module APK path is unknown"
                )
                return false
            }
            val abi = android.os.Build.SUPPORTED_64_BIT_ABIS.firstOrNull() ?: "arm64-v8a"
            val fullPath = "$apk!/lib/$abi/libpmm.so"
            Log.w(
                "KonamikU",
                "injectPmmtool: System.loadLibrary failed (${loadError.message}); loading $fullPath"
            )
            val directError = runCatching { System.load(fullPath) }.exceptionOrNull()
            if (directError != null) {
                Log.e(
                    "KonamikU",
                    "injectPmmtool: could not load libpmm.so: " +
                        "${directError.javaClass.simpleName}: ${directError.message}"
                )
                return false
            }
            Log.i("KonamikU", "pmmtool library loaded via System.load: $fullPath")
        }

        // The load itself is what this function reports. Hook state is queried
        // separately: letting that query decide the result conflated "JNI
        // method missing" with "library did not load", and retries would then
        // never re-resolve the symbol.
        runCatching { nativePmmHooked() }.onSuccess { code ->
            Log.i("KonamikU", "pmmtool state after load: ${PmmState.fromCode(code)}")
        }.onFailure { e ->
            Log.e(
                "KonamikU",
                "injectPmmtool: nativePmmHooked unavailable: " +
                    "${e.javaClass.simpleName}: ${e.message}"
            )
        }
        return true
    }

    /**
     * Retries the hook once the library should be loaded. Called on each card
     * activation, so a device that only maps the NCI stack on first use still
     * ends up hooked; WAITING_NCI is the normal answer until then.
     */
    private fun tryInstallPmmHook(): PmmState = runCatching {
        PmmState.fromCode(nativePmmHooked())
    }.getOrDefault(PmmState.LOAD_FAILED)

    /** Implemented in bridge.c; returns a PMM_STATE_* code. */
    private external fun nativePmmHooked(): Int
}
