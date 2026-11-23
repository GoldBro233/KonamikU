/*
 * Host test for app/src/main/jni/pmm_tlv.c.
 *
 *   cc -std=c11 -Wall -Wextra -Werror -I app/src/main/jni \
 *      app/src/test/native/pmm_tlv_test.c app/src/main/jni/pmm_tlv.c \
 *      -o /tmp/pmm_tlv_test && /tmp/pmm_tlv_test
 */
#include <stdio.h>
#include <string.h>

#include "pmm_tlv.h"

static const uint8_t PMM[PMM_LEN] = {0x00, 0xF1, 0x00, 0x00, 0x00, 0x01, 0x43, 0x00};

static int failures;

#define CHECK(cond)                                                     \
    do {                                                                \
        if (!(cond)) {                                                  \
            fprintf(stderr, "%s:%d: CHECK(%s) failed\n", __FILE__,      \
                    __LINE__, #cond);                                   \
            failures++;                                                 \
        }                                                               \
    } while (0)

/* Patching must not touch a single byte of [buf]. */
static void expect_unchanged(uint8_t *buf, int len) {
    uint8_t before[256];
    memcpy(before, buf, (size_t) len);
    CHECK(tlv_patch_pmm(buf, len, PMM) == 0);
    CHECK(memcmp(before, buf, (size_t) len) == 0);
}

/* NCI 1.0 as written by nfc_ce_t3t_set_listen_params: ID1 (10 bytes),
 * FLAGS2, CON_ADV_FEAT, then LF_T3T_PMM = FF*8. */
static void nci1_pmm_parameter_is_replaced(void) {
    uint8_t buf[] = {
        0x40, 0x0A, 0x88, 0xB4, 0x02, 0xFE, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01,
        0x53, 0x02, 0x01, 0x00,
        0x50, 0x01, 0x01,
        0x51, 0x08, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
    };
    uint8_t expected[sizeof buf];
    memcpy(expected, buf, sizeof buf);
    memcpy(expected + 21, PMM, PMM_LEN);

    CHECK(tlv_patch_pmm(buf, (int) sizeof buf, PMM) == 1);
    CHECK(memcmp(buf, expected, sizeof buf) == 0);
}

/* NCI 2.0: SC + NFCID2 + PMm inside LF_T3T_ID; only the placeholder PMm. */
static void nci2_identifier_placeholder_pmm_is_replaced(void) {
    uint8_t buf[] = {
        0x40, 0x12, 0x88, 0xB4, 0x02, 0xFE, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01,
        0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
        0x53, 0x02, 0x01, 0x00,
        0x55, 0x01, 0x01,
    };
    uint8_t expected[sizeof buf];
    memcpy(expected, buf, sizeof buf);
    memcpy(expected + 12, PMM, PMM_LEN);

    CHECK(tlv_patch_pmm(buf, (int) sizeof buf, PMM) == 1);
    CHECK(memcmp(buf, expected, sizeof buf) == 0);
}

/* A PMm the service registered itself is not a placeholder. */
static void nci2_registered_pmm_is_kept(void) {
    uint8_t buf[] = {
        0x41, 0x12, 0x88, 0xB4, 0x02, 0xFE, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01,
        0x01, 0x20, 0x22, 0x04, 0x27, 0x67, 0x77, 0xFF,
    };
    expect_unchanged(buf, (int) sizeof buf);
}

/* 51 08 and FF*8 inside an NFCID2 are data, not PMm fields. */
static void lookalikes_inside_values_are_kept(void) {
    uint8_t buf[] = {
        0x40, 0x0A, 0x88, 0xB4, 0x51, 0x08, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
        0x40, 0x0A, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
    };
    expect_unchanged(buf, (int) sizeof buf);
}

static void truncated_value_is_left_alone(void) {
    uint8_t buf[] = {
        0x53, 0x02, 0x01, 0x00,
        0x51, 0x08, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
    };
    CHECK(!tlv_is_well_formed(buf, (int) sizeof buf));
    expect_unchanged(buf, (int) sizeof buf);
}

static void dangling_type_byte_is_left_alone(void) {
    uint8_t buf[] = {
        0x51, 0x08, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
        0x53,
    };
    CHECK(!tlv_is_well_formed(buf, (int) sizeof buf));
    expect_unchanged(buf, (int) sizeof buf);
}

static void wrong_lengths_are_left_alone(void) {
    uint8_t buf[] = {
        0x51, 0x07, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
        0x40, 0x11, 0x88, 0xB4, 0x02, 0xFE, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01,
        0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF,
    };
    expect_unchanged(buf, (int) sizeof buf);
}

static void empty_buffer_is_well_formed_and_untouched(void) {
    uint8_t buf[1] = {0x51};
    CHECK(tlv_is_well_formed(buf, 0));
    CHECK(tlv_patch_pmm(buf, 0, PMM) == 0);
    CHECK(buf[0] == 0x51);
}

static void already_patched_reports_no_change(void) {
    uint8_t buf[] = {0x51, 0x08, 0x00, 0xF1, 0x00, 0x00, 0x00, 0x01, 0x43, 0x00};
    expect_unchanged(buf, (int) sizeof buf);
}

static void flags_are_swapped_only_in_flags_elements(void) {
    uint8_t buf[] = {
        0x40, 0x0A, 0x88, 0xB4, 0x53, 0x02, 0x01, 0x00, 0x00, 0x00, 0x00, 0x01,
        0x53, 0x02, 0x01, 0x00,
    };
    uint8_t expected[sizeof buf];
    memcpy(expected, buf, sizeof buf);
    expected[14] = 0x00;
    expected[15] = 0x01;

    CHECK(tlv_swap_t3t_flags(buf, (int) sizeof buf) == 1);
    CHECK(memcmp(buf, expected, sizeof buf) == 0);
}

static void flags_in_malformed_buffer_are_left_alone(void) {
    uint8_t buf[] = {0x53, 0x02, 0x01, 0x00, 0x40, 0x0A, 0x88};
    uint8_t before[sizeof buf];
    memcpy(before, buf, sizeof buf);
    CHECK(tlv_swap_t3t_flags(buf, (int) sizeof buf) == 0);
    CHECK(memcmp(buf, before, sizeof buf) == 0);
}

int main(void) {
    nci1_pmm_parameter_is_replaced();
    nci2_identifier_placeholder_pmm_is_replaced();
    nci2_registered_pmm_is_kept();
    lookalikes_inside_values_are_kept();
    truncated_value_is_left_alone();
    dangling_type_byte_is_left_alone();
    wrong_lengths_are_left_alone();
    empty_buffer_is_well_formed_and_untouched();
    already_patched_reports_no_change();
    flags_are_swapped_only_in_flags_elements();
    flags_in_malformed_buffer_are_left_alone();
    if (failures) {
        fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    puts("pmm_tlv: all checks passed");
    return 0;
}
