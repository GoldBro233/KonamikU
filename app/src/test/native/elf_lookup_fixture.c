/*
 * Shared library loaded by elf_lookup_test.c. Built once per hash style with
 * -DFIXTURE=<name>, so each copy exports its own konamiku_fixture_<name>.
 */
#define CAT2(a, b) a##b
#define CAT(a, b) CAT2(a, b)

/* Undefined weak reference: appears in .dynsym without a definition. */
extern int konamiku_fixture_missing(void) __attribute__((weak));

int CAT(konamiku_fixture_, FIXTURE)(void) {
    return konamiku_fixture_missing ? konamiku_fixture_missing() : 42;
}
