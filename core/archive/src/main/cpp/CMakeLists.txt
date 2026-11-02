cmake_minimum_required(VERSION 3.22)
project(hirari_archive LANGUAGES C)

set(CMAKE_C_VISIBILITY_PRESET hidden)

set(THIRD_PARTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party)
set(LIBARCHIVE_DIR ${THIRD_PARTY_DIR}/libarchive/libarchive)
set(LZMA_DIR ${THIRD_PARTY_DIR}/xz/src)
set(BZIP2_DIR ${THIRD_PARTY_DIR}/bzip2)
set(ZSTD_DIR ${THIRD_PARTY_DIR}/zstd/lib)

# liblzma's decoders only: LZMA1, LZMA2, delta and the branch filters, with the
# CRC32, CRC64 and SHA-256 checks of the .xz format. No encoders, no threads.
set(LZMA_FILTERS x86 arm armthumb arm64 powerpc ia64 sparc riscv)
set(LZMA_SOURCES
    ${LZMA_DIR}/common/tuklib_physmem.c
    ${LZMA_DIR}/liblzma/check/check.c
    ${LZMA_DIR}/liblzma/check/crc32_fast.c
    ${LZMA_DIR}/liblzma/check/crc64_fast.c
    ${LZMA_DIR}/liblzma/check/sha256.c
    ${LZMA_DIR}/liblzma/delta/delta_common.c
    ${LZMA_DIR}/liblzma/delta/delta_decoder.c
    ${LZMA_DIR}/liblzma/lz/lz_decoder.c
    ${LZMA_DIR}/liblzma/lzma/lzma_decoder.c
    ${LZMA_DIR}/liblzma/lzma/lzma_encoder_presets.c
    ${LZMA_DIR}/liblzma/lzma/lzma2_decoder.c
    ${LZMA_DIR}/liblzma/simple/simple_coder.c
    ${LZMA_DIR}/liblzma/simple/simple_decoder.c
)
foreach(name alone_decoder auto_decoder block_buffer_decoder block_decoder block_header_decoder block_util common
        easy_decoder_memusage easy_preset file_info filter_buffer_decoder filter_common filter_decoder
        filter_flags_decoder hardware_physmem index index_decoder index_hash stream_buffer_decoder stream_decoder
        stream_flags_common stream_flags_decoder string_conversion vli_decoder vli_size)
    list(APPEND LZMA_SOURCES ${LZMA_DIR}/liblzma/common/${name}.c)
endforeach()
foreach(filter ${LZMA_FILTERS})
    list(APPEND LZMA_SOURCES ${LZMA_DIR}/liblzma/simple/${filter}.c)
    string(TOUPPER ${filter} FILTER)
    list(APPEND LZMA_DECODERS HAVE_DECODER_${FILTER})
endforeach()
add_library(lzma STATIC ${LZMA_SOURCES})
target_compile_definitions(lzma
    PRIVATE
        HAVE_STDBOOL_H HAVE__BOOL HAVE_STDINT_H HAVE_INTTYPES_H HAVE_VISIBILITY=1
        HAVE_CHECK_CRC32 HAVE_CHECK_CRC64 HAVE_CHECK_SHA256
        HAVE_DECODERS HAVE_DECODER_LZMA1 HAVE_DECODER_LZMA2 HAVE_DECODER_DELTA ${LZMA_DECODERS}
        TUKLIB_SYMBOL_PREFIX=lzma_ TUKLIB_PHYSMEM_SYSCONF
    PUBLIC LZMA_API_STATIC
)
target_include_directories(lzma
    PRIVATE
        ${LZMA_DIR}/common
        ${LZMA_DIR}/liblzma/common
        ${LZMA_DIR}/liblzma/check
        ${LZMA_DIR}/liblzma/lz
        ${LZMA_DIR}/liblzma/rangecoder
        ${LZMA_DIR}/liblzma/lzma
        ${LZMA_DIR}/liblzma/delta
        ${LZMA_DIR}/liblzma/simple
    PUBLIC ${LZMA_DIR}/liblzma/api
)

