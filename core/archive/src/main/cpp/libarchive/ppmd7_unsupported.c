/*
 * Stands in for archive_ppmd7.c, which is public domain and so outside the
 * licenses docs/spec.md section 8.5 allows (Q19). The 7z and RAR readers fail
 * when they ask for the model memory, which they do before decoding any PPMd
 * data.
 */

#include "archive_platform.h"
#include "archive_ppmd7_private.h"
#include "ppmd_unsupported.h"

static __thread int requested;

void hirari_ppmd_requested(void) { requested = 1; }

int hirari_take_ppmd_request(void) {
	int r = requested;
	requested = 0;
	return r;
}

static void construct(CPpmd7 *p) { (void)p; }

static Bool alloc(CPpmd7 *p, UInt32 size) {
	(void)p;
	(void)size;
	hirari_ppmd_requested();
	return False;
}

static void release(CPpmd7 *p) { (void)p; }

static void init(CPpmd7 *p, unsigned max_order) {
	(void)p;
	(void)max_order;
}

static void create_vtable(CPpmd7z_RangeDec *p) { (void)p; }

static Bool range_dec_init(CPpmd7z_RangeDec *p) {
	(void)p;
	return False;
}

static int decode_symbol(CPpmd7 *p, IPpmd7_RangeDec *rc) {
	(void)p;
	(void)rc;
	return -1;
}

const IPpmd7 __archive_ppmd7_functions = {
	.Ppmd7_Construct = construct,
	.Ppmd7_Alloc = alloc,
	.Ppmd7_Free = release,
	.Ppmd7_Init = init,
	.Ppmd7z_RangeDec_CreateVTable = create_vtable,
	.PpmdRAR_RangeDec_CreateVTable = create_vtable,
	.Ppmd7z_RangeDec_Init = range_dec_init,
	.PpmdRAR_RangeDec_Init = range_dec_init,
	.Ppmd7_DecodeSymbol = decode_symbol,
};
