# Composes the existing per-call audio-context patch with flash-padding reset.
# To activate later, replace the audio-context include in CMakeLists with this
# wrapper. Do not include the audio-context script first: its exact hash check
# intentionally does not accept our additional patched bytes.
if(NOT DEFINED WHISPER_AUDIO_CTX_SOURCE OR NOT EXISTS "${WHISPER_AUDIO_CTX_SOURCE}")
    message(FATAL_ERROR "Missing pinned whisper.cpp source for flash-padding patch")
endif()
set(_noteit_padding_input_sha "33679c9de7610775a8ff8c3cd307e35568aae82b888632cfd1a7d66f6982fbe6")
set(_noteit_padding_result_sha "3fdd9feb15ecd3a16b4deef320e0cf6a7dc200f07952b4f2f5c813fef9fcea79")
file(SHA256 "${WHISPER_AUDIO_CTX_SOURCE}" _noteit_padding_source_sha)
if(_noteit_padding_source_sha STREQUAL _noteit_padding_result_sha)
    return()
endif()

# Validates the original pinned source or its exact audio-context-only patch.
# This is skipped on subsequent configurations of our combined patched source.
include("${CMAKE_CURRENT_LIST_DIR}/patch_whisper_audio_ctx.cmake")
file(SHA256 "${WHISPER_AUDIO_CTX_SOURCE}" _noteit_padding_source_sha)
if(NOT _noteit_padding_source_sha STREQUAL _noteit_padding_input_sha)
    message(FATAL_ERROR "Whisper flash-padding patch refused: unexpected input bytes")
endif()

set(_noteit_padding_anchor [=[    const int64_t t_start_us = ggml_time_us();

    // conv
]=])
set(_noteit_padding_replacement [=[    const int64_t t_start_us = ggml_time_us();

    // Reset flash-attention padding before this encoder pass writes fresh K/V.
    // A shorter context must not read cached values from an earlier, longer pass.
    if (wctx.params.flash_attn) {
        ggml_backend_buffer_clear(wstate.kv_pad.buffer, 0);
        ggml_backend_buffer_clear(wstate.kv_cross.buffer, 0);
    }

    // conv
]=])
file(READ "${WHISPER_AUDIO_CTX_SOURCE}" _noteit_padding_source)
string(FIND "${_noteit_padding_source}" "${_noteit_padding_anchor}" _noteit_padding_anchor_index)
if(_noteit_padding_anchor_index LESS 0)
    message(FATAL_ERROR "Whisper flash-padding patch refused: encoder anchor missing")
endif()
string(REPLACE "${_noteit_padding_anchor}" "${_noteit_padding_replacement}"
    _noteit_padding_result "${_noteit_padding_source}")
string(SHA256 _noteit_padding_actual_sha "${_noteit_padding_result}")
if(NOT _noteit_padding_actual_sha STREQUAL _noteit_padding_result_sha)
    message(FATAL_ERROR "Whisper flash-padding patch refused: unexpected patched bytes")
endif()
# file(WRITE) converts LF to CRLF on Windows hosts; CONFIGURE keeps upstream LF bytes.
file(CONFIGURE OUTPUT "${WHISPER_AUDIO_CTX_SOURCE}" CONTENT "${_noteit_padding_result}" @ONLY NEWLINE_STYLE UNIX)
file(SHA256 "${WHISPER_AUDIO_CTX_SOURCE}" _noteit_padding_written_sha)
if(NOT _noteit_padding_written_sha STREQUAL _noteit_padding_result_sha)
    message(FATAL_ERROR "Whisper flash-padding patch refused: written bytes do not match expected result")
endif()
message(STATUS "Applied pinned Whisper flash-attention padding reset patch")
