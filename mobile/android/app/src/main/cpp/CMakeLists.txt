cmake_minimum_required(VERSION 3.18)
project(noteit_whisper C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

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
)
FetchContent_MakeAvailable(whispercpp)

find_library(LOG_LIB log)

add_library(noteit_whisper SHARED whisper_jni.c)
target_link_libraries(noteit_whisper whisper ${LOG_LIB} android)
