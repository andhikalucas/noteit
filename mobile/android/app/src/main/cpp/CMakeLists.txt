cmake_minimum_required(VERSION 3.18)
project(noteit_whisper C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
# Also optimize any invocation that selects Debug rather than RelWithDebInfo;
# older cached Debug builds otherwise contain no optimization flag.
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O3")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3")

# Build whisper.cpp from source, fetched at configure time (pinned commit).
# Keeps the repo small — no vendored native tree — while reusing upstream's
# maintained CMake. The same libwhisper serves the bubble (Kotlin, direct JNI)
# and in-app notes (Flutter via MethodChannel).
include(FetchContent)

set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)  # avoid libomp dependency
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)  # cross-compile: no -march=native

# Experimental build only. Keep CPU as the shipping path until A55 sustained
# benchmarks pass; a Vulkan-enabled binary also needs useGpu=true at runtime.
option(NOTEIT_WHISPER_VULKAN "Build experimental Vulkan benchmark backend" OFF)
set(GGML_VULKAN ${NOTEIT_WHISPER_VULKAN} CACHE BOOL "" FORCE)

# arm64 phones from ~2018 on support armv8.2 half-precision vector arithmetic
# and dot-product instructions; ggml is ~2-3x faster on the encoder with them
# (this mirrors upstream's whisper.android example, which uses +fp16).
# dotprod additionally accelerates quantized (q*_) models.
if (ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options(-march=armv8.2-a+fp16+dotprod)
endif()

FetchContent_Declare(
    whispercpp
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG        080bbbe85230f624f0b52127f1ae1218247989f9
    # Keep upstream LF bytes on Windows hosts with core.autocrlf=true; the
    # source patches below verify exact SHA-256 hashes of the checkout.
    GIT_CONFIG     core.autocrlf=false core.eol=lf
)
FetchContent_MakeAvailable(whispercpp)

# Initialize each call's audio context before language detection, and clear
# stale flash-attention padding before each encoder pass. Both pinned source
# transformations are checked byte-for-byte and idempotent.
set(WHISPER_AUDIO_CTX_SOURCE "${whispercpp_SOURCE_DIR}/src/whisper.cpp")
include("${CMAKE_CURRENT_SOURCE_DIR}/cmake/patch_whisper_flash_padding.cmake")

find_library(LOG_LIB log)

add_library(noteit_whisper SHARED whisper_jni.c)
target_compile_definitions(noteit_whisper PRIVATE NOTEIT_WHISPER_VULKAN=$<BOOL:${NOTEIT_WHISPER_VULKAN}>)
target_link_libraries(noteit_whisper PRIVATE whisper ${LOG_LIB} android)

# Benchmark-only: personal builds do not package the llama.cpp cleanup runtime.
option(NOTEIT_CLEANUP_RUNTIME "Build and package the llama.cpp cleanup runtime" OFF)
if(NOTEIT_CLEANUP_RUNTIME)
    # llama.cpp uses a newer ggml ABI than pinned Whisper. Build it in its own
    # CMake project and hide all its static-library symbols inside the JNI DSO.
    # Sharing a ggml target or exported ggml_* symbols would corrupt one runtime.
    FetchContent_Declare(llamacleanup
        URL https://codeload.github.com/ggml-org/llama.cpp/tar.gz/434ddbbc0e30522e897670681e503b797c12b7c1
        URL_HASH SHA256=dbcd40af496a36667a0b78eabc406b4545df9ff6ce34a5a06f5390c19b67ab17
        SOURCE_SUBDIR noteit-external-only)
    FetchContent_MakeAvailable(llamacleanup)
    include(ExternalProject)
    set(CLEANUP_BINARY_DIR "${CMAKE_CURRENT_BINARY_DIR}/cleanup-runtime")
    ExternalProject_Add(cleanup_runtime_build
        SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/cleanup_runtime"
        BINARY_DIR "${CLEANUP_BINARY_DIR}"
        CMAKE_ARGS
            -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
            -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
            -DANDROID_ABI=${ANDROID_ABI}
            -DANDROID_PLATFORM=${ANDROID_PLATFORM}
            -DANDROID_STL=c++_shared
            -DCMAKE_BUILD_TYPE=Release
            -DLLAMA_SOURCE_DIR=${llamacleanup_SOURCE_DIR}
        BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target noteit_cleanup --parallel 4
        INSTALL_COMMAND ""
        BUILD_ALWAYS TRUE
        BUILD_BYPRODUCTS "${CLEANUP_BINARY_DIR}/libnoteit_cleanup.so")
    add_library(noteit_cleanup SHARED IMPORTED GLOBAL)
    set_target_properties(noteit_cleanup PROPERTIES IMPORTED_LOCATION "${CLEANUP_BINARY_DIR}/libnoteit_cleanup.so")
    add_dependencies(noteit_cleanup cleanup_runtime_build)
    # Make the imported runtime a packaged dependency even before a cleanup model
    # is downloaded. Loading the DSO itself does not load a model or allocate KV cache.
    target_link_libraries(noteit_whisper PRIVATE "-Wl,--no-as-needed" noteit_cleanup "-Wl,--as-needed")
endif()

option(NOTEIT_PUNCTUATION_BENCHMARK "Build the isolated SentencePiece tokenizer benchmark" OFF)
if(NOTEIT_PUNCTUATION_BENCHMARK)
    add_subdirectory("${CMAKE_CURRENT_LIST_DIR}/../../punctuationBenchmark/cpp"
                     "${CMAKE_CURRENT_BINARY_DIR}/punctuation-tokenizer")
endif()
