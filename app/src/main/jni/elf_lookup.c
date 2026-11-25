/* Host and device test: app/src/test/native/elf_lookup_test.c. */

#define _GNU_SOURCE /* dl_iterate_phdr on glibc, for the host test */

#include "elf_lookup.h"

#include <link.h>
#include <stddef.h>
#include <stdint.h>
#include <string.h>

#define SYM_TYPE(info) ((info) & 0xf)
#ifndef STT_GNU_IFUNC
#define STT_GNU_IFUNC 10
#endif

struct dyn_tables {
    const ElfW(Sym) *symtab;
    const char *strtab;
    size_t strsz;
    const uint32_t *gnu_hash;
    const uint32_t *sysv_hash;
};

/*
 * glibc's loader rewrites the address entries of .dynamic to absolute
 * addresses; bionic leaves them as virtual addresses relative to the load
 * bias. A relative address is smaller than the bias of any object mapped above
 * zero, which tells the two apart.
 */
static ElfW(Addr) dyn_addr(const struct dl_phdr_info *info, ElfW(Addr) ptr) {
    return ptr >= info->dlpi_addr ? ptr : info->dlpi_addr + ptr;
}

static int read_tables(const struct dl_phdr_info *info, struct dyn_tables *t) {
    const ElfW(Dyn) *dyn = NULL;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) {
            dyn = (const ElfW(Dyn) *) (info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
            break;
        }
    }
    if (dyn == NULL) {
        return 0;
    }

    memset(t, 0, sizeof(*t));
    for (; dyn->d_tag != DT_NULL; ++dyn) {
        switch (dyn->d_tag) {
            case DT_SYMTAB:
                t->symtab = (const ElfW(Sym) *) dyn_addr(info, dyn->d_un.d_ptr);
                break;
            case DT_STRTAB:
                t->strtab = (const char *) dyn_addr(info, dyn->d_un.d_ptr);
                break;
            case DT_STRSZ:
                t->strsz = dyn->d_un.d_val;
                break;
            case DT_GNU_HASH:
                t->gnu_hash = (const uint32_t *) dyn_addr(info, dyn->d_un.d_ptr);
                break;
            case DT_HASH:
                t->sysv_hash = (const uint32_t *) dyn_addr(info, dyn->d_un.d_ptr);
                break;
            default:
                break;
        }
    }
    return t->symtab != NULL && t->strtab != NULL &&
           (t->gnu_hash != NULL || t->sysv_hash != NULL);
}

/* A usable definition named [name]; the name is read only inside .dynstr. */
static int defines(const struct dyn_tables *t, const ElfW(Sym) *sym,
                   const char *name, size_t len) {
    if (sym->st_shndx == SHN_UNDEF || sym->st_value == 0 ||
        SYM_TYPE(sym->st_info) == STT_GNU_IFUNC) {
        return 0;
    }
    if (sym->st_name >= t->strsz || t->strsz - sym->st_name <= len) {
        return 0;
    }
    return memcmp(t->strtab + sym->st_name, name, len + 1) == 0;
}

static const ElfW(Sym) *gnu_lookup(const struct dyn_tables *t, const char *name, size_t len) {
    const uint32_t *h = t->gnu_hash;
    const uint32_t nbuckets = h[0], symoffset = h[1], bloom_size = h[2], bloom_shift = h[3];
    if (nbuckets == 0 || bloom_size == 0) {
        return NULL;
    }
    const ElfW(Addr) *bloom = (const ElfW(Addr) *) (h + 4);
    const uint32_t *buckets = (const uint32_t *) (bloom + bloom_size);
    const uint32_t *chain = buckets + nbuckets;

    uint32_t hash = 5381;
    for (const unsigned char *p = (const unsigned char *) name; *p; ++p) {
        hash = hash * 33 + *p;
    }

    const uint32_t bits = sizeof(ElfW(Addr)) * 8;
    const ElfW(Addr) word = bloom[(hash / bits) % bloom_size];
    const ElfW(Addr) mask = ((ElfW(Addr)) 1 << (hash % bits)) |
                            ((ElfW(Addr)) 1 << ((hash >> bloom_shift) % bits));
    if ((word & mask) != mask) {
        return NULL;
    }

    uint32_t index = buckets[hash % nbuckets];
    if (index < symoffset) {
        return NULL;
    }
    for (;; ++index) {
        const uint32_t entry = chain[index - symoffset];
        if ((entry | 1) == (hash | 1) && defines(t, &t->symtab[index], name, len)) {
            return &t->symtab[index];
        }
        if (entry & 1) {
            return NULL;
        }
    }
}

static const ElfW(Sym) *sysv_lookup(const struct dyn_tables *t, const char *name, size_t len) {
    const uint32_t *h = t->sysv_hash;
    const uint32_t nbucket = h[0], nchain = h[1];
    if (nbucket == 0) {
        return NULL;
    }
    const uint32_t *bucket = h + 2;
    const uint32_t *chain = bucket + nbucket;

    uint32_t hash = 0;
    for (const unsigned char *p = (const unsigned char *) name; *p; ++p) {
        hash = (hash << 4) + *p;
        const uint32_t g = hash & 0xf0000000u;
        hash ^= g >> 24;
        hash &= ~g;
    }

    for (uint32_t i = bucket[hash % nbucket]; i != STN_UNDEF && i < nchain; i = chain[i]) {
        if (defines(t, &t->symtab[i], name, len)) {
            return &t->symtab[i];
        }
    }
    return NULL;
}

struct find_ctx {
    const char *name;
    size_t len;
    void *addr;
    const char *object;
};

static int find_in_object(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    struct find_ctx *ctx = data;
    struct dyn_tables t;
    if (!read_tables(info, &t)) {
        return 0;
    }
    const ElfW(Sym) *sym = t.gnu_hash != NULL ? gnu_lookup(&t, ctx->name, ctx->len)
                                              : sysv_lookup(&t, ctx->name, ctx->len);
    if (sym == NULL) {
        return 0;
    }
    ctx->addr = (void *) (info->dlpi_addr + sym->st_value);
    ctx->object = info->dlpi_name;
    return 1;
}

void *elf_find_loaded_symbol(const char *symbol, const char **object) {
    struct find_ctx ctx = {symbol, strlen(symbol), NULL, NULL};
    dl_iterate_phdr(find_in_object, &ctx);
    if (object != NULL) {
        *object = ctx.object;
    }
    return ctx.addr;
}

struct match_ctx {
    int (*match)(const char *file_name);
};

static int match_object(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    const struct match_ctx *ctx = data;
    const char *path = info->dlpi_name;
    if (path == NULL || path[0] == '\0') {
        return 0;
    }
    const char *slash = strrchr(path, '/');
    return ctx->match(slash != NULL ? slash + 1 : path) ? 1 : 0;
}

int elf_loaded_object_matching(int (*match)(const char *file_name)) {
    struct match_ctx ctx = {match};
    return dl_iterate_phdr(match_object, &ctx) != 0;
}
