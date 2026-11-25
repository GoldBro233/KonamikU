#include <jni.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>
#include <android/log.h>
#include <sys/system_properties.h>
#include "dobby.h"
#include "elf_lookup.h"
#include "pmm_tlv.h"

#define TAG     "KonamikU_pmm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

/* ── Target PMm ──────────────────────────────────────────────────────────
 * 00 F1 00 00 00 01 43 00  (KONAMI reader compatible)
 */
static const uint8_t TARGET_PMM[8] = {
    0x00, 0xF1, 0x00, 0x00, 0x00, 0x01, 0x43, 0x00
};

/* Mangled name of the NCI stack's config writer, 1684 bytes on stock Android
 * 17 (RMX8899) and in ST's libstnfc_nci_jni.so (Xiaomi 25019PNF3C), which
 * exports it from .dynsym. */
#define TARGET_SYMBOL "_Z23nfa_dm_check_set_confighPhb"

/*
 * Real signature (verified by disassembling libnfc-nci.so):
 *
 *   int nfa_dm_check_set_config(uint8_t tlv_len, uint8_t *tlv, int flags)
 *
 *  - tlv_len : TOTAL length of the TLV buffer, not the payload length.
 *              The prologue does `and w21, w0, #0xff` then walks the buffer
 *              with `w8 = tlv_len - 2`, comparing against each TLV's own
 *              length byte. It is uint8_t, so <= 255.
 *  - tlv     : the TLV buffer itself.
 *  - flags   : NOT a length. The body only ever tests bit 0 (`tbz w8, #0x0`)
 *              and the low byte (`tst w1, #0xff`), i.e. option bits.
 *
 * The original hook ignored tlv_len entirely and scanned a fixed 0x20 bytes,
 * so a short TLV (e.g. 4 bytes) caused an out-of-bounds read of up to 28
 * bytes and an out-of-bounds 8-byte memcpy write into com.android.nfc's heap.
 */
typedef int (*nfa_dm_check_set_config_t)(uint8_t tlv_len, uint8_t *tlv, int flags);
static nfa_dm_check_set_config_t orig_nfa_dm_check_set_config = NULL;

/*
 * Hook state, returned to Kotlin as an int.
 *
 * These are part of the module's contract with the UI, so the numeric values
 * are stable and mirrored in KonamikuModule.PmmState. "Not installed" is not
 * one state: waiting for the NCI stack resolves itself on the next tag contact,
 * while a missing symbol never will - telling the user which is which is the
 * difference between "try again" and "this device cannot".
 */
#define PMM_STATE_NOT_ATTEMPTED 0   /* library not loaded yet */
#define PMM_STATE_INSTALLED     1   /* hook live */
#define PMM_STATE_WAITING_NCI   2   /* loader has no NCI stack mapped yet */
#define PMM_STATE_NO_SYMBOL     3   /* searched, symbol absent on this ROM */
#define PMM_STATE_HOOK_FAILED   4   /* symbol found, DobbyHook refused */

/* Guards g_hook_state and the DobbyHook call. try_install_hook is reached
 * from JNI_OnLoad, Application.onCreate and binder threads; without this two
 * of them could both resolve the symbol and both hook it, and a late
 * WAITING_NCI result could overwrite INSTALLED. */
static pthread_mutex_t g_hook_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_hook_state = PMM_STATE_NOT_ATTEMPTED;

static const char *state_name(int state) {
    switch (state) {
        case PMM_STATE_INSTALLED:   return "INSTALLED";
        case PMM_STATE_WAITING_NCI: return "WAITING_NCI";
        case PMM_STATE_NO_SYMBOL:   return "NO_SYMBOL";
        case PMM_STATE_HOOK_FAILED: return "HOOK_FAILED";
        default:                    return "NOT_ATTEMPTED";
    }
}

/* Sony CXD224x (Xperia, NCI 1.0) reads the 2-byte LF_T3T_FLAGS (0x53) value
 * big-endian. AOSP writes it little-endian, so "enable identifier 1" (01 00)
 * reaches the chip as bit 8 - an identifier past LF_T3T_MAX - and is
 * rejected with STATUS_INVALID_PARAM, leaving the host IDm disabled: the
 * NFCC never answers SENSF_REQ with it. Observed on G8441: 01 00 rejected,
 * 00 01 accepted and the reader reads the IDm. Other controllers read it
 * little-endian as the spec says, so this is gated on the CXD224x config. */
static int is_cxd224x(void) {
    static int cached = -1;
    if (cached < 0) {
        char prop[PROP_VALUE_MAX] = {0};
        __system_property_get("persist.nfc_cfg.config_file_name", prop);
        cached = strstr(prop, "cxd224x") != NULL;
        LOGI("CXD224x LF_T3T_FLAGS byte swap %s", cached ? "enabled" : "disabled");
    }
    return cached;
}

