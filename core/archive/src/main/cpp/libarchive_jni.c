#include <jni.h>
#include <string.h>

#include <archive.h>
#include <archive_entry.h>

static void throw_new(JNIEnv *env, const char *class_name, const char *message) {
	jclass cls = (*env)->FindClass(env, class_name);
	if (cls != NULL) (*env)->ThrowNew(env, cls, message);
}

static void throw_archive_error(JNIEnv *env, struct archive *a) {
	const char *message = archive_error_string(a);
	throw_new(env, "io/github/yuubiz/hirari/core/archive/ArchiveException",
		message != NULL ? message : "libarchive failed without a message");
}

/* Returns 0 with a Java exception pending. */
static int add_name(JNIEnv *env, jobject out, jmethodID add, const char *name) {
	jsize length = (jsize)strlen(name);
	jbyteArray bytes = (*env)->NewByteArray(env, length);
	if (bytes == NULL) return 0;
	(*env)->SetByteArrayRegion(env, bytes, 0, length, (const jbyte *)name);
	(*env)->CallBooleanMethod(env, out, add, bytes);
	(*env)->DeleteLocalRef(env, bytes);
	return !(*env)->ExceptionCheck(env);
}

JNIEXPORT void JNICALL
Java_io_github_yuubiz_hirari_core_archive_Libarchive_readEntryNames(
	JNIEnv *env, jobject self, jint fd, jobject out) {
	(void)self;
	jmethodID add = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, out), "add", "(Ljava/lang/Object;)Z");
	if (add == NULL) return;

	struct archive *a = archive_read_new();
	if (a == NULL) {
		throw_new(env, "java/lang/OutOfMemoryError", "archive_read_new");
		return;
	}
	if (archive_read_support_format_zip(a) != ARCHIVE_OK || archive_read_open_fd(a, fd, 64 * 1024) != ARCHIVE_OK) {
		throw_archive_error(env, a);
		archive_read_free(a);
		return;
	}
	for (;;) {
		struct archive_entry *entry;
		int r = archive_read_next_header(a, &entry);
		if (r == ARCHIVE_EOF) break;
		const char *name = r == ARCHIVE_OK || r == ARCHIVE_WARN ? archive_entry_pathname(entry) : NULL;
		if (name == NULL) {
			throw_archive_error(env, a);
			break;
		}
		if (!add_name(env, out, add, name)) break;
	}
	archive_read_free(a);
}
