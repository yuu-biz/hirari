/*
 * libFuzzer target for the archive reader. It sets libarchive up as libarchive_jni.c does (the same formats and
 * filters, read, seek and skip callbacks over a buffer, a first read of 4096 bytes after a seek), lists every entry
 * and reads its data up to a cap, and then opens the buffer a second time and skips to the last entry as the
 * session does when it moves back. The JNI glue and the Java side are not part of it; the device fuzzer covers those.
 */

#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <archive.h>
#include <archive_entry.h>

#define FIRST_READ_SIZE 4096
#define BLOCK_SIZE 65536
#define MAX_ENTRIES 256
#define MAX_ENTRY_BYTES (8u << 20)
#define MAX_TOTAL_BYTES (32u << 20)

typedef struct {
	const uint8_t *data;
	int64_t size;
	int64_t position;
	int sequential;
	uint8_t block[BLOCK_SIZE];
} source;

static la_ssize_t read_callback(struct archive *a, void *opaque, const void **buffer) {
	(void)a;
	source *s = opaque;
	int64_t left = s->size > s->position ? s->size - s->position : 0;
	int64_t want = s->sequential ? BLOCK_SIZE : FIRST_READ_SIZE;
	int64_t n = left < want ? left : want;
	if (n > 0) memcpy(s->block, s->data + s->position, (size_t)n);
	*buffer = s->block;
	s->position += n;
	s->sequential = 1;
	return (la_ssize_t)n;
}

static la_int64_t seek_callback(struct archive *a, void *opaque, la_int64_t offset, int whence) {
	source *s = opaque;
	int64_t base;
	switch (whence) {
	case SEEK_SET: base = 0; break;
	case SEEK_CUR: base = s->position; break;
	case SEEK_END: base = s->size; break;
	default:
		archive_set_error(a, EINVAL, "Unknown seek origin %d", whence);
		return ARCHIVE_FATAL;
	}
	if ((offset > 0 && base > INT64_MAX - offset) || base + offset < 0) {
		archive_set_error(a, EINVAL, "Seek out of range");
		return ARCHIVE_FATAL;
	}
	s->position = base + offset;
	s->sequential = 0;
	return s->position;
}

static la_int64_t skip_callback(struct archive *a, void *opaque, la_int64_t request) {
	(void)a;
	source *s = opaque;
	int64_t left = s->size > s->position ? s->size - s->position : 0;
	int64_t skipped = request < left ? request : left;
	if (skipped < 0) skipped = 0;
	s->position += skipped;
	s->sequential = 0;
	return skipped;
}

static struct archive *open_archive(source *s) {
	s->position = 0;
	s->sequential = 0;
	struct archive *a = archive_read_new();
	if (a == NULL) return NULL;
	int r = archive_read_support_format_zip(a);
	if (r == ARCHIVE_OK) r = archive_read_support_format_rar(a);
	if (r == ARCHIVE_OK) r = archive_read_support_format_rar5(a);
	if (r == ARCHIVE_OK) r = archive_read_support_format_7zip(a);
	if (r == ARCHIVE_OK) r = archive_read_support_format_tar(a);
	if (r == ARCHIVE_OK) r = archive_read_support_filter_gzip(a);
	if (r == ARCHIVE_OK) r = archive_read_support_filter_xz(a);
	if (r == ARCHIVE_OK) r = archive_read_support_filter_zstd(a);
	if (r == ARCHIVE_OK) r = archive_read_support_filter_bzip2(a);
	if (r == ARCHIVE_OK) r = archive_read_set_read_callback(a, read_callback);
	if (r == ARCHIVE_OK) r = archive_read_set_seek_callback(a, seek_callback);
	if (r == ARCHIVE_OK) r = archive_read_set_skip_callback(a, skip_callback);
	if (r == ARCHIVE_OK) r = archive_read_set_callback_data(a, s);
	if (r == ARCHIVE_OK) r = archive_read_open1(a);
	if (r != ARCHIVE_OK && r != ARCHIVE_WARN) {
		archive_read_free(a);
		return NULL;
	}
	return a;
}

static int ok(int r) { return r == ARCHIVE_OK || r == ARCHIVE_WARN; }

/* Reads the data of the current entry up to the caps. Returns the bytes read. */
static uint64_t read_entry(struct archive *a) {
	uint64_t total = 0;
	for (;;) {
		const void *block;
		size_t size;
		la_int64_t offset;
		int r = archive_read_data_block(a, &block, &size, &offset);
		if (!ok(r)) return total;
		total += size;
		if (total >= MAX_ENTRY_BYTES) return total;
	}
}

int LLVMFuzzerTestOneInput(const uint8_t *data, size_t size) {
	source *s = calloc(1, sizeof(*s));
	if (s == NULL) return 0;
	s->data = data;
	s->size = (int64_t)size;
	struct archive *a = open_archive(s);
	if (a != NULL) {
		uint64_t total = 0;
		int entries = 0;
		struct archive_entry *entry;
		while (entries < MAX_ENTRIES && ok(archive_read_next_header(a, &entry))) {
			(void)archive_entry_pathname(entry);
			(void)archive_entry_size_is_set(entry);
			(void)archive_entry_is_data_encrypted(entry);
			(void)archive_format_name(a);
			entries++;
			if (archive_entry_filetype(entry) == AE_IFDIR) continue;
			if (total < MAX_TOTAL_BYTES) total += read_entry(a);
		}
		archive_read_free(a);
		if (entries > 1) {
			a = open_archive(s);
			if (a != NULL) {
				for (int i = 0; i < entries && ok(archive_read_next_header(a, &entry)); i++) {
				}
				archive_read_free(a);
			}
		}
	}
	free(s);
	return 0;
}