static int hooked_nfa_dm_check_set_config(uint8_t tlv_len, uint8_t *tlv, int flags) {
    /* Never write through [tlv]: callers may pass read-only data. On this
     * stack nfa_dm_set_init_nci_params() hands in a buffer in .rodata, and
     * patching it in place faulted (SEGV_ACCERR) on every NFC start. Patch a
     * copy instead; the callee copies the TLVs before returning, so a stack
     * buffer is safe. tlv_len is a uint8_t, so 255 bytes always fit. */
    uint8_t copy[256];
    uint8_t *arg = tlv;
    if (tlv != NULL && tlv_len > 0) {
        memcpy(copy, tlv, tlv_len);
        int patched = tlv_patch_pmm(copy, (int) tlv_len, TARGET_PMM);
        if (patched) {
            LOGI("PMm patched (tlv_len=%d)", (int) tlv_len);
        }
        if (is_cxd224x() && tlv_swap_t3t_flags(copy, (int) tlv_len)) {
            LOGI("LF_T3T_FLAGS byte-swapped (tlv_len=%d)", (int) tlv_len);
            patched = 1;
        }
        if (patched) {
            arg = copy;
        }
    }

    /* orig_ is only non-NULL when DobbyHook succeeded; guard anyway so a
     * partial hook can never jump through a null pointer. */
    if (orig_nfa_dm_check_set_config == NULL) {
        return 0;
    }
    return orig_nfa_dm_check_set_config(tlv_len, arg, flags);
}

/* Whether a loaded file name looks like an NCI stack library. */
static int is_nci_stack(const char *file_name) {
    return strstr(file_name, "nfc") != NULL && strstr(file_name, "nci") != NULL;
}

/**
 * Attempt the hook once. Callers hold g_hook_lock.
 *
 * @return a PMM_STATE_* value. Once installed it is a no-op, and after a
 *         temporary miss (NCI not mapped) it retries.
 */
static int try_install_hook_locked(void) {
    if (g_hook_state == PMM_STATE_INSTALLED) {
        return g_hook_state;
    }

    /*
     * The library that owns the symbol differs by platform and vendor:
     * libnfc-nci.so up to Android 13, libnfc_nci_jni.so from 14 (the stack
     * folded into the JNI layer), and vendor builds such as ST's
     * libstnfc_nci_jni.so, which Xiaomi ships inside
     * /system/app/Nfc_st/Nfc_st.apk. It is loaded into the NFC app's own
     * linker namespace, where dlsym from this library cannot see it, and may
     * be mapped straight out of an APK.
     *
     * Dobby's resolver handled neither: after dlsym failed it mmap'd the file
     * behind every mapped module and parsed it as an ELF, and for a library
     * inside an APK that file is the APK itself - parsing the zip as an ELF
     * header crashed com.android.nfc on every start. The dynamic symbol
     * tables of the loaded objects are searched in memory instead.
     */
    const char *object = NULL;
    void *target = elf_find_loaded_symbol(TARGET_SYMBOL, &object);

    if (!target) {
        /* The NCI stack is loaded lazily, so a miss before it is mapped
         * resolves on a later call. Once it is mapped and still lacks the
         * symbol, the ROM diverges from stock and retrying will not help. */
        int nci_loaded = elf_loaded_object_matching(is_nci_stack);
        g_hook_state = nci_loaded ? PMM_STATE_NO_SYMBOL : PMM_STATE_WAITING_NCI;
        LOGW("symbol %s not found (nci loaded=%d) -> %s",
             TARGET_SYMBOL, nci_loaded, state_name(g_hook_state));
        return g_hook_state;
    }

    LOGI("symbol found in %s @ %p", object != NULL ? object : "?", target);

    int ret = DobbyHook(
        target,
        (dobby_dummy_func_t) hooked_nfa_dm_check_set_config,
        (dobby_dummy_func_t *) &orig_nfa_dm_check_set_config
    );

    if (ret == 0 && orig_nfa_dm_check_set_config != NULL) {
        g_hook_state = PMM_STATE_INSTALLED;
        LOGI("hook installed OK");
        return g_hook_state;
    }

    g_hook_state = PMM_STATE_HOOK_FAILED;
    LOGW("DobbyHook failed: ret=%d orig=%p -> %s",
         ret, (void *) orig_nfa_dm_check_set_config, state_name(g_hook_state));
    return g_hook_state;
}

static int try_install_hook(void) {
    pthread_mutex_lock(&g_hook_lock);
    int state = try_install_hook_locked();
    pthread_mutex_unlock(&g_hook_lock);
    return state;
}

/**
 * Called by the module to query (and, if needed, retry) the hook.
 *
 * Injection happens in NfcApplication.onCreate(), which can run before the
 * NCI stack is dlopen'd - the NFC process sits idle until the first reader or
 * HCE use. WAITING_NCI is therefore a temporary answer, not a failure.
 *
 * @return a PMM_STATE_* value, mirrored by KonamikuModule.PmmState.
 */
JNIEXPORT jint JNICALL
Java_org_cf0x_konamiku_xposed_KonamikuModule_nativePmmHooked(JNIEnv *env, jobject thiz) {
    (void) env;
    (void) thiz;
    return try_install_hook();
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    LOGI("pmmtool loading");
    try_install_hook();
    return JNI_VERSION_1_6;
}
