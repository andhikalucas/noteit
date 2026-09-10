cmake_minimum_required(VERSION 3.22.1)

if(NOT NOTEIT_PUNCTUATION_BENCHMARK)
  message(FATAL_ERROR "SentencePiece is only available in the optional punctuation benchmark.")
endif()

include(FetchContent)
# Official v0.2.1 tag resolves to this immutable commit. Keep the archive hash
# independent of a mutable tag or a preinstalled/system tokenizer runtime.
FetchContent_Declare(punctuation_sentencepiece
  URL https://codeload.github.com/google/sentencepiece/tar.gz/31646a467d2051eb904e0b45de3a73e91fe1c1e3
  URL_HASH SHA256=b4eb17e6bea5c9380ddd79d04d01d6aa5e280be8f3e3cef1f745a80e6d9451ce)
FetchContent_GetProperties(punctuation_sentencepiece)
if(NOT punctuation_sentencepiece_POPULATED)
  FetchContent_Populate(punctuation_sentencepiece)
endif()

set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_NFKC_COMPILE OFF CACHE BOOL "" FORCE)
set(SPM_PROTOBUF_PROVIDER internal CACHE STRING "" FORCE)
set(SPM_ABSL_PROVIDER internal CACHE STRING "" FORCE)
# Upstream defines CLI/trainer targets, but none is part of our target graph.
add_subdirectory("${punctuation_sentencepiece_SOURCE_DIR}"
                 "${punctuation_sentencepiece_BINARY_DIR}" EXCLUDE_FROM_ALL)
set_target_properties(sentencepiece-static PROPERTIES POSITION_INDEPENDENT_CODE ON
  CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)

add_library(noteit_sentencepiece SHARED punctuation_tokenizer_jni.cpp)
target_compile_features(noteit_sentencepiece PRIVATE cxx_std_17)
target_include_directories(noteit_sentencepiece PRIVATE "${punctuation_sentencepiece_SOURCE_DIR}/src")
set_target_properties(noteit_sentencepiece PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
target_link_libraries(noteit_sentencepiece PRIVATE sentencepiece-static log)
target_link_options(noteit_sentencepiece PRIVATE "-Wl,--exclude-libs,ALL"
  "-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")
