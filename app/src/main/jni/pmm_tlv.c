#include "pmm_tlv.h"

#include <string.h>

/*
 * Pure TLV handling for the nfa_dm_check_set_config hook, kept free of
 * Android and Dobby so it can be compiled and tested on the host
 * (app/src/test/native/pmm_tlv_test.c).
 *
 * Values are never matched by scanning raw bytes: 51 08 or eight FF can occur
 * inside an NFCID2 or straddle two elements, and rewriting those corrupts the
 * NFCC configuration.
 */

int tlv_is_well_formed(const uint8_t *buf, int len) {
    if (buf == NULL || len < 0) return 0;
    int i = 0;
    while (i < len) {
        if (i + 2 > len) return 0;
        int end = i + 2 + buf[i + 1];
        if (end > len) return 0;
        i = end;
    }
    return 1;
}

static int is_placeholder(const uint8_t *p) {
    for (int k = 0; k < PMM_LEN; ++k) {
        if (p[k] != 0xFF) return 0;
    }
    return 1;
}

static int write_pmm(uint8_t *dst, const uint8_t pmm[PMM_LEN]) {
    if (memcmp(dst, pmm, PMM_LEN) == 0) return 0;
    memcpy(dst, pmm, PMM_LEN);
    return 1;
}

int tlv_patch_pmm(uint8_t *buf, int len, const uint8_t pmm[PMM_LEN]) {
    if (!tlv_is_well_formed(buf, len)) return 0;
    int changed = 0;
    for (int i = 0; i < len; i += 2 + buf[i + 1]) {
        uint8_t type = buf[i];
        uint8_t vlen = buf[i + 1];
        uint8_t *value = buf + i + 2;
        if (type == NCI_PARAM_ID_LF_T3T_PMM && vlen == PMM_LEN) {
            changed |= write_pmm(value, pmm);
        } else if (type >= NCI_PARAM_ID_LF_T3T_ID1 && type <= NCI_PARAM_ID_LF_T3T_ID16 &&
                   vlen == NCI2_LF_T3T_ID_LEN &&
                   is_placeholder(value + NCI2_LF_T3T_ID_PMM_OFFSET)) {
            changed |= write_pmm(value + NCI2_LF_T3T_ID_PMM_OFFSET, pmm);
        }
    }
    return changed;
}

int tlv_swap_t3t_flags(uint8_t *buf, int len) {
    if (!tlv_is_well_formed(buf, len)) return 0;
    int changed = 0;
    for (int i = 0; i < len; i += 2 + buf[i + 1]) {
        if (buf[i] == NCI_PARAM_ID_LF_T3T_FLAGS2 && buf[i + 1] == 2 && buf[i + 2] != buf[i + 3]) {
            uint8_t lo = buf[i + 2];
            buf[i + 2] = buf[i + 3];
            buf[i + 3] = lo;
            changed = 1;
        }
    }
    return changed;
}
