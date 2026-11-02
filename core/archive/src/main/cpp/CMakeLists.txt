cmake_minimum_required(VERSION 3.22)
project(hirari_archive LANGUAGES C)

set(CMAKE_C_VISIBILITY_PRESET hidden)

set(LIBARCHIVE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party/libarchive/libarchive)

add_library(archive STATIC
    ${LIBARCHIVE_DIR}/archive_acl.c
    ${LIBARCHIVE_DIR}/archive_check_magic.c
    ${LIBARCHIVE_DIR}/archive_crc32.c
    ${LIBARCHIVE_DIR}/archive_cryptor.c
    ${LIBARCHIVE_DIR}/archive_entry.c
    ${LIBARCHIVE_DIR}/archive_entry_sparse.c
    ${LIBARCHIVE_DIR}/archive_entry_xattr.c
    ${LIBARCHIVE_DIR}/archive_hmac.c
    ${LIBARCHIVE_DIR}/archive_options.c
    ${LIBARCHIVE_DIR}/archive_random.c
    ${LIBARCHIVE_DIR}/archive_rb.c
    ${LIBARCHIVE_DIR}/archive_read.c
    ${LIBARCHIVE_DIR}/archive_read_add_passphrase.c
    ${LIBARCHIVE_DIR}/archive_read_support_format_zip.c
    ${LIBARCHIVE_DIR}/archive_string.c
    ${LIBARCHIVE_DIR}/archive_string_sprintf.c
    ${LIBARCHIVE_DIR}/archive_time.c
    ${LIBARCHIVE_DIR}/archive_util.c
    ${LIBARCHIVE_DIR}/archive_virtual.c
    libarchive/ppmd8_unsupported.c
)
target_compile_definitions(archive PRIVATE HAVE_CONFIG_H=1 _FILE_OFFSET_BITS=64)
target_include_directories(archive
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/libarchive
    PUBLIC ${LIBARCHIVE_DIR}
)

add_library(hirari_archive SHARED libarchive_jni.c)
target_compile_options(hirari_archive PRIVATE -Wall -Wextra)
target_link_libraries(hirari_archive PRIVATE archive z)
