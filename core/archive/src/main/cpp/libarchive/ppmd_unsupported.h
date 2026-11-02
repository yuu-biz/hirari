/*
 * The PPMd stubs record that a reader asked for a PPMd model, so that the JNI
 * layer can tell an unsupported method from a real allocation failure, which
 * libarchive reports the same way. The record is per thread: every session
 * makes its libarchive calls on one thread of its own.
 */

#ifndef HIRARI_PPMD_UNSUPPORTED_H
#define HIRARI_PPMD_UNSUPPORTED_H

void hirari_ppmd_requested(void);

/* Whether a PPMd model was asked for on this thread since the last call. */
int hirari_take_ppmd_request(void);

#endif
