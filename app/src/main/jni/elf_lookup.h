#ifndef KONAMIKU_ELF_LOOKUP_H
#define KONAMIKU_ELF_LOOKUP_H

/*
 * Looks [symbol] up in the dynamic symbol table of every object already
 * loaded into this process, in load order, and returns the first definition.
 *
 * Works from the loaded images alone (PT_DYNAMIC, the hash table, .dynsym and
 * .dynstr), so it finds libraries in another linker namespace, which dlsym
 * does not see, and libraries mapped straight out of an APK, which a reader
 * that parses the file on disk cannot handle. It never opens a file. Symbols
 * that live only in .symtab are not found; release builds strip that table.
 * GNU indirect functions are skipped: their address is the resolver's.
 *
 * @param object if non-NULL, receives the defining object's path as the
 *               loader reports it; valid while that object stays loaded.
 * @return the symbol's address, or NULL when no loaded object defines it.
 */
void *elf_find_loaded_symbol(const char *symbol, const char **object);

/*
 * @return non-zero when [match] accepts the file name (the part of the path
 *         after the last '/') of some loaded object.
 */
int elf_loaded_object_matching(int (*match)(const char *file_name));

#endif
