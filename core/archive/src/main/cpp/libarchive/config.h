/*
 * libarchive configuration for Android (bionic, API 28 and later), covering
 * only the macros that the read-only sources in CMakeLists.txt test.
 *
 * HAVE_ICONV, HAVE_NL_LANGINFO and HAVE_LOCALE_CHARSET stay undefined, so
 * libarchive takes UTF-8 as the locale charset on bionic whatever the process
 * locale is, and converts no names except those it reads as UTF-8 or UTF-16.
 *
 * liblzma, bzip2 and zstd come from third_party/ and are built by
 * CMakeLists.txt, so their headers and libraries are always there.
 */

#define HAVE_ARC4RANDOM_BUF 1
#define HAVE_BZLIB_H 1
#define HAVE_DECL_INT32_MAX 1
#define HAVE_DECL_INT32_MIN 1
#define HAVE_DECL_INT64_MAX 1
#define HAVE_DECL_INT64_MIN 1
#define HAVE_DECL_INTMAX_MAX 1
#define HAVE_DECL_INTMAX_MIN 1
#define HAVE_DECL_SIZE_MAX 1
#define HAVE_DECL_SSIZE_MAX 1
#define HAVE_DECL_UINT32_MAX 1
#define HAVE_DECL_UINT64_MAX 1
#define HAVE_DECL_UINTMAX_MAX 1
#define HAVE_EILSEQ 1
#define HAVE_ERRNO_H 1
#define HAVE_FCNTL_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_LIBLZMA 1
#define HAVE_LIBZSTD 1
#define HAVE_LIMITS_H 1
#define HAVE_LOCALTIME_R 1
#define HAVE_LZMA_H 1
#define HAVE_MBRTOWC 1
#define HAVE_STDINT_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_TIME_H 1
#define HAVE_UNISTD_H 1
#define HAVE_WCHAR_H 1
#define HAVE_WCRTOMB 1
#define HAVE_WCSLEN 1
#define HAVE_WCTOMB 1
#define HAVE_WMEMCMP 1
#define HAVE_WMEMCPY 1
#define HAVE_WMEMMOVE 1
#define HAVE_ZLIB_H 1
#define HAVE_ZSTD_H 1
#define MAJOR_IN_SYSMACROS 1
