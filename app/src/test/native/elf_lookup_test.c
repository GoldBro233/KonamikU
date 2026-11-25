/*
 * Test for app/src/main/jni/elf_lookup.c. Runs on the host (glibc, which
 * relocates .dynamic in place) and on a device (bionic, which does not):
 *
 *   F=app/src/test/native; J=app/src/main/jni
 *   cc -shared -fPIC -DFIXTURE=gnu  -Wl,--hash-style=gnu  $F/elf_lookup_fixture.c -o /tmp/libfixture_gnu.so
 *   cc -shared -fPIC -DFIXTURE=sysv -Wl,--hash-style=sysv $F/elf_lookup_fixture.c -o /tmp/libfixture_sysv.so
 *   cc -std=c11 -Wall -Wextra -Werror -I $J $F/elf_lookup_test.c $J/elf_lookup.c -ldl -o /tmp/elf_lookup_test
 *   /tmp/elf_lookup_test /tmp/libfixture_gnu.so /tmp/libfixture_sysv.so
 *
 * For a device, build the same three with the NDK's aarch64-linux-android29-clang,
 * push them to /data/local/tmp and run the test there.
 */
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>

#include "elf_lookup.h"

static int failures;

#define CHECK(cond)                                                     \
    do {                                                                \
        if (!(cond)) {                                                  \
            fprintf(stderr, "%s:%d: CHECK(%s) failed\n", __FILE__,      \
                    __LINE__, #cond);                                   \
            failures++;                                                 \
        }                                                               \
    } while (0)

static const char *g_wanted;

static int is_wanted(const char *file_name) {
    return strcmp(file_name, g_wanted) == 0;
}

static const char *file_name(const char *path) {
    const char *slash = strrchr(path, '/');
    return slash != NULL ? slash + 1 : path;
}

/* Loaded privately, so only the in-memory tables can reach the symbol by name. */
static void finds_symbol_of_local_library(const char *path, const char *symbol) {
    void *handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    CHECK(handle != NULL);
    if (handle == NULL) {
        fprintf(stderr, "dlopen %s: %s\n", path, dlerror());
        return;
    }

    const char *object = NULL;
    void *found = elf_find_loaded_symbol(symbol, &object);
    CHECK(found != NULL);
    CHECK(found == dlsym(handle, symbol));
    CHECK(object != NULL && strcmp(file_name(object), file_name(path)) == 0);
    if (found != NULL) {
        CHECK(((int (*)(void)) found)() == 42);
    }

    g_wanted = file_name(path);
    CHECK(elf_loaded_object_matching(is_wanted));
}

/* konamiku_fixture_missing is in every fixture's .dynsym, never defined. */
static void undefined_entries_are_not_definitions(void) {
    const char *object = "untouched";
    CHECK(elf_find_loaded_symbol("konamiku_fixture_missing", &object) == NULL);
    CHECK(object == NULL);
}

static void unknown_symbol_and_library_are_absent(void) {
    CHECK(elf_find_loaded_symbol("konamiku_no_such_symbol", NULL) == NULL);
    g_wanted = "libkonamiku_no_such_library.so";
    CHECK(!elf_loaded_object_matching(is_wanted));
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: %s <libfixture_gnu.so> <libfixture_sysv.so>\n", argv[0]);
        return 2;
    }
    finds_symbol_of_local_library(argv[1], "konamiku_fixture_gnu");
    finds_symbol_of_local_library(argv[2], "konamiku_fixture_sysv");
    undefined_entries_are_not_definitions();
    unknown_symbol_and_library_are_absent();
    if (failures) {
        fprintf(stderr, "%d check(s) failed\n", failures);
        return 1;
    }
    puts("elf_lookup: all checks passed");
    return 0;
}
