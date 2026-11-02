/*
 * The JNI half of NativeArchive.kt. One session reads one archive through the
 * Kotlin bridge object, whose read method fills a direct buffer from a
 * ByteSource. Every function here runs on the session's thread, and libarchive
 * calls the callbacks only from inside them, so the JNIEnv of the running call
 * is the one the callbacks use. Nothing is shared between sessions.
 *
 * While a Java exception is pending, the callbacks fail without calling into
 * Java, and the exception reaches Kotlin when the running call returns.
 */

#include <errno.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <archive.h>
#include <archive_entry.h>

/* Status codes shared with NativeArchive.kt. The negative ones are libarchive's. */
#define STATUS_STOPPED 2
#define STATUS_ENCRYPTED_HEADERS 3

/*
 * A read right after a seek or a skip is usually for one local header, so it
 * asks for little. Reads that follow on from the previous one ask for a full
 * buffer.
 */
#define FIRST_READ_SIZE 4096

typedef struct {
	JNIEnv *env;
	jobject bridge;
	jmethodID read;
	jmethodID header;
	jmethodID data;
	const void *buffer;
	jint capacity;
	int64_t size;
	int64_t position;
	int sequential;
	struct archive *archive;
} session;

static session *from_handle(jlong handle) {
	return (session *)(intptr_t)handle;
}

static la_ssize_t read_callback(struct archive *a, void *data, const void **buffer) {
	session *s = data;
	JNIEnv *env = s->env;
	if ((*env)->ExceptionCheck(env)) {
		archive_set_error(a, ECANCELED, "Java exception pending");
		return ARCHIVE_FATAL;
	}
	jint length = s->sequential ? s->capacity : FIRST_READ_SIZE;
	if (length > s->capacity) length = s->capacity;
	jint n = (*env)->CallIntMethod(env, s->bridge, s->read, (jlong)s->position, length);
	if ((*env)->ExceptionCheck(env) || n < 0 || n > length) {
		archive_set_error(a, EIO, "ByteSource read failed");
		return ARCHIVE_FATAL;
	}
	*buffer = s->buffer;
	s->position += n;
	s->sequential = 1;
	return n;
}

