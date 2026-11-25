cmake_minimum_required(VERSION 3.22.1)
project("konamiku")

# ── Dobby (LSPosed fork, built from source with -fPIC) ───────────────────
set(DOBBY_GENERATE_SHARED OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLE         OFF CACHE BOOL "" FORCE)
set(BUILD_TEST            OFF CACHE BOOL "" FORCE)
# Its resolver parses the file behind each mapped module as an ELF, which
# crashes on libraries mapped out of an APK; bridge.c uses elf_lookup.c.
set(Plugin.SymbolResolver OFF CACHE BOOL "" FORCE)
add_subdirectory(Dobby)

# ── libpmm.so ────────────────────────────────────────────────────────────
add_library(pmm SHARED bridge.c elf_lookup.c pmm_tlv.c)

target_include_directories(pmm PRIVATE
        Dobby/include
)

target_link_libraries(pmm
        dobby
        log
)