# bzlib.c holds the compressor's entry points next to the decompressor's, so
# the compressor's files are compiled for the link, and the NDK's default
# --gc-sections drops them.
add_library(bz2 STATIC
    bzip2_error.c
    ${BZIP2_DIR}/blocksort.c
    ${BZIP2_DIR}/bzlib.c
    ${BZIP2_DIR}/compress.c
    ${BZIP2_DIR}/crctable.c
    ${BZIP2_DIR}/decompress.c
    ${BZIP2_DIR}/huffman.c
    ${BZIP2_DIR}/randtable.c
)
target_compile_definitions(bz2 PRIVATE BZ_NO_STDIO)
target_include_directories(bz2 PUBLIC ${BZIP2_DIR})

add_library(zstd STATIC
    ${ZSTD_DIR}/common/debug.c
    ${ZSTD_DIR}/common/entropy_common.c
    ${ZSTD_DIR}/common/error_private.c
    ${ZSTD_DIR}/common/fse_decompress.c
    ${ZSTD_DIR}/common/xxhash.c
    ${ZSTD_DIR}/common/zstd_common.c
    ${ZSTD_DIR}/decompress/huf_decompress.c
    ${ZSTD_DIR}/decompress/zstd_ddict.c
    ${ZSTD_DIR}/decompress/zstd_decompress.c
    ${ZSTD_DIR}/decompress/zstd_decompress_block.c
)
target_compile_definitions(zstd PRIVATE ZSTD_DISABLE_ASM=1 ZSTD_LEGACY_SUPPORT=0 ZSTD_MULTITHREAD_SUPPORT_DEFAULT=0)
target_include_directories(zstd PUBLIC ${ZSTD_DIR})

add_library(archive STATIC
    ${LIBARCHIVE_DIR}/archive_acl.c
    ${LIBARCHIVE_DIR}/archive_blake2s_ref.c
    ${LIBARCHIVE_DIR}/archive_blake2sp_ref.c
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
    ${LIBARCHIVE_DIR}/archive_read_support_filter_bzip2.c
    ${LIBARCHIVE_DIR}/archive_read_support_filter_gzip.c
    ${LIBARCHIVE_DIR}/archive_read_support_filter_xz.c
    ${LIBARCHIVE_DIR}/archive_read_support_filter_zstd.c
    ${LIBARCHIVE_DIR}/archive_read_support_format_7zip.c
    ${LIBARCHIVE_DIR}/archive_read_support_format_rar.c
    ${LIBARCHIVE_DIR}/archive_read_support_format_rar5.c
    ${LIBARCHIVE_DIR}/archive_read_support_format_tar.c
    ${LIBARCHIVE_DIR}/archive_read_support_format_zip.c
    ${LIBARCHIVE_DIR}/archive_string.c
    ${LIBARCHIVE_DIR}/archive_string_sprintf.c
    ${LIBARCHIVE_DIR}/archive_time.c
    ${LIBARCHIVE_DIR}/archive_util.c
    ${LIBARCHIVE_DIR}/archive_virtual.c
    libarchive/ppmd7_unsupported.c
    libarchive/ppmd8_unsupported.c
)
target_compile_definitions(archive PRIVATE HAVE_CONFIG_H=1 _FILE_OFFSET_BITS=64)
target_include_directories(archive
    PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/libarchive
    PUBLIC ${LIBARCHIVE_DIR}
)
target_link_libraries(archive PRIVATE lzma bz2 zstd z)

add_library(hirari_archive SHARED libarchive_jni.c)
target_compile_options(hirari_archive PRIVATE -Wall -Wextra)
target_include_directories(hirari_archive PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/libarchive)
# zstd and liblzma mark their API for export. Only the JNI functions are, so
# that --gc-sections drops the parts of the libraries no reader calls.
target_link_options(hirari_archive PRIVATE -Wl,--exclude-libs,ALL)
target_link_libraries(hirari_archive PRIVATE archive lzma bz2 zstd z)
