cmake_minimum_required(VERSION 3.22)
project(hirari_image LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

set(THIRD_PARTY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../third_party)
set(LIBJXL_DIR ${THIRD_PARTY_DIR}/libjxl)
set(HWY_DIR ${THIRD_PARTY_DIR}/highway)

# The debug variant builds without NDEBUG and at -O0. libjxl then traps on its debug assertions, which hostile
# input can reach, and decodes too slowly for the time limit, so the libraries build as release in every variant.
set(LIBRARY_OPTIONS -O2 -fno-exceptions -fno-rtti -fmerge-all-constants -Wno-unused-parameter)
# Only the baseline SIMD target of each ABI is compiled. Dispatching at run time to newer targets multiplies the
# code size (docs/spec.md, Q7).
set(LIBRARY_DEFINITIONS NDEBUG HWY_COMPILE_ONLY_STATIC=1 HWY_STATIC_DEFINE)

add_library(hwy STATIC
    ${HWY_DIR}/hwy/abort.cc
    ${HWY_DIR}/hwy/aligned_allocator.cc
    ${HWY_DIR}/hwy/per_target.cc
    ${HWY_DIR}/hwy/print.cc
    ${HWY_DIR}/hwy/targets.cc
)
target_compile_options(hwy PRIVATE ${LIBRARY_OPTIONS})
target_compile_definitions(hwy PUBLIC ${LIBRARY_DEFINITIONS})
target_include_directories(hwy PUBLIC ${HWY_DIR})

include(${LIBJXL_DIR}/lib/jxl_lists.cmake)
set(JXL_DEC_SOURCES ${JPEGXL_INTERNAL_DEC_SOURCES})
list(FILTER JXL_DEC_SOURCES INCLUDE REGEX "\\.cc$")
list(TRANSFORM JXL_DEC_SOURCES PREPEND ${LIBJXL_DIR}/lib/)

set(JPEGXL_MAJOR_VERSION 0)
set(JPEGXL_MINOR_VERSION 12)
set(JPEGXL_PATCH_VERSION 0)
configure_file(${LIBJXL_DIR}/lib/jxl/version.h.in ${CMAKE_CURRENT_BINARY_DIR}/include/jxl/version.h @ONLY)

add_library(jxl_dec STATIC ${JXL_DEC_SOURCES})
target_compile_options(jxl_dec PRIVATE ${LIBRARY_OPTIONS})
# Boxes and JPEG reconstruction are what need brotli. Pixels decode without either.
target_compile_definitions(jxl_dec
    PUBLIC JXL_STATIC_DEFINE
    PRIVATE JXL_INTERNAL_LIBRARY_BUILD JPEGXL_ENABLE_BOXES=0 JPEGXL_ENABLE_TRANSCODE_JPEG=0
)
target_include_directories(jxl_dec
    PUBLIC
        ${LIBJXL_DIR}/lib/include
        ${CMAKE_CURRENT_SOURCE_DIR}/libjxl/include
        ${CMAKE_CURRENT_BINARY_DIR}/include
    PRIVATE ${LIBJXL_DIR}
)
target_link_libraries(jxl_dec PRIVATE hwy)

add_library(hirari_jxl SHARED jxl_jni.cc)
target_compile_options(hirari_jxl PRIVATE -Wall -Wextra -fno-exceptions -fno-rtti)
target_link_libraries(hirari_jxl PRIVATE jxl_dec hwy jnigraphics)
target_link_options(hirari_jxl PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
