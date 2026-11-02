/*
 * bzip2 built with BZ_NO_STDIO asks the program for this handler. bzip2 calls
 * it for states its decompressor never reaches and returns an error after it,
 * so returning keeps the process alive, as docs/spec.md section 6.1 requires.
 */

void bz_internal_error(int errcode);

void bz_internal_error(int errcode) { (void)errcode; }
