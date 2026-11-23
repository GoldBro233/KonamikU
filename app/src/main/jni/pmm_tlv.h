#ifndef KONAMIKU_PMM_TLV_H
#define KONAMIKU_PMM_TLV_H

#include <stdint.h>

#define PMM_LEN 8

/* NCI parameter IDs (AOSP libnfc-nci nci_defs.h). */
#define NCI_PARAM_ID_LF_T3T_ID1    0x40   /* ..0x4F: one per T3T identifier */
#define NCI_PARAM_ID_LF_T3T_ID16   0x4F
#define NCI_PARAM_ID_LF_T3T_PMM    0x51   /* NCI 1.0: one PMm for all identifiers */
#define NCI_PARAM_ID_LF_T3T_FLAGS2 0x53

/* LF_T3T_ID value under NCI 2.0: System Code (2) + NFCID2 (8) + PMm (8). */
#define NCI2_LF_T3T_ID_LEN 18
#define NCI2_LF_T3T_ID_PMM_OFFSET 10

/*
 * Returns non-zero when [buf] is a well-formed TLV sequence: every element's
 * header and value fit, and nothing trails the last element.
 */
int tlv_is_well_formed(const uint8_t *buf, int len);

/*
 * Writes [pmm] into the PMm fields of a well-formed TLV buffer:
 *  - LF_T3T_PMM (51h, length 8) is the PMm parameter itself and is replaced;
 *  - an NCI 2.0 LF_T3T_ID (40h-4Fh, length 18) carries a PMm in bytes 10..17
 *    of its value, replaced only while it is the FF*8 placeholder the stack
 *    writes when no PMm was registered.
 * Anything else, including a malformed buffer, is left byte-for-byte alone.
 *
 * @return non-zero when a byte changed.
 */
int tlv_patch_pmm(uint8_t *buf, int len, const uint8_t pmm[PMM_LEN]);

/*
 * Byte-swaps every LF_T3T_FLAGS (53h, length 2) value of a well-formed TLV
 * buffer. Malformed buffers are left alone.
 *
 * @return non-zero when a byte changed.
 */
int tlv_swap_t3t_flags(uint8_t *buf, int len);

#endif
