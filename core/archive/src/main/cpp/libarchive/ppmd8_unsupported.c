/*
 * Stands in for archive_ppmd8.c, which is public domain and so outside the
 * licenses docs/spec.md section 8.5 allows. The ZIP reader still lists PPMd
 * entries; reading their data fails when it asks for the model memory.
 */

#include "archive_platform.h"
#include "archive_ppmd8_private.h"

static void construct(CPpmd8 *p) { (void)p; }

static Bool alloc(CPpmd8 *p, UInt32 size) {
	(void)p;
	(void)size;
	return False;
}

static void release(CPpmd8 *p) { (void)p; }

static void init(CPpmd8 *p, unsigned max_order, unsigned restore_method) {
	(void)p;
	(void)max_order;
	(void)restore_method;
}

static int range_dec_init(CPpmd8 *p) {
	(void)p;
	return 0;
}

static int decode_symbol(CPpmd8 *p) {
	(void)p;
	return -1;
}

const IPpmd8 __archive_ppmd8_functions = {
	.Ppmd8_Construct = construct,
	.Ppmd8_Alloc = alloc,
	.Ppmd8_Free = release,
	.Ppmd8_Init = init,
	.Ppmd8_RangeDec_Init = range_dec_init,
	.Ppmd8_DecodeSymbol = decode_symbol,
};