static la_int64_t seek_callback(struct archive *a, void *data, la_int64_t offset, int whence) {
	session *s = data;
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

static la_int64_t skip_callback(struct archive *a, void *data, la_int64_t request) {
	(void)a;
	session *s = data;
	int64_t left = s->size > s->position ? s->size - s->position : 0;
	int64_t skipped = request < left ? request : left;
	if (skipped < 0) skipped = 0;
	s->position += skipped;
	s->sequential = 0;
	return skipped;
}

static void free_archive(session *s) {
	if (s->archive != NULL) {
		archive_read_free(s->archive);
		s->archive = NULL;
	}
}

JNIEXPORT jlong JNICALL
Java_io_github_yuubiz_hirari_core_archive_NativeArchive_nativeCreate(
	JNIEnv *env, jobject self, jlong size, jobject buffer) {
	session *s = calloc(1, sizeof(*s));
	if (s == NULL) return 0;
	jclass cls = (*env)->GetObjectClass(env, self);
	s->read = (*env)->GetMethodID(env, cls, "read", "(JI)I");
	if (s->read != NULL) s->header = (*env)->GetMethodID(env, cls, "header", "([BJZZLjava/lang/String;II)V");
	if (s->header != NULL) s->data = (*env)->GetMethodID(env, cls, "data", "(Ljava/nio/ByteBuffer;J)Z");
	(*env)->DeleteLocalRef(env, cls);
	if (s->data != NULL) s->buffer = (*env)->GetDirectBufferAddress(env, buffer);
	if (s->buffer != NULL) s->capacity = (jint)(*env)->GetDirectBufferCapacity(env, buffer);
	if (s->capacity > 0) s->bridge = (*env)->NewGlobalRef(env, self);
	if (s->bridge == NULL) {
		free(s);
		return 0;
	}
	s->size = size;
	return (jlong)(intptr_t)s;
}

/* Frees the archive the session had open, if any, and opens the source again from the start. */
JNIEXPORT jint JNICALL
Java_io_github_yuubiz_hirari_core_archive_NativeArchive_nativeOpen(JNIEnv *env, jobject self, jlong handle) {
	(void)self;
	session *s = from_handle(handle);
	s->env = env;
	free_archive(s);
	s->position = 0;
	s->sequential = 0;
	struct archive *a = archive_read_new();
	if (a == NULL) return ARCHIVE_FATAL;
	s->archive = a;
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
	return r;
}

/*
 * Reads the next header and passes it to the bridge's header method. The name
 * is null when libarchive could not give one. Returns STATUS_ENCRYPTED_HEADERS
 * when the header could not be read because it is encrypted: libarchive marks
 * the entry it was filling, which it hands back even when the read fails.
 */
JNIEXPORT jint JNICALL
Java_io_github_yuubiz_hirari_core_archive_NativeArchive_nativeNextHeader(JNIEnv *env, jobject self, jlong handle) {
	(void)self;
	session *s = from_handle(handle);
	s->env = env;
	struct archive_entry *entry = NULL;
	int r = archive_read_next_header(s->archive, &entry);
	if (r != ARCHIVE_OK && r != ARCHIVE_WARN) {
		if (r != ARCHIVE_EOF && entry != NULL && archive_entry_is_metadata_encrypted(entry)) {
			return STATUS_ENCRYPTED_HEADERS;
		}
		return r;
	}

	jbyteArray name = NULL;
	const char *pathname = archive_entry_pathname(entry);
	if (pathname != NULL) {
		jsize length = (jsize)strlen(pathname);
		name = (*env)->NewByteArray(env, length);
		if (name == NULL) return ARCHIVE_FATAL;
		(*env)->SetByteArrayRegion(env, name, 0, length, (const jbyte *)pathname);
	}
	const char *format_name = archive_format_name(s->archive);
	jstring format = (*env)->NewStringUTF(env, format_name != NULL ? format_name : "");
	if (format == NULL) {
		(*env)->DeleteLocalRef(env, name);
		return ARCHIVE_FATAL;
	}
	(*env)->CallVoidMethod(env, s->bridge, s->header, name,
		archive_entry_size_is_set(entry) ? (jlong)archive_entry_size(entry) : (jlong)-1,
		(jboolean)(archive_entry_filetype(entry) == AE_IFDIR),
		(jboolean)(archive_entry_is_data_encrypted(entry) != 0),
		format,
		(jint)(archive_format(s->archive) & ARCHIVE_FORMAT_BASE_MASK),
		(jint)archive_filter_code(s->archive, 0));
	(*env)->DeleteLocalRef(env, name);
	(*env)->DeleteLocalRef(env, format);
	if ((*env)->ExceptionCheck(env)) return ARCHIVE_FATAL;
	return r;
}

/*
 * Passes the data of the current entry to the bridge's data method one block
 * at a time. The ByteBuffer views libarchive's block, which stays valid only
 * until the next archive_read_data_block call. Returns STATUS_STOPPED when the
 * bridge declines a block.
 */
JNIEXPORT jint JNICALL
Java_io_github_yuubiz_hirari_core_archive_NativeArchive_nativeReadData(JNIEnv *env, jobject self, jlong handle) {
	(void)self;
	session *s = from_handle(handle);
	s->env = env;
	for (;;) {
		const void *block;
		size_t size;
		la_int64_t offset;
		int r = archive_read_data_block(s->archive, &block, &size, &offset);
		if (r == ARCHIVE_EOF) return ARCHIVE_OK;
		if (r != ARCHIVE_OK && r != ARCHIVE_WARN) return r;
		if (size == 0) continue;
		jobject view = (*env)->NewDirectByteBuffer(env, (void *)block, (jlong)size);
		if (view == NULL) return ARCHIVE_FATAL;
		jboolean more = (*env)->CallBooleanMethod(env, s->bridge, s->data, view, (jlong)offset);
		(*env)->DeleteLocalRef(env, view);
		if ((*env)->ExceptionCheck(env)) return ARCHIVE_FATAL;
		if (!more) return STATUS_STOPPED;
	}
}

JNIEXPORT void JNICALL
Java_io_github_yuubiz_hirari_core_archive_NativeArchive_nativeFree(JNIEnv *env, jobject self, jlong handle) {
	(void)self;
	session *s = from_handle(handle);
	s->env = env;
	free_archive(s);
	(*env)->DeleteGlobalRef(env, s->bridge);
	free(s);
}
