cmake_minimum_required(VERSION 3.22)
project(hirari_fuzz LANGUAGES C CXX)

# Host-side libFuzzer targets for the native readers (docs/spec.md section 6.2). The libraries are the ones the
# app builds: this file reuses the CMakeLists.txt of :core:archive and :core:image as they are, and builds only
# their static library targets, with AddressSanitizer, UndefinedBehaviorSanitizer and libFuzzer's coverage
# instrumentation. Needs clang, which has libFuzzer, and the zlib headers.

if(NOT CMAKE_C_COMPILER_ID STREQUAL "Clang")
    message(FATAL_ERROR "libFuzzer needs clang: cmake -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++")
endif()

set(SANITIZE "address,undefined")
set(FUZZ_FLAGS "-fsanitize=fuzzer-no-link,${SANITIZE} -fno-sanitize-recover=undefined -fno-omit-frame-pointer -g")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${FUZZ_FLAGS}")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${FUZZ_FLAGS}")
# The app's own libraries build with -O2 and without NDEBUG for libjxl; keep an optimised build with assertions on.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
set(CMAKE_C_FLAGS_RELEASE "-O1")
set(CMAKE_CXX_FLAGS_RELEASE "-O1")

get_filename_component(REPO ${CMAKE_CURRENT_SOURCE_DIR}/../../.. ABSOLUTE)
add_subdirectory(${REPO}/core/archive/src/main/cpp archive_lib EXCLUDE_FROM_ALL)
add_subdirectory(${REPO}/core/image/src/main/cpp image_lib EXCLUDE_FROM_ALL)

# liblzma 5.8.4 adds 0 to a null pointer when it starts a decoder (lzma_decoder.c, rc_to_local, with an empty input). The
# addition is undefined in C and does nothing on every target the app runs on, and UBSan stops on it within a minute,
# which would hide everything after it. The other checks stay on.
target_compile_options(lzma PRIVATE -fno-sanitize=pointer-overflow)

add_executable(archive_fuzzer archive_fuzzer.c)
target_link_libraries(archive_fuzzer PRIVATE archive lzma bz2 zstd z)
target_link_options(archive_fuzzer PRIVATE -fsanitize=fuzzer,${SANITIZE})

add_executable(jxl_fuzzer jxl_fuzzer.cc)
target_include_directories(jxl_fuzzer PRIVATE ${REPO}/third_party/libjxl/lib/include)
target_link_libraries(jxl_fuzzer PRIVATE jxl_dec hwy)
target_link_options(jxl_fuzzer PRIVATE -fsanitize=fuzzer,${SANITIZE})
